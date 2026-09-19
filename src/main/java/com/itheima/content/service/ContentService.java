package com.itheima.content.service;

import com.itheima.upload.controller.UploadType;
import com.itheima.comment.dao.CommentDao;
import com.itheima.comment.service.CommentService;
import com.itheima.content.dao.ContentDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.like.service.LikeService;
import com.itheima.content.dao.ContentMediaDao;
import com.itheima.exception.*;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.content.model.cache.CommentCacheDTO;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.upload.model.command.UploadCommand;
import com.itheima.content.model.dto.PageResult;
import com.itheima.content.model.entity.ContentMedia;
import com.itheima.admin.model.vo.AdminContentVO;
import com.itheima.content.model.vo.CommentVO;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ContentService {
    private final ContentDao contentDao;
    private final ContentMediaDao contentMediaDao;
    private final CommentDao commentDao;
    private final ContentLikeDao contentLikeDao;
    private final CommentService commentService;
    private final LikeService likeService;
    private final ContentCache contentCache;
    private final CommentCache commentCache;
    private final ContentStatusFiller contentStatusFiller;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentService.class);

    @InjectConstructor
    public ContentService(ContentDao contentDao, ContentMediaDao contentMediaDao,
                          CommentDao commentDao, ContentLikeDao contentLikeDao,
                          CommentService commentService, LikeService likeService,
                          ContentCache contentCache,
                          CommentCache commentCache,
                          ContentStatusFiller contentStatusFiller,
                          TransactionTemplate transactionTemplate) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.commentDao = commentDao;
        this.contentLikeDao = contentLikeDao;
        this.commentService = commentService;
        this.likeService = likeService;
        this.contentCache = contentCache;
        this.commentCache = commentCache;
        this.contentStatusFiller = contentStatusFiller;
        this.transactionTemplate = transactionTemplate;
    }

    // ===== 搜索 =====

    public PageResult<ContentVO> search(String keyword, Long userId, int page, int pageSize) {
        return transactionTemplate.execute(conn -> {
            try {
                int total = contentDao.countKeywordSearch(conn, keyword);
                List<Long> contentIdList = contentDao.keywordSearchInBrief(conn, keyword, page, pageSize);
                List<ContentVO> result = new ArrayList<>();
                for (Long contentId : contentIdList) {
                    ContentCacheDTO cacheDTO = contentCache.getContent(contentId);
                    if (cacheDTO == null) continue;
                    result.add(contentCache.toContentVO(cacheDTO));
                }

                if (userId != null && !result.isEmpty()) {
                    contentStatusFiller.fillLikeAndFollowBatch(result, userId);
                }

                return new PageResult<>(result, total, page, pageSize);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "搜索内容失败, keyword=" + keyword, e);
                throw new ServerException("搜索失败，请重试");
            }
        });
    }

    // ===== 组装响应 VO =====

    public ContentDetailVO getContentDetailVO(long contentId, Long userId) {
        ContentCacheDTO cacheDTO = contentCache.getContent(contentId);
        if (cacheDTO == null) {
            return null;
        }

        ContentDetailVO cdVO = contentCache.toDetailVO(cacheDTO);

        if (userId != null) {
            contentStatusFiller.fillContentLikeStatus(cdVO, contentId, userId);
            contentStatusFiller.fillFollowStatus(cdVO, userId);
        }

        return cdVO;
    }

    // ===== 评论查询（独立接口用）=====

    public List<CommentVO> getCommentsForContent(long contentId, Long userId) {
        ContentCacheDTO dto = contentCache.getContent(contentId);
        // 评论区开关：作者关闭后整体不可见（评论数据保留，重新开启即恢复）；
        // 同时内容不存在/隐藏/删除时也直接空（4.5 读评论前先确认 content 存在，防隐藏内容评论泄漏）
        if (dto == null || !dto.isCommentEnabled()) {
            return new ArrayList<>();
        }
        // 评论树走新 Redis 评论缓存（T3：三态 Cache-Aside + 独立 TTL + 空标记）
        List<CommentCacheDTO> commentTree = commentCache.getCommentTree(contentId);
        if (commentTree == null || commentTree.isEmpty()) {
            return new ArrayList<>();
        }

        if (userId != null) {
            List<Long> allCommentIds = commentCache.collectCommentIds(commentTree);
            Map<Long, Boolean> likedMap = likeService.batchIsCommentLiked(userId, allCommentIds);
            if (likedMap == null) likedMap = new HashMap<>();
            return commentService.convertToCommentVOList(commentTree, likedMap);
        } else {
            return commentService.convertToCommentVOList(commentTree, new HashMap<>());
        }
    }

    /**
     * 评论列表**分页**（T8 D1=A：主楼分页 + 楼中楼整树）。
     *
     * <p>仍从整树缓存取主楼的完整列表（**评论缓存装载结构不变**——本方法只在展示层切片），
     * 按主楼切片取该页，每条主楼携带其完整 {@code children}（楼中楼不切、不撕裂）；
     * DB 侧批量查询只针对该页评论 id（含该页主楼的楼中楼），不再展开全树。
     *
     * <p>缺省路径 {@link #getCommentsForContent(long, Long)} 不受影响：不传分页参数时仍返回
     * 全量数组、与改造前逐字节一致（是否传参由 Controller 显式判定）。
     *
     * @param page     页码（≥1，Controller 经 {@code BaseServletUtil.parsePage} 归一）
     * @param pageSize 每页主楼条数（1~50，经 {@code BaseServletUtil.parsePageSize} 归一）
     * @return 分页信封 {@code {list,total,page,pageSize,totalPages}}，{@code total} = **主楼条数**
     */
    public PageResult<CommentVO> getCommentsForContent(long contentId, Long userId, int page, int pageSize) {
        ContentCacheDTO dto = contentCache.getContent(contentId);
        // 评论区开关 / 内容不存在或隐藏：与缺省路径同一前置判断，分页下返回空页（total=0）
        if (dto == null || !dto.isCommentEnabled()) {
            return new PageResult<>(new ArrayList<>(), 0, page, pageSize);
        }
        List<CommentCacheDTO> commentTree = commentCache.getCommentTree(contentId);
        if (commentTree == null || commentTree.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), 0, page, pageSize);
        }

        // total 取主楼总数：与"每页 N 条主楼"同源同口径，前端判断"还有没有下一批"不依赖已加载量
        int total = commentTree.size();
        List<CommentCacheDTO> pageRoots = sliceRoots(commentTree, page, pageSize);

        // 点赞态只对该页评论批量查询（含该页主楼的楼中楼）
        Map<Long, Boolean> likedMap = new HashMap<>();
        if (userId != null) {
            List<Long> pageCommentIds = commentCache.collectCommentIds(pageRoots);
            likedMap = likeService.batchIsCommentLiked(userId, pageCommentIds);
            if (likedMap == null) likedMap = new HashMap<>();
        }
        return new PageResult<>(commentService.convertToCommentVOList(pageRoots, likedMap),
                total, page, pageSize);
    }

    /**
     * 主楼分页切片（T8 的**唯一切片点**）：越界页返回空列表，但信封仍带真实 {@code total}。
     *
     * <p>当前切片源是"整树缓存的主楼列表"；若将来改为缓存窗口读（主楼序列独立键等），
     * 只需替换本方法的取数方式，调用方与对外契约不变。
     */
    private List<CommentCacheDTO> sliceRoots(List<CommentCacheDTO> roots, int page, int pageSize) {
        // 兜底非法分页参数（Controller 已归一：page≥1、pageSize 1~50）——非正参数给空页而不是
        // 假装第 1 页，避免 subList 越界；合法入参的任何行为不受影响
        if (page < 1 || pageSize < 1) {
            return new ArrayList<>();
        }
        long offset = (long) (page - 1) * pageSize;
        if (offset >= roots.size()) {
            return new ArrayList<>();
        }
        int from = (int) offset;
        int to = (int) Math.min(offset + pageSize, roots.size());
        return new ArrayList<>(roots.subList(from, to));
    }

    // ===== 管理 =====

    public long addVideo(UploadCommand uc, String videoUrl, String coverUrl) {
        long videoId = transactionTemplate.execute(conn -> {
            try {
                long id = doAddContent(conn, uc);
                contentMediaDao.addMedia(conn, id, videoUrl, UploadType.VIDEO.getMediaType(), 1);
                contentMediaDao.addMedia(conn, id, coverUrl, UploadType.COVER.getMediaType(), 1);
                return id;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "添加视频失败, userId=" + uc.getUserId(), e);
                throw new ServerException("数据库写入失败");
            }
        });
        // 事务提交后写 Redis 内容缓存（H3 修复：缓存写入不在事务内）
        contentCache.addContent(videoId);
        return videoId;
    }

    public long addPost(UploadCommand uc, String coverUrl, List<String> imageUrls) {
        long contentId = transactionTemplate.execute(conn -> {
            try {
                long id = doAddContent(conn, uc);
                if (coverUrl != null) {
                    contentMediaDao.addMedia(conn, id, coverUrl, UploadType.COVER.getMediaType(), 1);
                }
                int sort = 1;
                for (String imageUrl : imageUrls) {
                    contentMediaDao.addMedia(conn, id, imageUrl, UploadType.IMAGE.getMediaType(), sort++);
                }
                return id;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "添加动态失败, userId=" + uc.getUserId(), e);
                throw new ServerException("数据库写入失败");
            }
        });
        // 事务提交后写 Redis 内容缓存（H3 修复：缓存写入不在事务内）
        contentCache.addContent(contentId);
        return contentId;
    }

    public long doAddContent(Connection conn, UploadCommand uc) throws SQLException {
        long userId = uc.getUserId();
        String title = uc.getTitle();
        String description = uc.getDescription();
        int categoryId = uc.getCategoryId();
        return contentDao.addContent(conn, userId, uc.getType(), title, description, categoryId);
    }

    // ===== 作者开关评论区（C2）=====

    /**
     * 作者本人开关自己作品的评论区。校验内容存在 + 所有权。
     * 关闭后：/comment/add 拒绝、/comment/show 返回空；评论数据不删，重新开启即恢复。
     */
    public void setCommentEnabled(long contentId, long userId, boolean enabled) {
        transactionTemplate.execute(conn -> {
            try {
                ContentCacheDTO dto = contentDao.findContent(conn, contentId);
                if (dto == null) {
                    throw new NotFoundException("内容不存在");
                }
                if (dto.getAuthorId() != userId) {
                    throw new ForbiddenException("只能操作自己的作品");
                }
                contentDao.updateCommentEnabled(conn, contentId, enabled);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "更新评论区开关失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
            return null;
        });
        // 缓存同步放事务提交后：失效内容 key，读自愈回填 comment_enabled
        contentCache.updateCommentEnabled(contentId);
    }

    // ===== 作者编辑作品（B1 扩展 + A3）=====

    /**
     * 作者本人替换自作品某条媒体（换源）。
     * 校验所有权 → 更新 content_media（url/file_exists/last_verify_time）→ 返回旧 url 供清理旧文件。
     * 事务提交后刷新内容缓存。
     */
    public String replaceMedia(long contentId, long userId, int type, int sort, String newUrl) {
        String oldUrl = transactionTemplate.execute(conn -> {
            try {
                ContentMedia media = findOwnedMedia(conn, contentId, userId, type, sort);
                String old = media.getUrl();
                Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
                contentMediaDao.updateMediaUrl(conn, media.getMediaId(), newUrl, true, ts);
                contentDao.updateFileExists(conn, contentId, true, ts);
                return old;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "替换媒体失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
        });
        contentCache.refreshContent(contentId);
        return oldUrl;
    }

    /**
     * 作者本人删除自作品某条媒体（单图删除）。
     * 仅允许删除图片（type==2）：视频文件与封面（含图文封面）为结构性资源只可替换不可删。
     * 删除后对图片重排 sort，保持 1..n 连续。返回旧 url 供清理物理文件。
     */
    public String deleteMedia(long contentId, long userId, int type, int sort) {
        if (type != 2) {
            throw new ParamException("仅支持删除图片");
        }
        String oldUrl = transactionTemplate.execute(conn -> {
            try {
                ContentMedia media = findOwnedMedia(conn, contentId, userId, type, sort);
                String old = media.getUrl();
                contentMediaDao.deleteMediaByContentIdAndTypeSort(conn, contentId, type, sort);
                if (type == 2) {
                    contentMediaDao.compactImageSort(conn, contentId, sort);
                }
                return old;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "删除媒体失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
        });
        contentCache.refreshContent(contentId);
        return oldUrl;
    }

    /**
     * 作者本人编辑作品标题与简介（A3）。
     * title 非空且 ≤50（对齐前端 maxlength），description ≤5000；全文索引由 MySQL 自动维护。
     */
    public void updateContentInfo(long contentId, long userId, String title, String description) {
        String t = title == null ? "" : title.trim();
        String d = description == null ? "" : description.trim();
        if (t.isEmpty()) {
            throw new ParamException("标题不能为空");
        }
        if (t.length() > 50) {
            throw new ParamException("标题不超过50字");
        }
        if (d.length() > 5000) {
            throw new ParamException("简介不超过5000字");
        }
        transactionTemplate.execute(conn -> {
            try {
                findOwnedContent(conn, contentId, userId);
                contentDao.updateContentInfo(conn, contentId, t, d);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "编辑作品信息失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
            return null;
        });
        contentCache.refreshContent(contentId);
    }

    // ===== 作者删除作品（A1）=====

    /**
     * 作者本人删除自作品（软删除，不可恢复）。
     * 校验所有权 → 软删内容 → 级联软删全部评论 → 物理删点赞记录 → 物理删媒体记录。
     * 返回该内容全部媒体 url 供 Controller 清理物理文件；事务提交后整体剔除缓存。
     */
    public List<String> deleteContent(long contentId, long userId) {
        List<String> mediaUrls = transactionTemplate.execute(conn -> {
            try {
                findOwnedContent(conn, contentId, userId);
                List<String> urls = new ArrayList<>();
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentId);
                for (List<ContentMedia> list : mediaMap.values()) {
                    for (ContentMedia m : list) {
                        urls.add(m.getUrl());
                    }
                }
                contentDao.softDeleteContent(conn, contentId);
                commentDao.softDeleteByContentId(conn, contentId);
                contentLikeDao.deleteByContentId(conn, contentId);
                contentMediaDao.deleteByContentId(conn, contentId);
                return urls;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "删除内容失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
        });
        // 缓存同步放事务提交后：
        // 新内容缓存：失效内容 key + 索引剔除（读自愈 404）
        contentCache.removeContent(contentId);
        // 新评论缓存：级联失效评论树 key（4.5 内容删除 → 显式删 content:comments:{id} + 空标记）
        commentCache.invalidateComments(contentId);
        // 点赞缓存：失效计数/成员/空标记（T4 旧 ContentCacheManager.evictContent 副作用，T6 迁入）
        likeService.deleteContentLike(contentId);
        return mediaUrls;
    }

    /** 所有权校验（内容存在 + 作者本人），供编辑作品三类操作复用。 */
    private ContentCacheDTO findOwnedContent(Connection conn, long contentId, long userId) throws SQLException {
        ContentCacheDTO dto = contentDao.findContent(conn, contentId);
        if (dto == null) {
            throw new NotFoundException("内容不存在");
        }
        if (dto.getAuthorId() != userId) {
            throw new ForbiddenException("只能操作自己的作品");
        }
        return dto;
    }

    // ===== 管理员下架/恢复内容（A2，审核）=====

    /**
     * 管理端内容清单：含正常与已下架，不含已删除(1)的内容。
     * 权限（role==1）由 AuthFilter 在 /api/admin/* 统一校验。
     */
    public List<AdminContentVO> listContentForAdmin() {
        return transactionTemplate.execute(conn -> {
            try {
                return contentDao.findContentForAdmin(conn);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "查询管理端内容清单失败", e);
                throw new ServerException("数据库读取失败");
            }
        });
    }

    /**
     * 管理员下架内容（审核）：is_deleted 0→2。
     * 仅改状态，不动评论/点赞/媒体记录与物理文件；提交后剔除缓存，前台即时不可见。
     */
    public void hideContent(long contentId) {
        transactionTemplate.execute(conn -> {
            try {
                checkHideable(conn, contentId);
                contentDao.updateContentDeletedState(conn, contentId, 2);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "下架内容失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
            return null;
        });
        // 新内容缓存：失效内容 key + 索引剔除
        contentCache.removeContent(contentId);
        // 新评论缓存：下架时失效内容评论树 key（读自愈；4.5 业务显式失效）
        commentCache.invalidateComments(contentId);
        // 点赞缓存：失效计数/成员/空标记（T6 迁入，同删除路径）
        likeService.deleteContentLike(contentId);
    }

    /**
     * 管理员恢复内容：is_deleted 2→0。
     * 提交后回填缓存与索引，前台立即重新可见。
     */
    public void unhideContent(long contentId) {
        transactionTemplate.execute(conn -> {
            try {
                checkUnhideable(conn, contentId);
                contentDao.updateContentDeletedState(conn, contentId, 0);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "恢复内容失败, contentId=" + contentId, e);
                throw new ServerException("数据库写入失败");
            }
            return null;
        });
        // 新内容缓存：重载内容 + 索引（旧内存评论树重建副作用已随旧管理器 T6 移除）
        contentCache.refreshContent(contentId);
    }

    /** 下架前置校验：存在且未被删除、未处于下架态 */
    private void checkHideable(Connection conn, long contentId) throws SQLException {
        int state = contentDao.getContentStatus(conn, contentId);
        if (state == -1) {
            throw new NotFoundException("内容不存在");
        }
        if (state == 1) {
            throw new ConflictException("内容已删除，无法下架");
        }
        if (state == 2) {
            throw new ConflictException("内容已下架");
        }
    }

    /** 恢复前置校验：存在且未被删除、当前处于下架态 */
    private void checkUnhideable(Connection conn, long contentId) throws SQLException {
        int state = contentDao.getContentStatus(conn, contentId);
        if (state == -1) {
            throw new NotFoundException("内容不存在");
        }
        if (state == 1) {
            throw new ConflictException("内容已删除，无法恢复");
        }
        if (state == 0) {
            throw new ConflictException("内容未下架");
        }
    }

    /** 所有权校验 + 定位媒体行，供替换/删除复用。 */
    private ContentMedia findOwnedMedia(Connection conn, long contentId, long userId, int type, int sort) throws SQLException {
        findOwnedContent(conn, contentId, userId);
        ContentMedia media = contentMediaDao.findMediaByContentTypeSort(conn, contentId, type, sort);
        if (media == null) {
            throw new NotFoundException("媒体资源不存在");
        }
        return media;
    }
}
