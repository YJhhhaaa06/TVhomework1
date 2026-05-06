package com.itheima.service;

import com.itheima.util.MyRedisPool;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

public class LikeCacheService {

    // ===== Redis key 生成 =====

    private String userLikeContentKey(long userId) {
        return "content:like:user:" + userId;
    }

    private String contentLikeCountKey(long contentId) {
        return "content:like:" + contentId;
    }

    private String userLikeCommentKey(long userId) {
        return "comment:like:user:" + userId;
    }

    private String commentLikeCountKey(long commentId) {
        return "comment:like:" + commentId;
    }

    // ===== 内容点赞 / 取消 =====

    public void likeContent(long userId, long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            Pipeline pipeline = jedis.pipelined();
            pipeline.sadd(userLikeContentKey(userId), String.valueOf(contentId));
            pipeline.incr(contentLikeCountKey(contentId));
            pipeline.sync();
        }
    }

    public void unlikeContent(long userId, long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            Pipeline pipeline = jedis.pipelined();
            pipeline.srem(userLikeContentKey(userId), String.valueOf(contentId));
            pipeline.decr(contentLikeCountKey(contentId));
            pipeline.sync();
        }
    }

    // ===== 评论点赞 / 取消 =====

    public void likeComment(long userId, long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            Pipeline pipeline = jedis.pipelined();
            pipeline.sadd(userLikeCommentKey(userId), String.valueOf(commentId));
            pipeline.incr(commentLikeCountKey(commentId));
            pipeline.sync();
        }
    }

    public void unlikeComment(long userId, long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            Pipeline pipeline = jedis.pipelined();
            pipeline.srem(userLikeCommentKey(userId), String.valueOf(commentId));
            pipeline.decr(commentLikeCountKey(commentId));
            pipeline.sync();
        }
    }

    // ===== 内容查询（miss 返回 null）=====

    public Boolean isContentLiked(long userId, long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = userLikeContentKey(userId);
            if (!jedis.exists(key)) {
                return null;
            }
            return jedis.sismember(key, String.valueOf(contentId));
        }
    }

    public Integer getContentLikeCount(long contentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String val = jedis.get(contentLikeCountKey(contentId));
            return val == null ? null : Integer.parseInt(val);
        }
    }

    // ===== 评论查询（miss 返回 null）=====

    public Boolean isCommentLiked(long userId, long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String key = userLikeCommentKey(userId);
            if (!jedis.exists(key)) {
                return null;
            }
            return jedis.sismember(key, String.valueOf(commentId));
        }
    }

    public Integer getCommentLikeCount(long commentId) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            String val = jedis.get(commentLikeCountKey(commentId));
            return val == null ? null : Integer.parseInt(val);
        }
    }

    // ===== 缓存回写（DB miss 后回填 Redis）=====

    public void syncContentLikeStatus(long userId, long contentId, boolean liked) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            if (liked) {
                jedis.sadd(userLikeContentKey(userId), String.valueOf(contentId));
            }
        }
    }

    public void syncContentLikeCount(long contentId, int count) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            jedis.set(contentLikeCountKey(contentId), String.valueOf(count));
        }
    }

    public void syncCommentLikeStatus(long userId, long commentId, boolean liked) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            if (liked) {
                jedis.sadd(userLikeCommentKey(userId), String.valueOf(commentId));
            }
        }
    }

    public void syncCommentLikeCount(long commentId, int count) {
        try (Jedis jedis = MyRedisPool.getJedis()) {
            jedis.set(commentLikeCountKey(commentId), String.valueOf(count));
        }
    }
}