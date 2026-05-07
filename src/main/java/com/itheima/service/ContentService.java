package com.itheima.service;

import com.itheima.command.UploadCommand;
import com.itheima.controller.UploadType;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.dao.FollowDao;
import com.itheima.exception.*;
import com.itheima.pojo.*;
import com.itheima.util.LogUtil;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ContentService {
    private ContentDao contentDao = new ContentDao();
    private CommentDao commentDao = new CommentDao();
    private ContentMediaDao contentMediaDao = new ContentMediaDao();
    private FollowDao followDao = new FollowDao();
    private CommentService commentService = new CommentService();
    private LikeService likeService = new LikeService();
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentService.class);

    private static List<RecommendVO> recommendList = new ArrayList<>();
    private static Map<Long, ContentDetailVO> contentCache = new HashMap<>();
    private static Map<Long, List<CommentCacheDTO>> commentCache = new HashMap<>();
    private static Random r = new Random();

    private static ContentService instance = new ContentService();

    public static ContentService getInstance() {
        return instance;
    }

    private ContentService() {
        init();
    }

    // ===== 初始化 =====
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "内容缓存初始化失败", e);
            throw e;
        }
        startScheduler();
    }

    public void refresh() {
        try {
            List<RecommendVO> newRecommendList = contentDao.findAllContent();

            // 构建 content 缓存（不含评论）
            Map<Long, ContentDetailVO> newContentCache = getContentCache();

            // 把封面 URL 填到 recommendList
            for (RecommendVO rvo : newRecommendList) {
                ContentDetailVO detail = newContentCache.get(rvo.getId());
                if (detail != null && detail.getCoverUrl() != null) {
                    rvo.setCoverUrl(detail.getCoverUrl());
                }
            }

            // 构建 comment 缓存（独立，按 contentId 分组）
            Map<Long, List<CommentCacheDTO>> newCommentCache = getCommentCache(newContentCache.keySet());

            recommendList = newRecommendList;
            contentCache = newContentCache;
            commentCache = newCommentCache;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "内容缓存刷新失败", e);
            throw new RuntimeException("FAIL_TO_REFRESH", e);
        }
    }

    public void startScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refresh();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "缓存定时刷新异常", e);
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    // ===== 构建缓存 =====

    private Map<Long, ContentDetailVO> getContentCache() {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            List<ContentDetailVO> list = contentDao.findAllContentDetail(conn);
            for (ContentDetailVO cdVO : list) {
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, cdVO.getId());
                buildContentMedia(cdVO, mediaMap);
            }
            Map<Long, ContentDetailVO> map = new HashMap<>();
            for (ContentDetailVO cdVO : list) {
                map.put(cdVO.getId(), cdVO);
            }
            return map;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "查询内容缓存失败", e);
            throw new ServerException("缓存失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private Map<Long, List<CommentCacheDTO>> getCommentCache(Set<Long> contentIds) {
        Map<Long, List<CommentCacheDTO>> map = new HashMap<>();
        for (Long contentId : contentIds) {
            try {
                List<CommentCacheDTO> tree = commentService.getCommentCacheVOList(contentId);
                map.put(contentId, tree);
            } catch (Exception e) {
                LOGGER.warning("获取评论缓存失败，返回空列表, contentId=" + contentId);
                map.put(contentId, new ArrayList<>());
            }
        }
        return map;
    }

    // ===== 查询（缓存）=====

    public List<RecommendVO> getContentList() {
        return recommendList;
    }

    public ContentDetailVO getRecommendedContent(long contentId) {
        return contentCache.get(contentId);
    }

    public List<RecommendVO> getRecommend(int limit) {
        List<RecommendVO> list = new ArrayList<>(recommendList);
        Collections.shuffle(list);
        int size = Math.min(limit, list.size());
        return list.subList(0, size);
    }

    // ===== 查询（DB，不走缓存）=====

    public ContentDetailVO getContentById(long contentId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            ContentDetailVO cdVO = contentDao.findContent(conn, contentId);
            if (cdVO == null) {
                return null;
            }
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, cdVO.getId());
            buildContentMedia(cdVO, mediaMap);
            return cdVO;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "查询内容失败, contentId=" + contentId, e);
            throw new ConflictException(e.getMessage());
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<ContentDetailVO> search(String keyword) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            List<ContentDetailVO> list = contentDao.search(conn, keyword, 1, 20);
            for (ContentDetailVO cdVO : list) {
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, cdVO.getId());
                buildContentMedia(cdVO, mediaMap);
            }
            return list;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "搜索内容失败, keyword=" + keyword, e);
            throw new ServerException("搜索失败，请重试");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 组装响应 VO =====

    public ContentVO getContentVO(long contentId, long userId) {
        ContentDetailVO cdVO = contentCache.get(contentId);
        if (cdVO == null) {
            return null;
        }

        ContentVO cVO = new ContentVO();
        copyFromCache(cVO, cdVO);

        // 获取评论树
        List<CommentCacheDTO> commentTree = commentCache.getOrDefault(contentId, new ArrayList<>());

        // 收集所有需要查点赞的 ID
        List<Long> allCommentIds = collectCommentIds(commentTree);
        List<Long> allContentIds = List.of(contentId);

        // 批量查询点赞状态（Redis pipeline 优先，miss 的 ID 走 DB 兜底）
        Map<Long, Boolean> likedMap = new HashMap<>();
        // 查内容的点赞状态
        Map<Long, Boolean> contentLikedMap = likeService.batchIsContentLiked(userId, allContentIds);
        if (contentLikedMap != null) {
            likedMap.putAll(contentLikedMap);
        }
        // 查所有评论的点赞状态
        if (!allCommentIds.isEmpty()) {
            Map<Long, Boolean> commentLikedMap = likeService.batchIsCommentLiked(userId, allCommentIds);
            if (commentLikedMap != null) {
                likedMap.putAll(commentLikedMap);
            }
        }


        // 填充 isLiked
        Boolean contentLiked = likedMap.get(contentId);
        cVO.setIsLiked(contentLiked != null && contentLiked);

        // 填充 isFollow
        fillFollowStatus(cVO, userId);

        return cVO;
    }

    private void copyFromCache(ContentVO cVO, ContentDetailVO cdVO) {
        cVO.setId(cdVO.getId());
        cVO.setAuthorId(cdVO.getAuthorId());
        cVO.setType(cdVO.getType());
        cVO.setTitle(cdVO.getTitle());
        cVO.setDescription(cdVO.getDescription());
        cVO.setCategoryId(cdVO.getCategoryId());
        cVO.setCommentCount(cdVO.getCommentCount());
        cVO.setLikeCount(cdVO.getLikeCount());
        cVO.setAuthorName(cdVO.getAuthorName());
        cVO.setCoverUrl(cdVO.getCoverUrl());
    }

    private List<Long> collectCommentIds(List<CommentCacheDTO> tree) {
        List<Long> ids = new ArrayList<>();
        for (CommentCacheDTO ccVO : tree) {
            collectIdsRecursive(ccVO, ids);
        }
        return ids;
    }

    private void collectIdsRecursive(CommentCacheDTO ccVO, List<Long> ids) {
        ids.add(ccVO.getCommentId());
        if (ccVO.getChildren() != null) {
            for (CommentCacheDTO child : ccVO.getChildren()) {
                collectIdsRecursive(child, ids);
            }
        }
    }

    // ===== 关注状态填充 =====

    public void fillFollowStatus(List<RecommendVO> list, Long userId) {
        if (userId == null || list == null || list.isEmpty()) return;

        List<Long> authorIds = new ArrayList<>();
        for (RecommendVO vo : list) {
            if (vo.getAuthorId() > 0) {
                authorIds.add(vo.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) return;

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> followedSet = followDao.getFollowedIds(conn, userId, authorIds);
            for (RecommendVO vo : list) {
                vo.setIsFollow(followedSet.contains(vo.getAuthorId()));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "批量填充关注状态失败, userId=" + userId, e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private void fillFollowStatus(ContentVO vo, Long userId) {
        if (userId == null || vo == null) return;

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> followedSet = followDao.getFollowedIds(conn, userId, List.of(vo.getAuthorId()));
            vo.setIsFollow(followedSet.contains(vo.getAuthorId()));
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "填充关注状态失败, userId=" + userId, e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 媒体填充 =====

    public void buildContentMedia(ContentDetailVO cdVO, Map<Integer, List<ContentMedia>> mediaMap) {
        List<ContentMedia> coverList = mediaMap.get(3);
        if (coverList != null && !coverList.isEmpty()) {
            cdVO.setCoverUrl(jointUrl(coverList.getFirst().getUrl()));
        }
        int type = cdVO.getType();
        switch (type) {
            case 1:
                List<ContentMedia> videoList = mediaMap.get(1);
                if (videoList != null && !videoList.isEmpty()) {
                    cdVO.setVideoUrl(jointUrl(videoList.getFirst().getUrl()));
                } else {
                    throw new NotFoundException("资源已丢失");
                }
                break;
            case 2:
                List<ContentMedia> imageList = mediaMap.get(2);
                if (imageList != null && !imageList.isEmpty()) {
                    List<String> imageUrls = new ArrayList<>();
                    for (ContentMedia media : imageList) {
                        imageUrls.add(media.getUrl());
                    }
                    cdVO.setImageUrls(imageUrls);
                }
                break;
            default:
                throw new ServerException("未知内容类型: " + type);
        }
    }

    // ===== 管理 =====

    public void addVideo(UploadCommand uc, String videoUrl, String coverUrl) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            long videoId = doAddContent(conn, uc);
            contentMediaDao.addMedia(conn, videoId, videoUrl, UploadType.VIDEO.getMediaType(), 1);
            contentMediaDao.addMedia(conn, videoId, coverUrl, UploadType.COVER.getMediaType(), 1);
            conn.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "添加视频失败, userId=" + uc.getUserId(), e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("数据库写入失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public void addPost(UploadCommand uc, String coverUrl, List<String> imageUrls) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            long contentId = doAddContent(conn, uc);
            if (coverUrl != null) {
                contentMediaDao.addMedia(conn, contentId, coverUrl, UploadType.COVER.getMediaType(), 1);
            }
            int sort = 1;
            for (String imageUrl : imageUrls) {
                contentMediaDao.addMedia(conn, contentId, imageUrl, UploadType.IMAGE.getMediaType(), sort++);
            }
            conn.commit();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "添加动态失败, userId=" + uc.getUserId(), e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("数据库写入失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public long doAddContent(Connection conn, UploadCommand uc) throws SQLException {
        long userId = uc.getUserId();
        String title = uc.getTitle();
        String description = uc.getDescription();
        int categoryId = uc.getCategoryId();
        return contentDao.addContent(conn, userId, uc.getType(), title, description, categoryId);
    }

    public void deleteContent(long contentId, long userId) {
    }

    // ===== 内存缓存实时同步 =====

    /**
     * 点赞/取消点赞后实时更新内存中 content 的 likeCount
     * 同时更新 contentCache 和 recommendList，避免等待 1 分钟定时刷新
     */
    public static void updateContentLikeCount(long contentId, int delta) {
        // 更新 contentCache
        ContentDetailVO cdvo = contentCache.get(contentId);
        if (cdvo != null) {
            cdvo.setLikeCount(cdvo.getLikeCount() + delta);
        }
        // 同步更新 recommendList
        synchronized (recommendList) {
            for (RecommendVO rvo : recommendList) {
                if (rvo.getId() == contentId) {
                    rvo.setLikeCount(rvo.getLikeCount() + delta);
                    break;
                }
            }
        }
    }

    /**
     * 点赞/取消点赞后实时更新内存中 comment 的 likeCount
     * 递归遍历 commentCache 树找到对应评论并更新
     */
    public static void updateCommentLikeCount(long commentId, int delta) {
        synchronized (commentCache) {
            for (List<CommentCacheDTO> tree : commentCache.values()) {
                if (updateCommentLikeCountInTree(tree, commentId, delta)) {
                    return;
                }
            }
        }
    }

    private static boolean updateCommentLikeCountInTree(List<CommentCacheDTO> tree, long commentId, int delta) {
        for (CommentCacheDTO ccvo : tree) {
            if (ccvo.getCommentId() == commentId) {
                ccvo.setLikeCount(ccvo.getLikeCount() + delta);
                return true;
            }
            if (ccvo.getChildren() != null && !ccvo.getChildren().isEmpty()) {
                if (updateCommentLikeCountInTree(ccvo.getChildren(), commentId, delta)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String jointUrl(String url) {
        return "http://localhost:8080/MyAPP" + url;
    }
}