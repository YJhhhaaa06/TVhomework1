package com.itheima.content.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.comment.dao.CommentDao;
import com.itheima.config.AppConfig;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.exception.DatabaseException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 评论缓存（C 周期 T3，自 ContentCacheManager 迁出评论职责）：统一 Redis 缓存层。
 *
 * <p>评论树走 T1 CacheAside 三态（miss/hit-empty/hit-data + 空标记 60s + 单飞 + 写失败 DEL），
 * 独立 TTL（{@code cache.comment.ttlMinutes}），与内容缓存解耦（NEEDS 4.5：同生同灭 → 业务显式失效）。
 * 评论增/删/点赞 = 失效 {@code content:comments:{id}} 让读自愈（DB 为源真理，不原地改 JSON 树，
 * 消除旧内存树并发增删竞态 H1）。
 *
 * <p>三态约定：getCommentTree 返回 null = 已确认无评论（hit-empty 空标记）或降级重试不可用；
 * 调用方按"无评论"处理即可，**不得**把 miss 当"没有评论"（4.3 评论 miss ≠ 没有评论）。
 * 任何缓存失败一律降级走 DB（4.2），不导致业务失败。
 */
@Component
public class CommentCache {

    private static final Logger LOGGER = LogUtil.getLogger(CommentCache.class);
    private static final TypeReference<List<CommentCacheDTO>> COMMENT_TREE_TYPE = new TypeReference<>() {
    };

    private final CommentDao commentDao;
    private final TransactionTemplate transactionTemplate;
    private final CacheAside cacheAside;

    @InjectConstructor
    public CommentCache(CommentDao commentDao, TransactionTemplate transactionTemplate,
                        CacheAside cacheAside) {
        this.commentDao = commentDao;
        this.transactionTemplate = transactionTemplate;
        this.cacheAside = cacheAside;
    }

    // ==================== 读路径（三态 Cache-Aside） ====================

    /**
     * 三态读评论树：miss → 查 DB 回填（有评论写数据 key，无评论写空标记）；hit-empty → null；
     * hit-data → 返回树。null = 已确认无评论（或降级走 DB 后仍无数据），调用方按空处理。
     */
    public List<CommentCacheDTO> getCommentTree(long contentId) {
        return cacheAside.get(CacheKeys.contentComments(contentId), COMMENT_TREE_TYPE,
                () -> loadCommentTree(contentId), ttlSeconds());
    }

    /**
     * 展平评论树为全部评论 id（含 children），供批量评论点赞状态查询（自 ContentCacheManager 迁入）。
     */
    public List<Long> collectCommentIds(List<CommentCacheDTO> tree) {
        List<Long> ids = new ArrayList<>();
        for (CommentCacheDTO ccVO : tree) {
            collectIdsRecursive(ccVO, ids);
        }
        return ids;
    }

    // ==================== 写路径（业务显式失效，均应在 DB 事务提交后调用，4.5） ====================

    /** 评论增/删后：失效评论树 key（含空标记），读自愈回填 DB 最新树。 */
    public void invalidateComments(long contentId) {
        cacheAside.invalidate(CacheKeys.contentComments(contentId));
    }

    /** 评论点赞/取消后：定位所属内容并失效其评论树，读自愈回填 DB 最新 like_count。 */
    public void notifyCommentLikeChanged(long commentId) {
        Long contentId = findContentIdByCommentId(commentId);
        if (contentId != null) {
            invalidateComments(contentId);
        }
    }

    // ==================== 内部 ====================

    /**
     * DB 装载评论树。loader 契约（三期 T3 负缓存治理，NEEDS N2）：
     * 返回 null = 确认无评论（DB 无行）→ 允许写空标记；抛 {@link DatabaseException} =
     * 加载失败（SQLException 由事务模板包装；意外异常统一包成 DatabaseException）→
     * CacheAside 不写空标记、不 DEL，本次读转 null（对外行为不变）。
     */
    private List<CommentCacheDTO> loadCommentTree(long contentId) {
        try {
            List<CommentCacheDTO> wholeList = transactionTemplate.execute(conn ->
                    commentDao.getComments(conn, contentId));
            if (wholeList == null || wholeList.isEmpty()) {
                return null; // 无评论 → 空标记（hit-empty），防持续穿透
            }
            return buildCommentTree(wholeList);
        } catch (DatabaseException e) {
            LOGGER.log(Level.SEVERE, "评论树 DB 查询失败（加载失败，不写空标记）, contentId=" + contentId, e);
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "评论树装载异常（按加载失败处理，不写空标记）, contentId=" + contentId, e);
            throw new DatabaseException("评论树装载失败", e);
        }
    }

    /** 楼中楼归一化：回复一律挂主楼（沿 parent 链上溯到顶，防御存量脏数据），自 ContentCacheManager 迁入。 */
    private List<CommentCacheDTO> buildCommentTree(List<CommentCacheDTO> list) {
        Map<Long, CommentCacheDTO> map = new HashMap<>();
        List<CommentCacheDTO> roots = new ArrayList<>();

        for (CommentCacheDTO c : list) {
            c.setChildren(new ArrayList<>());
            map.put(c.getCommentId(), c);
        }

        for (CommentCacheDTO c : list) {
            Long parentId = c.getParentId();
            if (parentId == null || parentId == 0) {
                roots.add(c);
                continue;
            }
            // 楼中楼：回复一律挂主楼（沿 parent 链上溯到顶，防御存量脏数据）
            CommentCacheDTO parent = map.get(parentId);
            if (parent == null) {
                continue; // 父缺失（理论不可达，迁移前已归一）
            }
            while (parent.getParentId() != null && parent.getParentId() != 0) {
                CommentCacheDTO ancestor = map.get(parent.getParentId());
                if (ancestor == null) break;
                parent = ancestor;
            }
            parent.getChildren().add(c);
        }
        return roots;
    }

    /** 定位评论所属 contentId（轻查询，仅点赞失效路径用）。 */
    private Long findContentIdByCommentId(long commentId) {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return commentDao.getContentIdByCommentId(conn, commentId);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "评论所属内容查询失败, commentId=" + commentId, e);
                    return null;
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "评论所属内容查询异常, commentId=" + commentId, e);
            return null;
        }
    }

    private static void collectIdsRecursive(CommentCacheDTO ccVO, List<Long> ids) {
        ids.add(ccVO.getCommentId());
        if (ccVO.getChildren() != null) {
            for (CommentCacheDTO child : ccVO.getChildren()) {
                collectIdsRecursive(child, ids);
            }
        }
    }

    private long ttlSeconds() {
        return AppConfig.getCommentTtlSeconds();
    }
}