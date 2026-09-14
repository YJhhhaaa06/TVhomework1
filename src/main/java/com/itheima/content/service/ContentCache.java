package com.itheima.content.service;

import com.itheima.cache.CacheAside;
import com.itheima.cache.CacheKeys;
import com.itheima.cache.RedisAccess;
import com.itheima.cache.SingleFlight;
import com.itheima.config.AppConfig;
import com.itheima.content.dao.ContentDao;
import com.itheima.content.dao.ContentMediaDao;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.entity.ContentMedia;
import com.itheima.content.model.vo.ContentDetailVO;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.exception.CacheException;
import com.itheima.exception.DatabaseException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ParamException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.Initializable;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.RequestContext;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * 内容缓存（C 周期 T2，拆 ContentCacheManager 的内容职责）：统一 Redis 缓存层。
 *
 * <p>内容详情走 T1 CacheAside 三态（miss/hit-empty/hit-data + 空标记 60s + 单飞 + 写失败 DEL），
 * 类型分区索引迁为 Redis LIST {@code content:index:{type}:{category}}（4 key/内容，新前序），
 * 点赞/评论数/评论区开关变更 = 失效内容 key 让读自愈（DB 列为源真理，见 NEEDS 4.5）。
 *
 * <p>本类只负责内容；评论缓存见 {@link CommentCache}（T3 迁出）。任何缓存失败一律降级（4.2），
 * init 不 crash 应用（旧 ContentCacheManager 的 HashMap 实现已随 T6 移除）。
 * 索引懒重建：索引 key 缺失（Redis 重启/被清）时按需从 DB 重建，防 /start 空推荐；
 * 索引写入失败（三期 T4/N4）时 best-effort DEL 所属索引 key 让读路径触发懒重建自愈。
 */
@Component
public class ContentCache implements Initializable {

    private static final Logger LOGGER = LogUtil.getLogger(ContentCache.class);

    /** 索引懒重建单飞 key（进程内，非 Redis key）。 */
    private static final String INDEX_REBUILD_KEY = "content:index:rebuild";

    private final ContentDao contentDao;
    private final ContentMediaDao contentMediaDao;
    private final TransactionTemplate transactionTemplate;
    private final CacheAside cacheAside;
    private final RedisAccess redisAccess;
    private final SingleFlight singleFlight;

    @InjectConstructor
    public ContentCache(ContentDao contentDao, ContentMediaDao contentMediaDao,
                        TransactionTemplate transactionTemplate, CacheAside cacheAside,
                        RedisAccess redisAccess, SingleFlight singleFlight) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.transactionTemplate = transactionTemplate;
        this.cacheAside = cacheAside;
        this.redisAccess = redisAccess;
        this.singleFlight = singleFlight;
    }

    // ==================== 读路径 ====================

    /**
     * 三态 Cache-Aside 读内容详情。
     *
     * @return 内容 DTO；DB 无此内容/媒体损坏/读取异常 → null（hit-empty 空标记 60s 防穿透，
     *          Controller 侧 404 语义与旧实现一致）
     */
    public ContentCacheDTO getContent(long contentId) {
        return cacheAside.get(CacheKeys.content(contentId), ContentCacheDTO.class,
                () -> loadContentFromDb(contentId), ttlSeconds());
    }

    /**
     * 批量读内容（T8 读路径加固）：一趟 pipeline 拉多条三态（语义与单 key 完全一致，
     * miss 逐个单飞回填），供推荐/Feed/Profile 页循环复用，替代逐条 {@link #getContent} 的 2*N 往返。
     *
     * <p>返回 {@code contentId → DTO} 全量 Map（null 值合法 = hit-empty / DB 无数据），
     * 调用方按键按原序收集并跳过 null。
     */
    public Map<Long, ContentCacheDTO> getContentsBatch(List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = new ArrayList<>(contentIds.size());
        for (Long id : contentIds) {
            keys.add(CacheKeys.content(id));
        }
        Map<String, ContentCacheDTO> byKey = cacheAside.getBatch(keys, ContentCacheDTO.class,
                k -> loadContentFromDb(parseContentId(k)), ttlSeconds());
        Map<Long, ContentCacheDTO> byId = new HashMap<>(byKey.size());
        for (Map.Entry<String, ContentCacheDTO> entry : byKey.entrySet()) {
            byId.put(parseContentId(entry.getKey()), entry.getValue());
        }
        return byId;
    }

    private static long parseContentId(String dataKey) {
        return Long.parseLong(dataKey.substring("content:".length()));
    }

    /**
     * 首页推荐（类型/分区过滤）。索引为 Redis LIST，读取去重 + shuffle + limit（沿用旧 getRecommendByFilter 语义）。
     * type/category 参数校验与旧 buildQueryKey 一致（ParamException 同文案）。
     */
    public List<ContentVO> getRecommendByFilter(Integer type, Integer categoryId, int limit) {
        String indexKey = buildQueryKey(type, categoryId);
        ensureIndex(indexKey);

        List<Long> idList = readIndex(indexKey);
        if (idList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(idList));
        Collections.shuffle(distinctIds);
        // T8：批量一趟 pipeline 读全部候选（语义与逐条 getContent 一致），再按 shuffle 原序跳过 null 收到 limit
        Map<Long, ContentCacheDTO> byId = getContentsBatch(distinctIds);
        List<ContentVO> result = new ArrayList<>();
        for (Long contentId : distinctIds) {
            ContentCacheDTO dto = byId.get(contentId);
            if (dto == null) {
                continue;
            }
            result.add(toContentVO(dto));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    // ==================== 写路径（均应在 DB 事务提交后调用） ====================

    /** 新增内容后入缓存（H3 修复：不携带 Connection，事务外调用）。写失败由 CacheAside 自愈。 */
    public void addContent(long contentId) {
        ContentCacheDTO dto;
        try {
            dto = loadContentFromDb(contentId);
        } catch (DatabaseException e) {
            // 三期 T3：DB 瞬时失败 = 加载失败，跳过缓存同步（读自愈回填），不抛 500
            LOGGER.log(Level.WARNING, "新增内容缓存装载失败（跳过缓存同步）, contentId=" + contentId, e);
            return;
        }
        if (dto == null) {
            return; // 回滚/并发删除兜底
        }
        writeContent(dto);
        addToIndex(dto);
    }

    /** 编辑媒体/文案/恢复内容后重载缓存；DB 已删 → 走移除语义。 */
    public void refreshContent(long contentId) {
        ContentCacheDTO dto;
        try {
            dto = loadContentFromDb(contentId);
        } catch (DatabaseException e) {
            // 三期 T3：DB 瞬时失败 = 加载失败，保留旧缓存让读自愈，不做删除语义
            LOGGER.log(Level.WARNING, "刷新内容缓存装载失败（保留旧缓存，读自愈）, contentId=" + contentId, e);
            return;
        }
        if (dto == null) {
            removeContent(contentId);
            return;
        }
        writeContent(dto);
        addToIndex(dto);
    }

    /** 删除/下架后移除：失效内容 key（含空标记）+ 从全部索引 key 剔除该 id。 */
    public void removeContent(long contentId) {
        cacheAside.invalidate(CacheKeys.content(contentId));
        try {
            // T8：KEYS→SCAN（治 H13 阻塞），LREM 幂等，重复 key 无害
            redisAccess.executeVoid(j ->
                    forEachIndexKey(j, k -> j.lrem(k, 0, String.valueOf(contentId))));
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "内容索引移除失败, contentId=" + contentId, e);
        }
    }

    /** 点赞/取消点赞后：失效内容 key，读自愈回填 DB 最新 like_count（4.5 显式失效）。 */
    public void notifyLikeCountChanged(long contentId) {
        cacheAside.invalidate(CacheKeys.content(contentId));
    }

    /** 评论增删后：失效内容 key，读自愈回填 DB 最新 comment_count。 */
    public void notifyCommentCountChanged(long contentId) {
        cacheAside.invalidate(CacheKeys.content(contentId));
    }

    /** 作者开关评论区后：失效内容 key，读自愈回填 comment_enabled。 */
    public void updateCommentEnabled(long contentId) {
        cacheAside.invalidate(CacheKeys.content(contentId));
    }

    // ==================== VO 复制（自旧 ContentCacheManager 迁入，旧类已随 T6 移除） ====================

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

    // ==================== 启动全量重建（三期 T5：Redis 写入移出 DB 事务 + 批量 pipeline） ====================

    @Override
    public void init() {
        // 阶段一（DB 事务内，只读）：全表 content + 批量媒体装载，返回可构建列表；
        // 事务提交后阶段二在**事务外**写 Redis（H3 原则：Redis 写入不占 DB 事务）。
        List<ContentCacheDTO> buildable;
        try {
            buildable = transactionTemplate.execute(this::loadBuildableFromDb);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "内容缓存初始化 DB 装载失败（跳过，缓存走读自愈）", e);
            return;
        }
        rebuildRedis(buildable);
    }

    /**
     * 初始化 DB 装载（事务内）：findAllContent + 一趟批量媒体查询（三期 T5 消 N+1），
     * 媒体损坏内容跳过 content key（读时 404 兜底），返回按新前序的可构建列表。
     */
    private List<ContentCacheDTO> loadBuildableFromDb(Connection conn) throws Exception {
        List<ContentCacheDTO> all = contentDao.findAllContent(conn);
        List<ContentCacheDTO> buildable = new ArrayList<>();
        List<Long> contentIds = new ArrayList<>(all.size());
        for (ContentCacheDTO dto : all) {
            contentIds.add(dto.getId());
        }
        List<ContentMedia> mediaList = contentMediaDao.findMediaByContentIds(conn, contentIds);
        Map<Long, Map<Integer, List<ContentMedia>>> mediaByContent = groupMediaByContent(mediaList);
        for (ContentCacheDTO dto : all) {
            try {
                buildContentMedia(dto, mediaByContent.getOrDefault(dto.getId(), Collections.emptyMap()));
                buildable.add(dto);
            } catch (NotFoundException | ServerException e) {
                // 单个内容媒体损坏：跳过内容 key（读时仍会兜底 404），索引仍按 type/category 重建
                LOGGER.log(Level.WARNING, "初始化跳过媒体损坏内容, contentId=" + dto.getId(), e);
            }
        }
        return buildable;
    }

    /** 扁平行媒体按 contentId 分组为 type → media 列表。 */
    private static Map<Long, Map<Integer, List<ContentMedia>>> groupMediaByContent(List<ContentMedia> mediaList) {
        Map<Long, Map<Integer, List<ContentMedia>>> byContent = new HashMap<>();
        for (ContentMedia media : mediaList) {
            byContent.computeIfAbsent(media.getContentId(), k -> new HashMap<>())
                    .computeIfAbsent(media.getType(), k -> new ArrayList<>())
                    .add(media);
        }
        return byContent;
    }

    // ==================== 内部 ====================

    /**
     * DB 装载：findContent + 媒体构建。loader 契约（三期 T3 负缓存治理，NEEDS N2）：
     * <ul>
     *   <li>返回 null = 确认无数据（DB 无行 / 媒体损坏 / 未知类型）→ 允许 CacheAside 写空标记；</li>
     *   <li>抛 {@link DatabaseException} = 加载失败（SQLException 由事务模板包装）→ CacheAside
     *       不写空标记、不 DEL 数据 key，本次读转 null（对外行为不变）；</li>
     *   <li>其余意外异常统一包成 {@link DatabaseException} 上抛（防静默污染空标记）。</li>
     * </ul>
     */
    private ContentCacheDTO loadContentFromDb(long contentId) {
        try {
            return transactionTemplate.execute(conn -> {
                ContentCacheDTO dto = contentDao.findContent(conn, contentId);
                if (dto == null) {
                    return null;
                }
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentId);
                buildContentMedia(dto, mediaMap);
                return dto;
            });
        } catch (DatabaseException e) {
            LOGGER.log(Level.SEVERE, "内容装载 DB 查询失败（加载失败，不写空标记）, contentId=" + contentId, e);
            throw e;
        } catch (NotFoundException e) {
            LOGGER.log(Level.WARNING, "内容装载跳过（媒体损坏）, contentId=" + contentId, e);
            return null;
        } catch (ServerException e) {
            // 未知内容类型（buildContentMedia 抛出）= 确认无法构建，按无数据（空标记）
            LOGGER.log(Level.WARNING, "内容装载跳过（类型异常）, contentId=" + contentId, e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "内容装载异常（按加载失败处理，不写空标记）, contentId=" + contentId, e);
            throw new DatabaseException("内容装载失败", e);
        }
    }

    private void writeContent(ContentCacheDTO dto) {
        cacheAside.writeOrInvalidate(CacheKeys.content(dto.getId()), dto, ttlSeconds());
    }

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

    private String jointUrl(String url) {
        return RequestContext.getContextPath() + url;
    }

    // ==================== 索引 ====================

    private static String indexKey(int type, int categoryId) {
        return "content:index:" + type + ":" + categoryId;
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

    /** 索引懒重建：目标索引 key 不存在时按需从 DB 重建（单飞防惊群；Redis 异常降级为空/不 crash）。 */
    private void ensureIndex(String indexKey) {
        try {
            Boolean exists = redisAccess.execute(j -> j.exists(indexKey));
            if (Boolean.TRUE.equals(exists)) {
                return;
            }
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "索引检查失败（视为无索引）", e);
        }
        try {
            singleFlight.get(INDEX_REBUILD_KEY, () -> {
                List<ContentCacheDTO> all = loadAllWithoutMedia();
                rebuildIndexes(all);
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "索引懒重建失败，本次推荐降级为空", e);
        }
    }

    private List<Long> readIndex(String indexKey) {
        try {
            return redisAccess.execute(j -> {
                List<String> values = j.lrange(indexKey, 0, -1);
                if (values == null || values.isEmpty()) {
                    return new ArrayList<>();
                }
                List<Long> ids = new ArrayList<>(values.size());
                for (String v : values) {
                    try {
                        ids.add(Long.parseLong(v));
                    } catch (NumberFormatException ignored) {
                        // 脏值忽略
                    }
                }
                return ids;
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "索引读取失败（降级为空推荐）, key=" + indexKey, e);
            return new ArrayList<>();
        }
    }

    private void addToIndex(ContentCacheDTO dto) {
        try {
            redisAccess.executeVoid(j -> {
                lremAndLpush(j, dto.getType(), dto.getCategoryId(), dto.getId());
            });
        } catch (CacheException e) {
            // 三期 T4/N4：索引写失败不自愈会导致内容长期不进推荐（索引 key 存在则懒重建永不触发）——
            // best-effort DEL 本内容所属索引 key，下次推荐读 ensureIndex 发现缺失即触发既有单飞懒重建全量自愈
            LOGGER.log(Level.WARNING, "内容索引写入失败，DEL 索引 key 让读路径懒重建自愈, contentId="
                    + dto.getId(), e);
            deleteIndexKeysQuietly(indexKeysOf(dto.getType(), dto.getCategoryId()));
        }
    }

    private void lremAndLpush(redis.clients.jedis.Jedis j, int type, int categoryId, long contentId) {
        String[] keys = indexKeysOf(type, categoryId);
        String id = String.valueOf(contentId);
        for (String k : keys) {
            j.lrem(k, 0, id);
            j.lpush(k, id);
        }
    }

    /** Pipeline 版（三期 T5：rebuildIndexes 全量重建一趟入队）。 */
    private static void lremAndLpush(Pipeline p, int type, int categoryId, long contentId) {
        String[] keys = indexKeysOf(type, categoryId);
        String id = String.valueOf(contentId);
        for (String k : keys) {
            p.lrem(k, 0, id);
            p.lpush(k, id);
        }
    }

    /** 一条内容所属的 4 个索引 key（本 type/cid + 两个通配维度 + 全通配）；写入与自愈 DEL 同源。 */
    private static String[] indexKeysOf(int type, int categoryId) {
        return new String[]{
            indexKey(type, categoryId),
            indexKey(type, -1),
            indexKey(-1, categoryId),
            indexKey(-1, -1)
        };
    }

    /** 索引自愈 DEL（best-effort，三期 T4/N4）：DEL 失败（Redis 持续挂）不抛出——读路径同样降级，与现状一致。 */
    private void deleteIndexKeysQuietly(String[] indexKeys) {
        try {
            redisAccess.executeVoid(j -> {
                for (String k : indexKeys) {
                    j.del(k);
                }
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "索引自愈 DEL 也失败（疑似 Redis 持续异常），维持降级, firstKey="
                    + indexKeys[0], e);
        }
    }

    /**
     * 索引全量重建（三期 T5 pipeline 化）：先 SCAN 清掉历史 content:index:*（顺序读），
     * 再按 findAllContent 顺序（新前序）一趟 pipeline 重建全部索引。
     */
    private void rebuildIndexes(List<ContentCacheDTO> all) {
        try {
            redisAccess.executeVoid(j -> {
                // SCAN 段需逐页读游标，无法入 pipeline；先收集旧索引 key
                List<String> staleKeys = new ArrayList<>();
                forEachIndexKey(j, staleKeys::add);
                Pipeline p = j.pipelined();
                for (String k : staleKeys) {
                    p.del(k);
                }
                for (ContentCacheDTO dto : all) {
                    lremAndLpush(p, dto.getType(), dto.getCategoryId(), dto.getId());
                }
                p.sync();
            });
        } catch (CacheException e) {
            LOGGER.log(Level.WARNING, "索引重建失败（降级为空推荐）", e);
        }
    }

    /** content:index:* 索引 key 的 SCAN 遍历助手（T8：KEYS→SCAN，治 H13 REDIS 主线程 O(N) 阻塞）。 */
    private static void forEachIndexKey(Jedis j, Consumer<String> action) {
        ScanParams params = new ScanParams().match("content:index:*").count(100);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> r = j.scan(cursor, params);
            cursor = r.getCursor();
            for (String k : r.getResult()) {
                action.accept(k);
            }
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
    }

    /**
     * init 全量重建（三期 T5：DB 阶段已事务外提交）：
     * 内容 key 一趟 pipeline 批量写（writeBatch 逐 key 清空标记），索引一趟 pipeline 重建。
     */
    private void rebuildRedis(List<ContentCacheDTO> buildable) {
        Map<String, Object> contentByKey = new HashMap<>(buildable.size());
        for (ContentCacheDTO dto : buildable) {
            contentByKey.put(CacheKeys.content(dto.getId()), dto);
        }
        cacheAside.writeBatch(contentByKey, ttlSeconds());
        rebuildIndexes(buildable);
    }

    private List<ContentCacheDTO> loadAllWithoutMedia() {
        try {
            return transactionTemplate.execute(conn -> {
                try {
                    return contentDao.findAllContent(conn);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "索引重建 DB 查询失败", e);
                    return new ArrayList<>();
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "索引重建 DB 装载异常", e);
            return new ArrayList<>();
        }
    }

    private long ttlSeconds() {
        return AppConfig.getContentTtlMillis() / 1000;
    }
}