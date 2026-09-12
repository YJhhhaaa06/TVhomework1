package com.itheima.content.service;

import com.itheima.config.AppConfig;
import com.itheima.comment.dao.CommentDao;
import com.itheima.like.service.LikeCacheService;
import com.itheima.content.dao.ContentDao;
import com.itheima.content.dao.ContentMediaDao;
import com.itheima.exception.CacheException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.Disposable;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.entity.ContentMedia;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.RequestContext;
import com.itheima.util.TransactionTemplate;

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
        contentTimestamps.remove(contentId);
        likeCacheService.deleteContentLike(contentId);
    }

    /**
     * 删除内容后整体剔除缓存（A1，T3 后仅剩"旧格式 Redis 点赞 key 清理 + 内存残留"副作用，
     * T6 收尾随本类移除）：
     * 复用 evictContent 剔除索引/内容/时间戳/Redis 内容点赞，并同步移除推荐列表中的对应项。
     * recommendList 虽当前未被直接读取，但保持内存结构一致，防未来踩坑。
     */
    public void removeContent(long contentId) {
        evictContent(contentId);
        synchronized (recommendList) {
            recommendList.removeIf(v -> v.getId() == contentId);
        }
    }

    private void cacheContent(long contentId, ContentCacheDTO detail) {
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

                Map<String, List<Long>> newIndex = new HashMap<>();
                for (ContentCacheDTO dto : allContent) {
                    addToIndexInternal(newIndex, dto.getId(), dto.getType(), dto.getCategoryId());
                }

                recommendList = newRecommendList;
                contentCache = newContentCache;
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
        // 初始延迟=刷新周期：init() 已同步 refresh 一次，无需启动后 0 延迟再刷；
        // 否则首帧异步 refresh 会在单测 init() 后立刻覆盖缓存（偶发竞态）
        long refreshMinutes = AppConfig.getContentRefreshMinutes();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refresh();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "缓存定时刷新异常", e);
            }
        }, refreshMinutes, refreshMinutes, TimeUnit.MINUTES);
    }

    @Override
    public void destroy() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
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
                cacheContent(contentId, dto);
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
        cVO.setCommentEnabled(dto.isCommentEnabled());
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
        cdVO.setCommentEnabled(dto.isCommentEnabled());
        cdVO.setAuthorName(dto.getAuthorName());
        cdVO.setCoverUrl(dto.getCoverUrl());
        cdVO.setVideoUrl(dto.getVideoUrl());
        cdVO.setImageUrls(dto.getImageUrls());
        cdVO.setCreateTime(dto.getCreateTime());
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

    // ===== 作者编辑作品后同步缓存 =====

    /**
     * 换源/删图/改文案后同步内容缓存：
     * 未缓存则直接按需回填；已缓存则重载整行（含 title/description）与媒体，并同步推荐列表封面。
     * 不整体 evict，避免误清评论/点赞缓存。
     */
    public void refreshContent(long contentId) {
        if (!contentCache.containsKey(contentId)) {
            backfillContent(contentId);
            return;
        }
        try {
            ContentCacheDTO newDto = transactionTemplate.execute(conn -> {
                try {
                    ContentCacheDTO dto = contentDao.findContent(conn, contentId);
                    if (dto == null) {
                        return null;
                    }
                    Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentId);
                    buildContentMedia(dto, mediaMap);
                    return dto;
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "刷新内容缓存失败, contentId=" + contentId, e);
                    throw new ServerException("数据库读取失败");
                }
            });
            if (newDto == null) {
                evictContent(contentId);
                return;
            }
            contentCache.put(contentId, newDto);
            contentTimestamps.put(contentId, System.currentTimeMillis());
            ContentVO cvo = toContentVO(newDto);
            synchronized (recommendList) {
                for (int i = 0; i < recommendList.size(); i++) {
                    if (recommendList.get(i).getId() == contentId) {
                        recommendList.set(i, cvo);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "刷新内容缓存异常, contentId=" + contentId, e);
        }
    }

    // ===== 内存缓存实时同步（评论树方法已迁入 CommentCache，T3；本块为内容侧残留，T6 随本类移除） =====

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
     * 作者开关评论区后实时同步内存中 content 的 commentEnabled
     * 同时更新 contentCache 和 recommendList，避免等待定时刷新读到旧值
     */
    public void updateContentCommentEnabled(long contentId, boolean enabled) {
        ContentCacheDTO dto = contentCache.get(contentId);
        if (dto != null) {
            dto.setCommentEnabled(enabled);
        }
        synchronized (recommendList) {
            for (ContentVO cvo : recommendList) {
                if (cvo.getId() == contentId) {
                    cvo.setCommentEnabled(enabled);
                    break;
                }
            }
        }
    }

    private String jointUrl(String url) {
        return RequestContext.getContextPath() + url;
    }
}
