package com.itheima.service;

import com.itheima.controller.UploadType;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.*;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.command.UploadCommand;
import com.itheima.model.dto.PageResult;
import com.itheima.model.vo.CommentVO;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ContentService {
    @Inject
    private ContentDao contentDao;
    @Inject
    private ContentMediaDao contentMediaDao;
    @Inject
    private CommentService commentService;
    @Inject
    private LikeService likeService;
    @Inject
    private ContentCacheManager contentCacheManager;
    @Inject
    private ContentStatusFiller contentStatusFiller;
    @Inject
    private TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentService.class);

    public ContentService() {}

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
        contentCacheManager.getContentFromCache(contentId);
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
}
