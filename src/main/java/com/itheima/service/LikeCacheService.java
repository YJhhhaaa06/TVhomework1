package com.itheima.service;

import com.itheima.util.MyRedisPool;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.*;

/**
 * 点赞缓存服务（Redis）
 *
 * 缓存以 content / comment 为中心：
 *   content:like:{contentId}  → Set<userId>  谁给这个内容点过赞
 *   comment:like:{commentId}  → Set<userId>  谁给这条评论点过赞
 *
 * 点赞数直接通过 SCARD 获取，不需要单独的计数 key
 */
public class LikeCacheService {

    // ==================== Redis key 生成 ====================

    private String contentLikeKey(long contentId) {
        return "content:like:" + contentId;
    }

    private String commentLikeKey(long commentId) {
        return "comment:like:" + commentId;
    }

    // ==================== 内容点赞 / 取消 ====================

    /**
     * 缓存：用户给内容点赞
     * 将 userId 加入 content:like:{contentId} 集合
     */
    public void likeContent(long userId, long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            jedis.sadd(contentLikeKey(contentId), String.valueOf(userId));
        }
    }

    /**
     * 缓存：用户取消内容点赞
     * 将 userId 从 content:like:{contentId} 集合中移除
     */
    public void unlikeContent(long userId, long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            jedis.srem(contentLikeKey(contentId), String.valueOf(userId));
        }
    }

    // ==================== 评论点赞 / 取消 ====================

    /**
     * 缓存：用户给评论点赞
     * 将 userId 加入 comment:like:{commentId} 集合
     */
    public void likeComment(long userId, long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            jedis.sadd(commentLikeKey(commentId), String.valueOf(userId));
        }
    }

    /**
     * 缓存：用户取消评论点赞
     * 将 userId 从 comment:like:{commentId} 集合中移除
     */
    public void unlikeComment(long userId, long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            jedis.srem(commentLikeKey(commentId), String.valueOf(userId));
        }
    }

    // ==================== 内容点赞状态查询（单条） ====================

    /**
     * 查询用户是否点赞了某个内容
     * @return true=已点赞, false=未点赞, null=缓存未命中（key 不存在，需 DB 兜底）
     */
    public Boolean isContentLiked(long userId, long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = contentLikeKey(contentId);
            // 先判断 key 是否存在：SISMEMBER 对不存在的 key 也返回 false，无法区分"未点赞"和"缓存未命中"
            if (!jedis.exists(key)) {
                return null;
            }
            return jedis.sismember(key, String.valueOf(userId));
        }
    }

    /**
     * 查询内容的点赞数（SCARD 直接获取集合大小）
     * @return 点赞数, null=缓存未命中
     */
    public Integer getContentLikeCount(long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = contentLikeKey(contentId);
            if (!jedis.exists(key)) {
                return null;
            }
            long count = jedis.scard(key);
            return (int) count;
        }
    }

    // ==================== 评论点赞状态查询（单条） ====================

    /**
     * 查询用户是否点赞了某条评论
     * @return true=已点赞, false=未点赞, null=缓存未命中
     */
    public Boolean isCommentLiked(long userId, long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = commentLikeKey(commentId);
            if (!jedis.exists(key)) {
                return null;
            }
            return jedis.sismember(key, String.valueOf(userId));
        }
    }

    /**
     * 查询评论的点赞数
     * @return 点赞数, null=缓存未命中
     */
    public Integer getCommentLikeCount(long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = commentLikeKey(commentId);
            if (!jedis.exists(key)) {
                return null;
            }
            long count = jedis.scard(key);
            return (int) count;
        }
    }

    // ==================== 批量查询点赞状态（Redis pipeline） ====================

    /**
     * 批量查询用户对多个内容的点赞状态
     * 使用 Pipeline：一次网络往返，对每个 contentId 执行 EXISTS + SISMEMBER
     *
     * @return Map<contentId, 是否点赞>，仅包含缓存命中的 ID；
     *         如果某个 contentId 的 key 不存在，则该 ID 不在返回的 Map 中（需调用方 DB 兜底）
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try (Jedis jedis = MyRedisPool.getJedis()) {
            Pipeline pipeline = jedis.pipelined();

            List<Long> idList = new ArrayList<>(contentIds);
            List<Response<Boolean>> existResponses = new ArrayList<>();
            List<Response<Boolean>> memberResponses = new ArrayList<>();

            for (Long contentId : idList) {
                String key = contentLikeKey(contentId);
                existResponses.add(pipeline.exists(key));
                memberResponses.add(pipeline.sismember(key, String.valueOf(userId)));
            }
            pipeline.sync();

            // 收集结果：只有 key 存在时才放入 Map
            Map<Long, Boolean> result = new HashMap<>();
            for (int i = 0; i < idList.size(); i++) {
                if (Boolean.TRUE.equals(existResponses.get(i).get())) {
                    result.put(idList.get(i), memberResponses.get(i).get());
                }
                // key 不存在 → 不放入 result，由调用方对缺失 ID 走 DB 兜底
            }
            return result;
        }
    }

    /**
     * 批量查询用户对多个评论的点赞状态
     * 逻辑同 batchIsContentLiked
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try (Jedis jedis = MyRedisPool.getJedis()) {
            Pipeline pipeline = jedis.pipelined();

            List<Long> idList = new ArrayList<>(commentIds);
            List<Response<Boolean>> existResponses = new ArrayList<>();
            List<Response<Boolean>> memberResponses = new ArrayList<>();

            for (Long commentId : idList) {
                String key = commentLikeKey(commentId);
                existResponses.add(pipeline.exists(key));
                memberResponses.add(pipeline.sismember(key, String.valueOf(userId)));
            }
            pipeline.sync();

            Map<Long, Boolean> result = new HashMap<>();
            for (int i = 0; i < idList.size(); i++) {
                if (Boolean.TRUE.equals(existResponses.get(i).get())) {
                    result.put(idList.get(i), memberResponses.get(i).get());
                }
            }
            return result;
        }
    }

    // ==================== 缓存回填（DB 兜底后将全量点赞者写入 Redis） ====================

    /**
     * 将内容的所有点赞者 userId 写入缓存（DB 查询后回填 Redis）
     * 用 pipeline 批量 SADD，一次性写入
     */
    public void syncContentLikers(long contentId, Set<Long> userIds) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = contentLikeKey(contentId);
            if (userIds == null || userIds.isEmpty()) {
                // 即使没人点赞，也创建一个空集合标记"已加载"，避免反复穿透 DB
                jedis.sadd(key, "__placeholder__");
                jedis.srem(key, "__placeholder__");
                return;
            }
            String[] members = userIds.stream().map(String::valueOf).toArray(String[]::new);
            jedis.sadd(key, members);
        }
    }

    /**
     * 将评论的所有点赞者 userId 写入缓存（DB 查询后回填 Redis）
     */
    public void syncCommentLikers(long commentId, Set<Long> userIds) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = commentLikeKey(commentId);
            if (userIds == null || userIds.isEmpty()) {
                jedis.sadd(key, "__placeholder__");//防止service以为是缓存未命中
                jedis.srem(key, "__placeholder__");
                return;
            }
            String[] members = userIds.stream().map(String::valueOf).toArray(String[]::new);
            jedis.sadd(key, members);
        }
    }
}