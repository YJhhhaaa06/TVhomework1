package com.itheima.comment.service;

import com.itheima.comment.model.command.CommentCommand;
import com.itheima.comment.dao.CommentDao;
import com.itheima.content.service.CommentCache;
import com.itheima.content.service.ContentCache;
import com.itheima.content.dao.ContentDao;
import com.itheima.common.model.dto.PageResult;
import com.itheima.exception.*;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.vo.CommentVO;
import com.itheima.like.service.LikeService;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class CommentService {

    /** T10-B：每主楼首屏只带前 K 条楼中楼（与 CommentCache.PREVIEW_REPLIES_PER_ROOT 同值，两处需同步）。 */
    public static final int REPLY_PREVIEW_K = 2;

    private final CommentDao commentDao;
    private final ContentDao contentDao;
    private final ContentCache contentCache;
    private final CommentCache commentCache;
    private final LikeService likeService;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(CommentService.class);

    @InjectConstructor
    public CommentService(CommentDao commentDao, ContentDao contentDao,
                          ContentCache contentCache,
                          CommentCache commentCache,
                          LikeService likeService,
                          TransactionTemplate transactionTemplate) {
        this.commentDao = commentDao;
        this.contentDao = contentDao;
        this.contentCache = contentCache;
        this.commentCache = commentCache;
        this.likeService = likeService;
        this.transactionTemplate = transactionTemplate;
    }

    // ===== 转换：CommentCacheVO 树 → CommentVO 树（带 isLiked）=====

    public List<CommentVO> convertToCommentVOList(List<CommentCacheDTO> cacheList, Map<Long, Boolean> likedMap) {
        List<CommentVO> result = new ArrayList<>();
        for (CommentCacheDTO ccVO : cacheList) {
            result.add(convertToCommentVO(ccVO, likedMap));
        }
        return result;
    }

    private CommentVO convertToCommentVO(CommentCacheDTO ccVO, Map<Long, Boolean> likedMap) {
        CommentVO cVO = new CommentVO(
                ccVO.getUsername(), ccVO.getCommentId(), ccVO.getContentId(),
                ccVO.getUserId(), ccVO.getContent(), ccVO.getParentId(),
                ccVO.getLikeCount()
        );
        Boolean liked = likedMap.get(ccVO.getCommentId());
        cVO.setIsLiked(liked != null && liked);
        cVO.setReplyToUserId(ccVO.getReplyToUserId());
        cVO.setReplyToUsername(ccVO.getReplyToUsername());
        // T10-B：主楼回复总数需透传到 VO（CommentVO 继承 CommentCacheDTO，构造器不拷该字段）
        cVO.setReplyCount(ccVO.getReplyCount());

        if (ccVO.getChildren() != null) {
            List<CommentVO> childVOList = new ArrayList<>();
            for (CommentCacheDTO child : ccVO.getChildren()) {
                childVOList.add(convertToCommentVO(child, likedMap));
            }
            cVO.setChildren(new ArrayList<>(childVOList));
        }
        return cVO;
    }

    // ===== 展开（T10-B：主楼全部回复分页，/comment/replies） =====

    /**
     * 按主楼展开全部回复（T10-B 展开接口）：返回该主楼回复的分页信封。
     *
     * <p>{@code total} = 主楼 {@code reply_count}（= 建树上溯后 children 总数，与分页信封 children 前 K 口径一致）；
     * list = 直接回复 + 间接二级回复（comment_id 升序），页间不重不漏由 keyset 构造保证；
     * 点赞态只对该页回复批量查询（与主楼分页同款）。
     */
    public PageResult<CommentVO> getRepliesForRoot(long rootId, Long userId, int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            return new PageResult<>(new ArrayList<>(), 0, page, pageSize);
        }
        CommentCacheDTO root = transactionTemplate.execute(conn -> {
            try {
                return commentDao.findMainById(conn, rootId);
            } catch (SQLException e) {
                throw new DatabaseException("主楼评论查询失败", e);
            }
        });
        if (root == null) {
            throw new NotFoundException("评论不存在或已删除");
        }
        int total = root.getReplyCount();
        List<CommentCacheDTO> rows = transactionTemplate.execute(conn -> {
            try {
                return commentDao.getRepliesInTreeByRoot(conn, root.getContentId(), rootId, 0L,
                        toIntLimit((long) page * pageSize));
            } catch (SQLException e) {
                throw new DatabaseException("评论回复查询失败", e);
            }
        });
        List<CommentCacheDTO> pageList = slicePage(rows, page, pageSize);

        Map<Long, Boolean> likedMap = new HashMap<>();
        if (userId != null && !pageList.isEmpty()) {
            List<Long> replyIds = new ArrayList<>(pageList.size());
            for (CommentCacheDTO c : pageList) {
                replyIds.add(c.getCommentId());
            }
            likedMap = likeService.batchIsCommentLiked(userId, replyIds);
            if (likedMap == null) {
                likedMap = new HashMap<>();
            }
        }
        return new PageResult<>(convertToCommentVOList(pageList, likedMap), total, page, pageSize);
    }

    private static int toIntLimit(long window) {
        // 上限仅防极端 page*pageSize 溢出（MySQL LIMIT 为 int）；单主楼回复量内页间可达。
        // 超 5 万回复的超大型主楼展开需 keyset 化（review M1 回写，学习项目量级不触发）
        return (int) Math.min(Math.max(window, 1L), 50_000L);
    }

    /** 从头取回的窗口行中切片该页（越界页空列表；信封 total 已真实）。 */
    private static List<CommentCacheDTO> slicePage(List<CommentCacheDTO> rows, int page, int pageSize) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        long from = (long) (page - 1) * pageSize;
        if (from >= rows.size()) {
            return new ArrayList<>();
        }
        int to = (int) Math.min(from + pageSize, rows.size());
        return new ArrayList<>(rows.subList((int) from, to));
    }

    // ===== 增 =====

    public void addComment(CommentCommand commentCommand) {
        long userId = commentCommand.getUserId();
        long contentId = commentCommand.getContentId();
        Long parentId = commentCommand.getParentId();
        String message = commentCommand.getMessage();

        // 评论区开关门禁（作者关闭后不可发；内容不存在时 dto 为 null，交事务内 isContentExist 抛 404）
        ContentCacheDTO contentDto = contentCache.getContent(contentId);
        if (contentDto != null && !contentDto.isCommentEnabled()) {
            throw new ConflictException("评论区已关闭");
        }

        // 楼中楼：parentId 一律指向主楼。被回复评论本身是回复时，上溯挂到其主楼 id，
        // 并记录被回复评论作者（replyToUserId，用于楼中楼「回复 @xxx」展示）
        // 用数组持有归一化结果，避免在 lambda 内改写被捕获变量（编译约束）
        Long[] effectiveParentId = { parentId };
        Long[] replyToUserId = { null };
        CommentCacheDTO newComment = transactionTemplate.execute(conn -> {
            if(!contentDao.isContentExist(conn,contentId)){
                throw new NotFoundException("被评论的内容不存在");
            }
            if (effectiveParentId[0] != null && effectiveParentId[0] != 0) {
                if (!commentDao.isCommentExist(conn, effectiveParentId[0])) {
                    throw new ConflictException("被回复评论不存在或已删除");
                }
                CommentCacheDTO parent = commentDao.findCommentById(conn, effectiveParentId[0]);
                if (parent.getContentId() != contentId) {
                    throw new ConflictException("被回复评论不属于该视频或动态");
                }
                if (parent.getParentId() != null && parent.getParentId() != 0) {
                    // 被回复的是楼内回复：上溯挂主楼，@ 目标是该回复作者
                    effectiveParentId[0] = parent.getParentId();
                    replyToUserId[0] = parent.getUserId();
                }
            }
            try {
                long commentId = commentDao.addComment(conn, contentId, userId, message, effectiveParentId[0], replyToUserId[0]);
                // T10-B：新增为回复 → 主楼 reply_count +1（有效 parent 已归一为主楼 id）
                if (effectiveParentId[0] != null && effectiveParentId[0] != 0) {
                    commentDao.updateReplyCount(conn, effectiveParentId[0], 1);
                }
                contentDao.updateCommentCount(conn, contentId, 1);
                contentCache.notifyCommentCountChanged(contentId);
                return commentDao.findCommentById(conn, commentId);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "评论添加失败", e);
                throw new ServerException("评论写入失败");
            }
        });

        // 缓存更新放在事务提交后（T3 4.5 业务显式失效，读自愈回填）：
        // T10-A 失效重映射——增主楼 → 失效 roots+count（读懒建窗口）；增回复 → 定向 HDEL 所在主楼 replies field
        if (newComment != null) {
            Long effectiveParent = effectiveParentId[0];
            if (effectiveParent == null || effectiveParent == 0) {
                commentCache.invalidateRoots(contentId);
            } else {
                commentCache.invalidateReplyUnder(contentId, effectiveParent);
            }
        }
    }

    // ===== 删（软删除，均不可恢复）=====

    /** 用户自删：仅能删除自己的评论 */
    public void deleteCommentByUser(long commentId, long userId) {
        doDeleteComment(commentId, userId, false);
    }

    /** 管理员删：可删除任意评论 */
    public void deleteCommentByAdmin(long commentId) {
        doDeleteComment(commentId, 0L, true);
    }

    private void doDeleteComment(long commentId, long operatorUserId, boolean isAdmin) {
        DeletedComment deleted = transactionTemplate.execute(conn -> {
            try {
                if (!commentDao.isCommentExist(conn, commentId)) {
                    throw new NotFoundException("评论不存在");
                }
                CommentCacheDTO comment = commentDao.findCommentById(conn, commentId);
                if (!isAdmin && comment.getUserId() != operatorUserId) {
                    throw new ForbiddenException("只能删除自己的评论");
                }
                // 楼中楼删除规则：主楼整栋软删，回复只删自己
                boolean isMain = comment.getParentId() == null || comment.getParentId() == 0;
                // T10-A：删除前定位所属主楼（删主楼 = 自身；删回复 = 沿 parent 链上溯），供失效重映射
                Long rootId = isMain ? commentId : commentDao.getRootIdByCommentId(conn, commentId);
                int deletedCount;
                if (isMain) {
                    // 必须先计数再软删：countFloorReplies 过滤 is_deleted=0，若先软删会数到 0 导致计数少扣
                    deletedCount = 1 + commentDao.countFloorReplies(conn, commentId);
                    commentDao.softDeleteFloor(conn, commentId);
                } else {
                    commentDao.softDeleteOne(conn, commentId);
                    deletedCount = 1;
                }
                contentDao.updateCommentCount(conn, comment.getContentId(), -deletedCount);
                // T10-B：删回复 → 主楼 reply_count −1（防负守卫；删主楼整栋不扣——主楼已删，无意义）
                if (!isMain && rootId != null) {
                    commentDao.updateReplyCount(conn, rootId, -1);
                }
                return new DeletedComment(comment.getContentId(), commentId, deletedCount, isMain, rootId);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "评论删除失败, commentId=" + commentId, e);
                throw new ServerException("评论删除失败");
            }
        });

        // 缓存/计数同步放在事务提交后
        if (deleted != null) {
            // 失效内容 key，读自愈回填 comment_count（DB 为源真理，T2 4.5）
            contentCache.notifyCommentCountChanged(deleted.contentId);
            // T10-A 失效重映射：删主楼 → 失效 roots+count + 清该主楼 replies field；
            // 删回复 → 定向 HDEL 所在主楼 replies field（懒载刷新）
            if (deleted.isMain) {
                commentCache.invalidateRoots(deleted.contentId);
                commentCache.invalidateReplyUnder(deleted.contentId, deleted.commentId);
            } else {
                commentCache.invalidateReplyUnder(deleted.contentId, deleted.rootId);
            }
        }
    }

    private static final class DeletedComment {
        final long contentId;
        final long commentId;
        final int deletedCount;
        final boolean isMain;
        final Long rootId;

        DeletedComment(long contentId, long commentId, int deletedCount, boolean isMain, Long rootId) {
            this.contentId = contentId;
            this.commentId = commentId;
            this.deletedCount = deletedCount;
            this.isMain = isMain;
            this.rootId = rootId;
        }
    }


}
