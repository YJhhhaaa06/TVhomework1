package com.itheima.service;

import com.itheima.controller.UploadType;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentLikeDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.*;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.command.UploadCommand;
import com.itheima.model.dto.PageResult;
import com.itheima.model.entity.ContentMedia;
import com.itheima.model.vo.AdminContentVO;
import com.itheima.model.vo.CommentVO;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
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
    private final ContentCacheManager contentCacheManager;
    private final ContentStatusFiller contentStatusFiller;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentService.class);

    @InjectConstructor
    public ContentService(ContentDao contentDao, ContentMediaDao contentMediaDao,
                          CommentDao commentDao, ContentLikeDao contentLikeDao,
                          CommentService commentService, LikeService likeService,
                          ContentCacheManager contentCacheManager,
                          ContentStatusFiller contentStatusFiller,
                          TransactionTemplate transactionTemplate) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.commentDao = commentDao;
        this.contentLikeDao = contentLikeDao;
        this.commentService = commentService;
        this.likeService = likeService;
        this.contentCacheManager = contentCacheManager;
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
                    ContentCacheDTO cacheDTO = contentCacheManager.getContentFromCache(contentId);
                    if (cacheDTO == null) continue;
                    result.add(contentCacheManager.toContentVO(cacheDTO));
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
        ContentCacheDTO cacheDTO = contentCacheManager.getContentFromCache(contentId);
        if (cacheDTO == null) {
            return null;
        }

        ContentDetailVO cdVO = contentCacheManager.toDetailVO(cacheDTO);

        if (userId != null) {
            contentStatusFiller.fillContentLikeStatus(cdVO, contentId, userId);
            contentStatusFiller.fillFollowStatus(cdVO, userId);
        }

        return cdVO;
    }

    // ===== 评论查询（独立接口用）=====

    public List<CommentVO> getCommentsForContent(long contentId, Long userId) {
        ContentCacheDTO dto = contentCacheManager.getContentFromCache(contentId);
        // 评论区开关：作者关闭后整体不可见（评论数据保留，重新开启即恢复）
        if (dto != null && !dto.isCommentEnabled()) {
            return new ArrayList<>();
        }
        List<CommentCacheDTO> commentTree = contentCacheManager.getCommentTree(contentId);
        if (commentTree.isEmpty()) {
            return new ArrayList<>();
        }

        if (userId != null) {
            List<Long> allCommentIds = contentCacheManager.collectCommentIds(commentTree);
            Map<Long, Boolean> likedMap = likeService.batchIsCommentLiked(userId, allCommentIds);
            if (likedMap == null) likedMap = new HashMap<>();
            return commentService.convertToCommentVOList(commentTree, likedMap);
        } else {
            return commentService.convertToCommentVOList(commentTree, new HashMap<>());
        }
    }

    // ===== 管理 =====

    public long addVideo(UploadCommand uc, String videoUrl, String coverUrl) {
        return transactionTemplate.execute(conn -> {
            try {
                long videoId = doAddContent(conn, uc);
                contentMediaDao.addMedia(conn, videoId, videoUrl, UploadType.VIDEO.getMediaType(), 1);
                contentMediaDao.addMedia(conn, videoId, coverUrl, UploadType.COVER.getMediaType(), 1);
                contentCacheManager.updateCacheAfterAdd(conn, videoId);
                return videoId;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "添加视频失败, userId=" + uc.getUserId(), e);
                throw new ServerException("数据库写入失败");
            }
        });
    }

    public long addPost(UploadCommand uc, String coverUrl, List<String> imageUrls) {
        return transactionTemplate.execute(conn -> {
            try {
                long contentId = doAddContent(conn, uc);
                if (coverUrl != null) {
                    contentMediaDao.addMedia(conn, contentId, coverUrl, UploadType.COVER.getMediaType(), 1);
                }
                int sort = 1;
                for (String imageUrl : imageUrls) {
                    contentMediaDao.addMedia(conn, contentId, imageUrl, UploadType.IMAGE.getMediaType(), sort++);
                }
                contentCacheManager.updateCacheAfterAdd(conn, contentId);
                return contentId;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "添加动态失败, userId=" + uc.getUserId(), e);
                throw new ServerException("数据库写入失败");
            }
        });
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
        // 缓存同步放事务提交后
        contentCacheManager.updateContentCommentEnabled(contentId, enabled);
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
        contentCacheManager.refreshContent(contentId);
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
        contentCacheManager.refreshContent(contentId);
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
        contentCacheManager.refreshContent(contentId);
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
        // 缓存同步放事务提交后
        contentCacheManager.removeContent(contentId);
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
        contentCacheManager.removeContent(contentId);
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
        contentCacheManager.refreshContent(contentId);
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
