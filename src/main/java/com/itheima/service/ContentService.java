package com.itheima.service;

import com.itheima.DTO.PageResult;
import com.itheima.command.UploadCommand;
import com.itheima.controller.UploadType;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.dao.FollowDao;
import com.itheima.exception.*;
import com.itheima.factory.BeanFactory;
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
    private ContentDao contentDao = BeanFactory.getContentDao();
    private CommentDao commentDao = BeanFactory.getCommentDao();
    private ContentMediaDao contentMediaDao = BeanFactory.getContentMediaDao();
    private FollowDao followDao = BeanFactory.getFollowDao();
    private CommentService commentService = BeanFactory.getCommentService();
    private LikeService likeService = BeanFactory.getLikeService();
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentService.class);

    private static List<ContentVO> recommendList = new ArrayList<>();
    private static Map<Long, ContentCacheDTO> contentCache = new HashMap<>();
    private static Map<Long, List<CommentCacheDTO>> commentCache = new HashMap<>();
    private static Map<Long, Long> contentTimestamps = new HashMap<>();
    private static final long CONTENT_TTL_MS = 10 * 60 * 1000;
    private static Map<String, List<Long>> typeCategoryIndex = new HashMap<>();
    private ScheduledExecutorService scheduler;

    public ContentService() {
        init();
    }

    // ===== 缓存 TTL 管理 =====

    private boolean isContentExpired(long contentId) {
        Long ts = contentTimestamps.get(contentId);
        return ts == null || System.currentTimeMillis() - ts > CONTENT_TTL_MS;
    }

    private void evictContent(long contentId) {
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto != null) {
            removeFromIndex(contentId, dto.getType(), dto.getCategoryId());
        }
        contentCache.remove(contentId);
        commentCache.remove(contentId);
        contentTimestamps.remove(contentId);
    }

    private void cacheContent(long contentId, ContentCacheDTO detail, List<CommentCacheDTO> comments) {
        contentCache.put(contentId, detail);
        commentCache.put(contentId, comments);
        contentTimestamps.put(contentId, System.currentTimeMillis());
    }

    private void cacheContentBasic(long contentId, ContentCacheDTO detail) {
        contentCache.put(contentId, detail);
        contentTimestamps.put(contentId, System.currentTimeMillis());
    }

    // ===== 类型/分区索引 =====

    private static String indexKey(int type, int categoryId) {
        return type + ":" + categoryId;
    }

    private void addToIndex(long contentId, int type, int categoryId) {
        String[] keys = {
            indexKey(type, categoryId),
            indexKey(type, -1),
            indexKey(-1, categoryId),
            indexKey(-1, -1)
        };
        for (String key : keys) {
            List<Long> list = typeCategoryIndex.computeIfAbsent(key, k -> new ArrayList<>());
            list.remove(contentId);
            list.add(0, contentId);
        }
    }

    private static void addToIndexInternal(Map<String, List<Long>> index, long contentId, int type, int categoryId) {
        String[] keys = {
            indexKey(type, categoryId),
            indexKey(type, -1),
            indexKey(-1, categoryId),
            indexKey(-1, -1)
        };
        for (String key : keys) {
            List<Long> list = index.computeIfAbsent(key, k -> new ArrayList<>());
            list.remove(contentId);
            list.add(contentId);
        }
    }

    private void removeFromIndex(long contentId, int type, int categoryId) {
        String[] keys = {
            indexKey(type, categoryId),
            indexKey(type, -1),
            indexKey(-1, categoryId),
            indexKey(-1, -1)
        };
        for (String key : keys) {
            List<Long> list = typeCategoryIndex.get(key);
            if (list != null) {
                list.remove(contentId);
            }
        }
    }

    private String buildQueryKey(Integer type, Integer categoryId) {
        if (type != null && type != 0 && (type < 1 || type > 2)) {
            throw new ParamException("不支持的内容类型: " + type);
        }
        if (categoryId != null && (categoryId < 0 || categoryId > 9)) {
            throw new ParamException("不支持的分区: " + categoryId);
        }
        int t = (type != null && type != 0) ? type : -1;
        int c = (categoryId != null) ? categoryId : -1;
        return indexKey(t, c);
    }

    public List<ContentVO> getRecommendByFilter(Integer type, Integer categoryId, int limit) {
        String key = buildQueryKey(type, categoryId);
        List<Long> idList = typeCategoryIndex.get(key);
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(idList));
        Collections.shuffle(distinctIds);
        List<ContentVO> result = new ArrayList<>();
        for (Long contentId : distinctIds) {
            ContentCacheDTO dto = getContentFromCache(contentId);
            if (dto == null) continue;
            ContentVO cVO = new ContentVO();
            copyToContentVO(cVO, dto);
            result.add(cVO);
            if (result.size() >= limit) break;
        }
        return result;
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
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            List<ContentCacheDTO> allContent = contentDao.findAllContent(conn);

            Map<Long, ContentCacheDTO> newContentCache = new HashMap<>();
            for (ContentCacheDTO dto : allContent) {
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, dto.getId());
                buildContentMedia(dto, mediaMap);
                newContentCache.put(dto.getId(), dto);
            }

            List<ContentVO> newRecommendList = new ArrayList<>();
            for (ContentCacheDTO dto : allContent) {
                ContentVO cVO = new ContentVO();
                copyToContentVO(cVO, dto);
                newRecommendList.add(cVO);
            }

            Map<Long, List<CommentCacheDTO>> newCommentCache = getCommentCache(newContentCache.keySet());

            Map<String, List<Long>> newIndex = new HashMap<>();
            for (ContentCacheDTO dto : allContent) {
                addToIndexInternal(newIndex, dto.getId(), dto.getType(), dto.getCategoryId());
            }

            recommendList = newRecommendList;
            contentCache = newContentCache;
            commentCache = newCommentCache;
            typeCategoryIndex = newIndex;
            contentTimestamps.clear();
            long now = System.currentTimeMillis();
            for (Long id : newContentCache.keySet()) {
                contentTimestamps.put(id, now);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "内容缓存刷新失败", e);

            throw new RuntimeException("FAIL_TO_REFRESH", e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public void startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refresh();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "缓存定时刷新异常", e);
            }
        }, 0, 10, TimeUnit.MINUTES);
    }

    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    // ===== 构建缓存 =====

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

    public List<ContentVO> getContentList() {
        return recommendList;
    }

    public List<ContentVO> getRecommend(int limit) {
        List<ContentVO> list = new ArrayList<>(recommendList);
        Collections.shuffle(list);
        int size = Math.min(limit, list.size());
        return list.subList(0, size);
    }

    public ContentCacheDTO getContentFromCache(long contentId) {
        if (isContentExpired(contentId)) {
            evictContent(contentId);
        }
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto == null) {
            backfillContent(contentId);
            dto = contentCache.get(contentId);
        }
        return dto;
    }

    // ===== 查询（DB，不走缓存）=====

    public ContentCacheDTO getContentById(long contentId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            ContentCacheDTO dto = contentDao.findContent(conn, contentId);
            if (dto == null) {
                return null;
            }
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, dto.getId());
            buildContentMedia(dto, mediaMap);
            return dto;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "查询内容失败, contentId=" + contentId, e);
            throw new ConflictException(e.getMessage());
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 搜索 =====

    public PageResult<ContentVO> search(String keyword, Long userId, int page, int pageSize) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            int total = contentDao.countKeywordSearch(conn, keyword);
            List<Long> contentIdList = contentDao.keywordSearchInBrief(conn, keyword, page, pageSize);
            List<ContentVO> result = new ArrayList<>();
            for (Long contentId : contentIdList) {
                ContentCacheDTO cacheDTO = getContentFromCache(contentId);
                if (cacheDTO == null) continue;
                ContentVO cVO = new ContentVO();
                copyToContentVO(cVO, cacheDTO);
                result.add(cVO);
            }

            if (userId != null && !result.isEmpty()) {
                fillLikeAndFollowBatch(result, userId);
            }

            return new PageResult<>(result, total, page, pageSize);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "搜索内容失败, keyword=" + keyword, e);
            throw new ServerException("搜索失败，请重试");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 组装响应 VO =====

    public ContentVO getContentVO(long contentId, Long userId) {
        ContentCacheDTO cacheDTO = getContentFromCache(contentId);
        if (cacheDTO == null) {
            return null;
        }

        ContentVO cVO = new ContentVO();
        copyToContentVO(cVO, cacheDTO);

        if (userId != null) {
            fillContentLikeStatus(cVO, contentId, userId);
            fillFollowStatus(cVO, userId);
        }

        return cVO;
    }

    public ContentDetailVO getContentDetailVO(long contentId, Long userId) {
        ContentCacheDTO cacheDTO = getContentFromCache(contentId);
        if (cacheDTO == null) {
            return null;
        }

        ContentDetailVO cdVO = new ContentDetailVO();
        copyToDetailVO(cdVO, cacheDTO);

        if (userId != null) {
            fillContentLikeStatus(cdVO, contentId, userId);
            fillFollowStatus(cdVO, userId);
        }

        return cdVO;
    }

    // ===== 评论查询（独立接口用）=====

    public List<CommentVO> getCommentsForContent(long contentId, Long userId) {
        getContentFromCache(contentId);
        List<CommentCacheDTO> commentTree = commentCache.getOrDefault(contentId, new ArrayList<>());
        if (commentTree.isEmpty()) {
            return new ArrayList<>();
        }

        if (userId != null) {
            List<Long> allCommentIds = collectCommentIds(commentTree);
            Map<Long, Boolean> likedMap = likeService.batchIsCommentLiked(userId, allCommentIds);
            if (likedMap == null) likedMap = new HashMap<>();
            return commentService.convertToCommentVOList(commentTree, likedMap);
        } else {
            return commentService.convertToCommentVOList(commentTree, new HashMap<>());
        }
    }

    // ===== 缓存回填 =====

    private void backfillContent(long contentId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            ContentCacheDTO dto = contentDao.findContent(conn, contentId);
            if (dto == null) return;
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, dto.getId());
            buildContentMedia(dto, mediaMap);
            List<CommentCacheDTO> comments = commentService.getCommentCacheVOList(contentId);
            cacheContent(contentId, dto, comments);
            addToIndex(contentId, dto.getType(), dto.getCategoryId());
            LOGGER.info("缓存回填成功, contentId=" + contentId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "缓存回填失败, contentId=" + contentId, e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 复制方法 =====

    private void copyToContentVO(ContentVO cVO, ContentCacheDTO dto) {
        cVO.setId(dto.getId());
        cVO.setAuthorId(dto.getAuthorId());
        cVO.setType(dto.getType());
        cVO.setTitle(dto.getTitle());
        cVO.setDescription(dto.getDescription());
        cVO.setCategoryId(dto.getCategoryId());
        cVO.setCommentCount(dto.getCommentCount());
        cVO.setLikeCount(dto.getLikeCount());
        cVO.setAuthorName(dto.getAuthorName());
        cVO.setCoverUrl(dto.getCoverUrl());
        cVO.setCreateTime(dto.getCreateTime());
    }

    private void copyToDetailVO(ContentDetailVO cdVO, ContentCacheDTO dto) {
        cdVO.setId(dto.getId());
        cdVO.setAuthorId(dto.getAuthorId());
        cdVO.setType(dto.getType());
        cdVO.setTitle(dto.getTitle());
        cdVO.setDescription(dto.getDescription());
        cdVO.setCategoryId(dto.getCategoryId());
        cdVO.setCommentCount(dto.getCommentCount());
        cdVO.setLikeCount(dto.getLikeCount());
        cdVO.setAuthorName(dto.getAuthorName());
        cdVO.setCoverUrl(dto.getCoverUrl());
        cdVO.setVideoUrl(dto.getVideoUrl());
        cdVO.setImageUrls(dto.getImageUrls());
        cdVO.setCreateTime(dto.getCreateTime());
    }

    // ===== 点赞状态填充 =====

    private void fillContentLikeStatus(ContentVO cVO, long contentId, long userId) {
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, List.of(contentId));
        if (likedMap != null) {
            Boolean liked = likedMap.get(contentId);
            cVO.setIsLiked(liked != null && liked);
        }
    }

    private void fillContentLikeStatus(ContentDetailVO cdVO, long contentId, long userId) {
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, List.of(contentId));
        if (likedMap != null) {
            Boolean liked = likedMap.get(contentId);
            cdVO.setIsLiked(liked != null && liked);
        }
    }

    public void fillLikeAndFollowBatch(List<ContentVO> list, Long userId) {
        if (userId == null || list.isEmpty()) return;

        // 批量查点赞
        List<Long> contentIds = new ArrayList<>();
        for (ContentVO vo : list) {
            contentIds.add(vo.getId());
        }
        Map<Long, Boolean> likedMap = likeService.batchIsContentLiked(userId, contentIds);
        if (likedMap == null) likedMap = new HashMap<>();
        for (ContentVO vo : list) {
            Boolean liked = likedMap.get(vo.getId());
            vo.setIsLiked(liked != null && liked);
        }

        // 批量查关注
        fillFollowStatus(list, userId);
    }

    // ===== 收集评论 ID（递归）=====

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

    public void fillFollowStatus(List<ContentVO> list, Long userId) {
        if (userId == null || list == null || list.isEmpty()) return;

        List<Long> authorIds = new ArrayList<>();
        for (ContentVO vo : list) {
            if (vo.getAuthorId() > 0) {
                authorIds.add(vo.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) return;

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> followedSet = followDao.getFollowedIds(conn, userId, authorIds);
            for (ContentVO vo : list) {
                vo.setIsFollowed(followedSet.contains(vo.getAuthorId()));
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
            vo.setIsFollowed(followedSet.contains(vo.getAuthorId()));
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "填充关注状态失败, userId=" + userId, e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    private void fillFollowStatus(ContentDetailVO vo, Long userId) {
        if (userId == null || vo == null) return;

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> followedSet = followDao.getFollowedIds(conn, userId, List.of(vo.getAuthorId()));
            vo.setIsFollowed(followedSet.contains(vo.getAuthorId()));
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "填充关注状态失败, userId=" + userId, e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 媒体填充 =====

    public void buildContentMedia(ContentCacheDTO dto, Map<Integer, List<ContentMedia>> mediaMap) {
        List<ContentMedia> coverList = mediaMap.get(3);
        if (coverList != null && !coverList.isEmpty()) {
            dto.setCoverUrl(jointUrl(coverList.getFirst().getUrl()));
        }
        int type = dto.getType();
        switch (type) {
            case 1:
                List<ContentMedia> videoList = mediaMap.get(1);
                if (videoList != null && !videoList.isEmpty()) {
                    dto.setVideoUrl(jointUrl(videoList.getFirst().getUrl()));
                } else {
                    throw new NotFoundException("资源已丢失");
                }
                break;
            case 2:
                List<ContentMedia> imageList = mediaMap.get(2);
                if (imageList != null && !imageList.isEmpty()) {
                    List<String> imageUrls = new ArrayList<>();
                    for (ContentMedia media : imageList) {
                        imageUrls.add(jointUrl(media.getUrl()));
                    }
                    dto.setImageUrls(imageUrls);
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
            updateCacheAfterAdd(conn, videoId);
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
            updateCacheAfterAdd(conn, contentId);
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

    private void updateCacheAfterAdd(Connection conn, long contentId) {
        try {
            ContentCacheDTO dto = contentDao.findContent(conn, contentId);
            if (dto == null) return;
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentId);
            buildContentMedia(dto, mediaMap);
            cacheContentBasic(contentId, dto);

            ContentVO cVO = new ContentVO();
            copyToContentVO(cVO, dto);
            recommendList.add(0, cVO);
            addToIndex(contentId, dto.getType(), dto.getCategoryId());
            LOGGER.info("新内容已加入缓存, contentId=" + contentId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "新内容缓存更新失败, contentId=" + contentId, e);
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
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto != null) {
            removeFromIndex(contentId, dto.getType(), dto.getCategoryId());
        }
        evictContent(contentId);
        synchronized (recommendList) {
            recommendList.removeIf(cvo -> cvo.getId() == contentId);
        }
    }

    // ===== 评论缓存实时更新 =====

    public static void addCommentToCache(long contentId, CommentCacheDTO newComment, Long parentId) {
        List<CommentCacheDTO> tree = commentCache.get(contentId);
        if (tree == null) return;
        newComment.setChildren(new ArrayList<>());
        if (parentId == null || parentId == 0) {
            tree.add(newComment);
        } else {
            insertChildToTree(tree, parentId, newComment);
        }
    }

    private static boolean insertChildToTree(List<CommentCacheDTO> tree, long parentId, CommentCacheDTO child) {
        for (CommentCacheDTO node : tree) {
            if (node.getCommentId() == parentId) {
                if (node.getChildren() == null) {
                    node.setChildren(new ArrayList<>());
                }
                node.getChildren().add(child);
                return true;
            }
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                if (insertChildToTree(node.getChildren(), parentId, child)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ===== 内存缓存实时同步 =====

    /**
     * 点赞/取消点赞后实时更新内存中 content 的 likeCount
     * 同时更新 contentCache 和 recommendList，避免等待 1 分钟定时刷新
     */
    public static void updateContentLikeCount(long contentId, int delta) {
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto != null) {
            dto.setLikeCount(dto.getLikeCount() + delta);
        }
        synchronized (recommendList) {
            for (ContentVO cvo : recommendList) {
                if (cvo.getId() == contentId) {
                    cvo.setLikeCount(cvo.getLikeCount() + delta);
                    break;
                }
            }
        }
    }

    public static void updateContentCommentCount(long contentId, int delta) {
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto != null) {
            dto.setCommentCount(dto.getCommentCount() + delta);
        }
        synchronized (recommendList) {
            for (ContentVO cvo : recommendList) {
                if (cvo.getId() == contentId) {
                    cvo.setCommentCount(cvo.getCommentCount() + delta);
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