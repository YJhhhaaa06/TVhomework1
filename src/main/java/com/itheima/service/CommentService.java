package com.itheima.service;

import com.itheima.command.CommentCommand;
import com.itheima.dao.CommentDao;
import com.itheima.exception.*;
import com.itheima.pojo.CommentCacheDTO;
import com.itheima.pojo.CommentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CommentService {

    private CommentDao commentDao = new CommentDao();
    private LikeService likeService = new LikeService();
    private static final Logger LOGGER =
            LogUtil.getLogger(CommentService.class);

    // ===== 构建评论树（缓存用）=====

    public List<CommentCacheDTO> buildCommentTree(List<CommentCacheDTO> list) {
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

    // ===== 转换：CommentCacheVO 树 → CommentVO 树（带 isLiked）=====

    public List<CommentVO> convertToCommentVOList(List<CommentCacheDTO> cacheList, Map<Long, Boolean> likedMap) {
        List<CommentVO> result = new ArrayList<>();
        for (CommentCacheDTO ccVO : cacheList) {
            result.add(convertToCommentVO(ccVO, likedMap));
        }
        return result;
    }

    private CommentVO convertToCommentVO(CommentCacheDTO ccVO, Map<Long, Boolean> likedMap) {
        CommentVO cVO = new CommentVO(
                ccVO.getUsername(), ccVO.getCommentId(), ccVO.getContentId(),
                ccVO.getUserId(), ccVO.getContent(), ccVO.getParentId(),
                ccVO.getLikeCount()
        );
        Boolean liked = likedMap.get(ccVO.getCommentId());
        cVO.setIsLiked(liked != null && liked);

        if (ccVO.getChildren() != null) {
            List<CommentVO> childVOList = new ArrayList<>();
            for (CommentCacheDTO child : ccVO.getChildren()) {
                childVOList.add(convertToCommentVO(child, likedMap));
            }
            cVO.setChildren(new ArrayList<>(childVOList));
        }
        return cVO;
    }

    // ===== 增 =====

    public void addComment(CommentCommand commentCommand) {
        long userId = commentCommand.getUserId();
        long contentId = commentCommand.getContentId();
        Long parentId = commentCommand.getParentId();
        String message = commentCommand.getMessage();

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            if (parentId != null && parentId != 0) {
                if (!commentDao.isParentIdCorrect(conn, parentId, contentId)) {
                    throw new ConflictException("被回复评论不属于该视频或动态");
                }
            }
            commentDao.addComment(conn, contentId, userId, message, parentId);
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    LOGGER.warning("添加评论时事务已回滚");
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE,"事务回滚失败",ex);
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "评论添加失败", ex);
                }
            }
            LOGGER.log(Level.SEVERE,"评论添加失败",e);
            throw new ServerException("评论写入失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 删 =====

    public void hideComment(long videoId) {
        hideOrUnhideComment(videoId, true);
    }

    public void unhideComment(long videoId) {
        hideOrUnhideComment(videoId, false);
    }

    public void hideOrUnhideComment(long videoId, boolean choose) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            if (choose) {
                commentDao.hideCommentByContent(conn, videoId);
            } else {
                commentDao.unhideCommentByContent(conn, videoId);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    LOGGER.warning("隐藏/显示评论时事务已回滚");
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE,"事务回滚失败",ex);
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            LOGGER.log(Level.SEVERE,"评论区操作失败", e);
            throw new ServerException("评论区操作失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    // ===== 查 =====

    private List<CommentCacheDTO> findComment(long videoId) throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return commentDao.getComments(conn, videoId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE,"查询评论失败",e);
            throw new ServerException("服务器异常，查询失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<CommentVO> getComment(long contentId) {
        try {
            List<CommentCacheDTO> wholeList = findComment(contentId);
            List<CommentCacheDTO> tree = buildCommentTree(wholeList);
            return convertToCommentVOList(tree, new HashMap<>());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE,"查询评论失败,contentId="+contentId,e);
            throw new ServerException("无法查询到id为" + contentId+"的视频或动态");
        }
    }

    public List<CommentVO> getComment(long contentId, long userId) {
        try {
            List<CommentCacheDTO> wholeList = findComment(contentId);
            List<CommentCacheDTO> tree = buildCommentTree(wholeList);
            // 收集所有评论 ID，批量查点赞状态
            List<Long> allCommentIds = collectAllCommentIds(tree);
            Map<Long, Boolean> likedMap = likeService.batchIsCommentLiked(userId, allCommentIds);
            return convertToCommentVOList(tree, likedMap != null ? likedMap : new HashMap<>());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE,"查询评论失败,contentId="+contentId,e);
            throw new NotFoundException("无法获取ID为" + contentId+"的视频或动态的评论");
        }
    }

    // ===== 收集评论 ID（递归）=====

    /**
     * 递归收集评论树中所有评论的 ID（用于批量查询点赞状态）
     */
    private List<Long> collectAllCommentIds(List<CommentCacheDTO> tree) {
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

    // ===== 缓存用：获取 CommentCacheVO 树 =====

    public List<CommentCacheDTO> getCommentCacheVOList(long contentId) {
        try {
            List<CommentCacheDTO> wholeList = findComment(contentId);
            return buildCommentTree(wholeList);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE,"查询评论缓存失败, contentId=" + contentId, e);
            throw new NotFoundException("FAIL_TO_GET_COMMENT,VIDEO_ID=" + contentId);
        }
    }

    // ===== 统计 =====

    public int countReplies(CommentCacheDTO ccVO) {
        int count = 0;
        List<CommentCacheDTO> children = ccVO.getChildren();
        if (children == null || children.isEmpty()) {
            return 0;
        }
        for (CommentCacheDTO child : children) {
            count += 1;
            count += countReplies(child);
        }
        return count;
    }
}