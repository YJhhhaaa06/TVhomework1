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

            cache.likeContent(userId, contentId);

        } catch (Exception e) {
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

            cache.unlikeContent(userId, contentId);

        } catch (Exception e) {
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

            cache.likeComment(userId, commentId);

        } catch (Exception e) {
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

            cache.unlikeComment(userId, commentId);

        } catch (Exception e) {
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

    // ==================== 内容查询（缓存优先，miss 回源 DB）====================

    public boolean isContentLiked(long userId, long contentId) {
        Boolean cached = cache.isContentLiked(userId, contentId);
        if (cached != null) {
            return cached;
        }

        try {
            boolean liked = contentLikeDao.isLiked(userId, contentId);
            cache.syncContentLikeStatus(userId, contentId, liked);
            return liked;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞状态失败");
        }
    }

    public int getContentLikeCount(long contentId) {
        Integer cached = cache.getContentLikeCount(contentId);
        if (cached != null) {
            return cached;
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            int count = contentDao.getLikeCount(conn, contentId);
            cache.syncContentLikeCount(contentId, count);
            return count;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞数失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ==================== 评论查询（缓存优先，miss 回源 DB）====================

    public boolean isCommentLiked(long userId, long commentId) {
        Boolean cached = cache.isCommentLiked(userId, commentId);
        if (cached != null) {
            return cached;
        }

        try {
            boolean liked = commentLikeDao.isLiked(userId, commentId);
            cache.syncCommentLikeStatus(userId, commentId, liked);
            return liked;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞状态失败");
        }
    }

    public int getCommentLikeCount(long commentId) {
        Integer cached = cache.getCommentLikeCount(commentId);
        if (cached != null) {
            return cached;
        }

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            int count = commentDao.getLikeCount(conn, commentId);
            cache.syncCommentLikeCount(commentId, count);
            return count;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException("服务器异常，查询点赞数失败");
        } finally {
            MyConnectionPool.release(conn);
        }
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