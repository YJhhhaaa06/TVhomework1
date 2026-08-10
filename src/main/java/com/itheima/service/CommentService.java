package com.itheima.service;

import com.itheima.model.command.CommentCommand;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.exception.*;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.Inject;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.vo.CommentVO;
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

@Component
public class CommentService {

    @Inject
    private CommentDao commentDao;
    @Inject
    private ContentDao contentDao;
    @Inject
    private LikeService likeService;
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

            if(!contentDao.isContentExist(conn,contentId)){
                throw new NotFoundException("被点赞的内容不存在");
            }
            if (parentId != null && parentId != 0) {
                if (!commentDao.isParentIdCorrect(conn, parentId, contentId)) {
                    throw new ConflictException("被回复评论不属于该视频或动态");
                }
            }
            long commentId = commentDao.addComment(conn, contentId, userId, message, parentId);
            contentDao.updateCommentCount(conn, contentId, 1);
            ContentService.updateContentCommentCount(contentId, 1);
            conn.commit();
            // 即时更新评论缓存
            CommentCacheDTO newComment = commentDao.findCommentById(conn, commentId);
            if (newComment != null) {
                ContentService.addCommentToCache(contentId, newComment, parentId);
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    LOGGER.warning("添加评论时事务已回滚");
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE,"事务回滚失败",ex);
                    throw new DatabaseException("评论添加失败", ex);
                }
            }
            LOGGER.log(Level.SEVERE,"评论添加失败",e);
            throw new ServerException("评论写入失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }


    // ===== 查 =====

    private List<CommentCacheDTO> findComment(long contentId) throws SQLException {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return commentDao.getComments(conn, contentId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE,"查询评论失败",e);
            throw new ServerException("服务器异常，查询失败");
        } finally {
            MyConnectionPool.release(conn);
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

}
