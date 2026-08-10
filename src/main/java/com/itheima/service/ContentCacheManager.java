package com.itheima.service;

import com.itheima.config.AppConfig;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.entity.ContentMedia;
import com.itheima.model.vo.ContentDetailVO;
import com.itheima.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.RequestContext;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ContentCacheManager implements Initializable, Disposable {
    private final ContentDao contentDao;
    private final ContentMediaDao contentMediaDao;
    private final CommentDao commentDao;
    private final LikeCacheService likeCacheService;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(ContentCacheManager.class);

    private List<ContentVO> recommendList = new ArrayList<>();
    private Map<Long, ContentCacheDTO> contentCache = new HashMap<>();
    private Map<Long, List<CommentCacheDTO>> commentCache = new HashMap<>();
    private Map<Long, Long> contentTimestamps = new HashMap<>();
    private final long CONTENT_TTL_MS = AppConfig.getContentTtlMillis();
    private Map<String, List<Long>> typeCategoryIndex = new HashMap<>();
    private ScheduledExecutorService scheduler;

    @InjectConstructor
    public ContentCacheManager(ContentDao contentDao, ContentMediaDao contentMediaDao,
                               CommentDao commentDao, LikeCacheService likeCacheService,
                               TransactionTemplate transactionTemplate) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.commentDao = commentDao;
        this.likeCacheService = likeCacheService;
        this.transactionTemplate = transactionTemplate;
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
        likeCacheService.deleteContentLike(contentId);
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
            result.add(toContentVO(dto));
            if (result.size() >= limit) break;
        }
        return result;
    }

    // ===== 初始化 =====
    @Override
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
        transactionTemplate.execute(conn -> {
            try {
                List<ContentCacheDTO> allContent = contentDao.findAllContent(conn);

                Map<Long, ContentCacheDTO> newContentCache = new HashMap<>();
                for (ContentCacheDTO dto : allContent) {
                    Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, dto.getId());
                    buildContentMedia(dto, mediaMap);
                    newContentCache.put(dto.getId(), dto);
                }

                List<ContentVO> newRecommendList = new ArrayList<>();
                for (ContentCacheDTO dto : allContent) {
                    newRecommendList.add(toContentVO(dto));
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
                LOGGER.log(Level.WARNING, "内容缓存刷新失败", e);

                throw new CacheException("内容缓存刷新失败", e);
            }
            return null;
        });
    }

    public void startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refresh();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "缓存定时刷新异常", e);
            }
        }, 0, AppConfig.getContentRefreshMinutes(), TimeUnit.MINUTES);
    }

    @Override
    public void destroy() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    // ===== 构建缓存 =====

    private Map<Long, List<CommentCacheDTO>> getCommentCache(Set<Long> contentIds) {
        Map<Long, List<CommentCacheDTO>> map = new HashMap<>();
        for (Long contentId : contentIds) {
            try {
                List<CommentCacheDTO> tree = loadCommentTree(contentId);
                map.put(contentId, tree);
            } catch (Exception e) {
                LOGGER.warning("获取评论缓存失败，返回空列表, contentId=" + contentId);
                map.put(contentId, new ArrayList<>());
            }
        }
        return map;
    }

    private List<CommentCacheDTO> loadCommentTree(long contentId) {
        List<CommentCacheDTO> wholeList = transactionTemplate.execute(conn -> {
            try {
                return commentDao.getComments(conn, contentId);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "查询评论失败", e);
                throw new ServerException("服务器异常，查询失败");
            }
        });
        return buildCommentTree(wholeList);
    }

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
            } else {
                CommentCacheDTO parent = map.get(parentId);
                if (parent != null) {
                    parent.getChildren().add(c);
                }
            }
        }
        return roots;
    }

    // ===== 查询（缓存）=====



    public ContentCacheDTO getContentFromCache(long contentId) {
        if (isContentExpired(contentId)) {//如果content在缓存中已经过期或不存在
            evictContent(contentId);//那就把这个ID的content从缓存中清除
        }
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto == null) {
            backfillContent(contentId);
            dto = contentCache.get(contentId);
        }
        return dto;
    }

    // ===== 缓存回填 =====

    private void backfillContent(long contentId) {
        try {
            transactionTemplate.execute(conn -> {
                ContentCacheDTO dto = contentDao.findContent(conn, contentId);
                if (dto == null) return null;
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, dto.getId());
                buildContentMedia(dto, mediaMap);
                List<CommentCacheDTO> comments = loadCommentTree(contentId);
                cacheContent(contentId, dto, comments);
                addToIndex(contentId, dto.getType(), dto.getCategoryId());
                LOGGER.info("缓存回填成功, contentId=" + contentId);
                return null;
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "缓存回填失败, contentId=" + contentId, e);
        }
    }

    // ===== 复制方法 =====

    public ContentVO toContentVO(ContentCacheDTO dto) {
        ContentVO cVO = new ContentVO();
        copyToContentVO(cVO, dto);
        return cVO;
    }

    public ContentDetailVO toDetailVO(ContentCacheDTO dto) {
        ContentDetailVO cdVO = new ContentDetailVO();
        copyToDetailVO(cdVO, dto);
        return cdVO;
    }

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

    // ===== 评论树工具（缓存用）=====

    public List<CommentCacheDTO> getCommentTree(long contentId) {
        return commentCache.getOrDefault(contentId, new ArrayList<>());
    }

    public List<Long> collectCommentIds(List<CommentCacheDTO> tree) {
        List<Long> ids = new ArrayList<>();
        for (CommentCacheDTO ccVO : tree) {
            collectIdsRecursive(ccVO, ids);
        }
        return ids;
    }

    private static void collectIdsRecursive(CommentCacheDTO ccVO, List<Long> ids) {
        ids.add(ccVO.getCommentId());
        if (ccVO.getChildren() != null) {
            for (CommentCacheDTO child : ccVO.getChildren()) {
                collectIdsRecursive(child, ids);
            }
        }
    }

    // ===== 媒体填充 =====

    private void buildContentMedia(ContentCacheDTO dto, Map<Integer, List<ContentMedia>> mediaMap) {
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

    // ===== 新增内容后更新缓存 =====

    public void updateCacheAfterAdd(Connection conn, long contentId) {
        try {
            ContentCacheDTO dto = contentDao.findContent(conn, contentId);
            if (dto == null) return;
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentId);
            buildContentMedia(dto, mediaMap);
            cacheContentBasic(contentId, dto);

            ContentVO cVO = toContentVO(dto);
            //初始化评论缓存
            commentCache.put(contentId,new ArrayList<CommentCacheDTO>());

            recommendList.addFirst(cVO);
            addToIndex(contentId, dto.getType(), dto.getCategoryId());
            LOGGER.info("新内容已加入缓存, contentId=" + contentId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "新内容缓存更新失败, contentId=" + contentId, e);
        }
    }

    // ===== 评论缓存实时更新 =====

    public void addCommentToCache(long contentId, CommentCacheDTO newComment, Long parentId) {
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
    public void updateContentLikeCount(long contentId, int delta) {
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

    public void updateContentCommentCount(long contentId, int delta) {
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
    public void updateCommentLikeCount(long commentId, int delta) {
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
        return RequestContext.getContextPath() + url;
    }
}
