package com.itheima.like.service;

import com.itheima.comment.dao.CommentDao;
import com.itheima.like.dao.CommentLikeDao;
import com.itheima.content.dao.ContentDao;
import com.itheima.like.dao.ContentLikeDao;
import com.itheima.content.service.CommentCache;
import com.itheima.content.service.ContentCache;
import com.itheima.exception.ConflictException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class LikeService {

    private final ContentDao contentDao;
    private final CommentDao commentDao;
    private final ContentLikeDao contentLikeDao;
    private final CommentLikeDao commentLikeDao;
    private final LikeCacheService cache;
    private final ContentCache contentCache;
    private final CommentCache commentCache;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(LikeService.class);

    @InjectConstructor
    public LikeService(ContentDao contentDao, CommentDao commentDao,
                       ContentLikeDao contentLikeDao, CommentLikeDao commentLikeDao,
                       LikeCacheService cache, ContentCache contentCache,
                       CommentCache commentCache,
                       TransactionTemplate transactionTemplate) {
        this.contentDao = contentDao;
        this.commentDao = commentDao;
        this.contentLikeDao = contentLikeDao;
        this.commentLikeDao = commentLikeDao;
        this.cache = cache;
        this.contentCache = contentCache;
        this.commentCache = commentCache;
        this.transactionTemplate = transactionTemplate;
    }

    // ==================== 内容点赞 ====================

    public void likeContent(long userId, long contentId) {
        transactionTemplate.execute(conn -> {
            if (!contentDao.isContentExist(conn, contentId)) {
                throw new NotFoundException("内容不存在");
            }
            if (contentLikeDao.isLiked(conn, userId, contentId)) {
                throw new ConflictException("不可重复点赞");
            }
            try {
                contentLikeDao.addLike(conn, userId, contentId);
                contentDao.updateLikeCount(conn, contentId, 1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "内容点赞失败, userId=" + userId + ", contentId=" + contentId, e);
                throw new ServerException("服务器异常，点赞失败");
            }
        });

        // 缓存更新放在事务提交后
        cache.likeContent(userId, contentId);
        // 失效内容 key，读自愈回填 DB 最新 like_count（T2 4.5 显式失效）
        contentCache.notifyLikeCountChanged(contentId);
    }

    public void removeLikeContent(long userId, long contentId) {
        transactionTemplate.execute(conn -> {
            if (!contentDao.isContentExist(conn, contentId)) {
                throw new NotFoundException("内容不存在");
            }
            if (!contentLikeDao.isLiked(conn, userId, contentId)) {
                throw new ConflictException("未点赞，无法取消");
            }
            try {
                contentLikeDao.deleteLike(conn, userId, contentId);
                contentDao.updateLikeCount(conn, contentId, -1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "取消内容点赞失败, userId=" + userId + ", contentId=" + contentId, e);
                throw new ServerException("服务器异常，取消点赞失败");
            }
        });

        cache.unlikeContent(userId, contentId);
        // 失效内容 key，读自愈回填 DB 最新 like_count（T2 4.5 显式失效）
        contentCache.notifyLikeCountChanged(contentId);
    }

    // ==================== 评论点赞 ====================

    public void likeComment(long userId, long commentId) {
        transactionTemplate.execute(conn -> {
            if (!commentDao.isCommentExist(conn, commentId)) {
                throw new NotFoundException("评论不存在");
            }
            if (commentLikeDao.isLiked(conn, userId, commentId)) {
                throw new ConflictException("不可重复点赞");
            }
            try {
                commentLikeDao.addLike(conn, userId, commentId);
                commentDao.updateLikeCount(conn, commentId, 1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "评论点赞失败, userId=" + userId + ", commentId=" + commentId, e);
                throw new ServerException("服务器异常，点赞失败");
            }
        });

        cache.likeComment(userId, commentId);
        // 失效评论所属内容评论树 key，读自愈回填 DB 最新 like_count（T3 4.5 业务显式失效）
        commentCache.notifyCommentLikeChanged(commentId);
    }

    public void removeLikeComment(long userId, long commentId) {
        transactionTemplate.execute(conn -> {
            if (!commentDao.isCommentExist(conn, commentId)) {
                throw new NotFoundException("评论不存在");
            }
            if (!commentLikeDao.isLiked(conn, userId, commentId)) {
                throw new ConflictException("未点赞，不可取消");
            }
            try {
                commentLikeDao.removeLike(conn, userId, commentId);
                commentDao.updateLikeCount(conn, commentId, -1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "取消评论点赞失败, userId=" + userId + ", commentId=" + commentId, e);
                throw new ServerException("服务器异常，取消点赞失败");
            }
        });

        cache.unlikeComment(userId, commentId);
        // 失效评论所属内容评论树 key，读自愈回填 DB 最新 like_count（T3 4.5 业务显式失效）
        commentCache.notifyCommentLikeChanged(commentId);
    }

    // ==================== 内容点赞查询（单条，T4 起纯委托 LikeCacheService） ====================

    /**
     * 查询用户是否点赞了某个内容。
     * T4 起缓存类内部完成"三态读 → miss 单飞回填 → Redis 异常降级 DB"（NEEDS 4.2/4.6/4.9），
     * 本方法只做委托，不再自带 DB 回填（回填点单飞防击穿 H6）。
     */
    public boolean isContentLiked(long userId, long contentId) {
        return cache.isContentLiked(userId, contentId);
    }

    /**
     * 查询内容的点赞数（计数/成员分离，只走 count key，0 为合法值）。
     */
    public int getContentLikeCount(long contentId) {
        return cache.getContentLikeCount(contentId);
    }

    // ==================== 评论点赞查询（单条，同 T4 委托） ====================

    /**
     * 查询用户是否点赞了某条评论。
     */
    public boolean isCommentLiked(long userId, long commentId) {
        return cache.isCommentLiked(userId, commentId);
    }

    /**
     * 查询评论的点赞数。
     */
    public int getCommentLikeCount(long commentId) {
        return cache.getCommentLikeCount(commentId);
    }

    // ==================== 批量查询点赞状态（T4 起纯委托，DB 兜底在缓存内部） ====================

    /**
     * 批量查询用户对多个内容的点赞状态。
     * 缓存内部 pipeline 扫描 + DB 批量兜底 + 逐个单飞回填，返回完整映射。
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return cache.batchIsContentLiked(userId, contentIds);
    }

    /**
     * 批量查询用户对多个评论的点赞状态（逻辑同内容批量）。
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return cache.batchIsCommentLiked(userId, commentIds);
    }
}
