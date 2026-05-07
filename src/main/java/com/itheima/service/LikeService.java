package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.CommentLikeDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentLikeDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class LikeService {
    private final ContentDao contentDao = new ContentDao();
    private final CommentDao commentDao = new CommentDao();
    private final ContentLikeDao contentLikeDao = new ContentLikeDao();
    private final CommentLikeDao commentLikeDao = new CommentLikeDao();
    private final LikeCacheService cache = new LikeCacheService();

    // ==================== 内容点赞 ====================

    public void likeContent(long userId, long contentId) throws Exception {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            if (!contentDao.isContentExist(conn, contentId)) {
                throw new NotFoundException("内容不存在");
            }
            if (contentLikeDao.isLiked(conn, userId, contentId)) {
                throw new ConflictException("不可重复点赞");
            }

            contentLikeDao.addLike(conn, userId, contentId);
            contentDao.updateLikeCount(conn, contentId, 1);
            conn.commit();

            // 缓存：将 userId 加入 content:like:{contentId} 集合
            cache.likeContent(userId, contentId);
            // 实时更新内存缓存中的点赞数
            ContentService.updateContentLikeCount(contentId, 1);

        } catch (SQLException e) {
            rollback(conn);
            e.printStackTrace();
            throw new ServerException("服务器异常，点赞失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public void removeLikeContent(long userId, long contentId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            if (!contentDao.isContentExist(conn, contentId)) {
                throw new NotFoundException("内容不存在");
            }
            if (!contentLikeDao.isLiked(conn, userId, contentId)) {
                throw new ConflictException("未点赞，无法取消");
            }

            contentLikeDao.deleteLike(conn, userId, contentId);
            contentDao.updateLikeCount(conn, contentId, -1);
            conn.commit();

            // 缓存：将 userId 从 content:like:{contentId} 集合中移除
            cache.unlikeContent(userId, contentId);
            // 实时更新内存缓存中的点赞数
            ContentService.updateContentLikeCount(contentId, -1);

        } catch (SQLException e) {
            rollback(conn);
            e.printStackTrace();
            throw new ServerException("服务器异常，取消点赞失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ==================== 评论点赞 ====================

    public void likeComment(long userId, long commentId) throws Exception {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            if (!commentDao.isCommentExist(conn, commentId)) {
                throw new NotFoundException("评论不存在");
            }
            if (commentLikeDao.isLiked(conn, userId, commentId)) {
                throw new ConflictException("不可重复点赞");
            }

            commentLikeDao.addLike(conn, userId, commentId);
            commentDao.updateLikeCount(conn, commentId, 1);
            conn.commit();

            // 缓存：将 userId 加入 comment:like:{commentId} 集合
            cache.likeComment(userId, commentId);
            // 实时更新内存缓存中的点赞数
            ContentService.updateCommentLikeCount(commentId, 1);

        } catch (SQLException e) {
            rollback(conn);
            e.printStackTrace();
            throw new ServerException("服务器异常，点赞失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public void removeLikeComment(long userId, long commentId) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);

            if (!commentDao.isCommentExist(conn, commentId)) {
                throw new NotFoundException("评论不存在");
            }
            if (!commentLikeDao.isLiked(conn, userId, commentId)) {
                throw new ConflictException("未点赞，不可取消");
            }

            commentLikeDao.removeLike(conn, userId, commentId);
            commentDao.updateLikeCount(conn, commentId, -1);
            conn.commit();

            // 缓存：将 userId 从 comment:like:{commentId} 集合中移除
            cache.unlikeComment(userId, commentId);
            // 实时更新内存缓存中的点赞数
            ContentService.updateCommentLikeCount(commentId, -1);

        } catch (SQLException e) {
            try {
                conn.rollback();
            }catch (SQLException ex){
                e.addSuppressed(ex);
            }
            e.printStackTrace();
            throw new ServerException("服务器异常，取消点赞失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ==================== 内容点赞查询（单条，缓存优先） ====================

    /**
     * 查询用户是否点赞了某个内容
     * 缓存命中直接返回；缓存 miss 则从 DB 查该内容全部点赞者，回填 Redis 后返回
     */
    public boolean isContentLiked(long userId, long contentId) {
        Boolean cached = cache.isContentLiked(userId, contentId);
        if (cached != null) {
            return cached;
        }

        // 缓存 miss → DB 查该内容的所有点赞者，回填缓存
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> allLikers = contentLikeDao.findLikerIdsByContentId(conn, contentId);
            cache.syncContentLikers(contentId, allLikers);
            return allLikers.contains(userId);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞状态失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    /**
     * 查询内容的点赞数
     * 缓存命中直接返回 SCARD 结果；缓存 miss 则 DB 查全部点赞者，回填后返回集合大小
     */
    public int getContentLikeCount(long contentId) {
        Integer cached = cache.getContentLikeCount(contentId);
        if (cached != null) {
            return cached;
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> allLikers = contentLikeDao.findLikerIdsByContentId(conn, contentId);
            cache.syncContentLikers(contentId, allLikers);
            return allLikers.size();
        } catch (SQLException e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞数失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ==================== 评论点赞查询（单条，缓存优先） ====================

    /**
     * 查询用户是否点赞了某条评论
     */
    public boolean isCommentLiked(long userId, long commentId) {
        Boolean cached = cache.isCommentLiked(userId, commentId);
        if (cached != null) {
            return cached;
        }

        // 缓存 miss → DB 查该评论的所有点赞者，回填缓存
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> allLikers = commentLikeDao.findLikerIdsByCommentId(conn, commentId);
            cache.syncCommentLikers(commentId, allLikers);
            return allLikers.contains(userId);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞状态失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    /**
     * 查询评论的点赞数
     */
    public int getCommentLikeCount(long commentId) {
        Integer cached = cache.getCommentLikeCount(commentId);
        if (cached != null) {
            return cached;
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> allLikers = commentLikeDao.findLikerIdsByCommentId(conn, commentId);
            cache.syncCommentLikers(commentId, allLikers);
            return allLikers.size();
        } catch (SQLException e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞数失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ==================== 批量查询点赞状态（Redis pipeline + DB 兜底） ====================

    /**
     * 批量查询用户对多个内容的点赞状态
     * 1. Redis pipeline 批量 EXISTS + SISMEMBER
     * 2. 缓存未命中的 ID → DB 批量查 → 回填缓存
     * @return 完整的 contentId → isLiked 映射
     */
    public Map<Long, Boolean> batchIsContentLiked(long userId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. Redis pipeline 批量查询
        Map<Long, Boolean> result = new HashMap<>(cache.batchIsContentLiked(userId, contentIds));

        // 2. 找出缓存未命中的 ID
        List<Long> missed = new ArrayList<>();
        for (Long cid : contentIds) {
            if (!result.containsKey(cid)) {
                missed.add(cid);
            }
        }
        if (missed.isEmpty()) {
            return result;
        }

        // 3. DB 兜底：批量查漏掉的 ID
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            // 先查用户对这批 content 的点赞情况
            Set<Long> likedSet = contentLikeDao.findLikedContentIds(conn, userId, missed);
            for (Long cid : missed) {
                result.put(cid, likedSet.contains(cid));
            }
            // 再逐个回填缓存（加载每个 content 的全部点赞者）
            for (Long cid : missed) {
                Set<Long> allLikers = contentLikeDao.findLikerIdsByContentId(conn, cid);
                cache.syncContentLikers(cid, allLikers);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，批量查询点赞状态失败");
        } finally {
            MyConnectionPool.release(conn);
        }
        return result;
    }

    /**
     * 批量查询用户对多个评论的点赞状态
     * 逻辑同 batchIsContentLiked
     */
    public Map<Long, Boolean> batchIsCommentLiked(long userId, List<Long> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, Boolean> result = new HashMap<>(cache.batchIsCommentLiked(userId, commentIds));

        List<Long> missed = new ArrayList<>();
        for (Long cid : commentIds) {
            if (!result.containsKey(cid)) {
                missed.add(cid);
            }
        }
        if (missed.isEmpty()) {
            return result;
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> likedSet = commentLikeDao.findLikedCommentIds(conn, userId, missed);
            for (Long cid : missed) {
                result.put(cid, likedSet.contains(cid));
            }
            for (Long cid : missed) {
                Set<Long> allLikers = commentLikeDao.findLikerIdsByCommentId(conn, cid);
                cache.syncCommentLikers(cid, allLikers);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，批量查询点赞状态失败");
        } finally {
            MyConnectionPool.release(conn);
        }
        return result;
    }

    // ==================== 内部工具 ====================

    private void rollback(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
    }
}