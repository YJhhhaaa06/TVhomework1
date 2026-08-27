package com.itheima.service;

import com.itheima.model.command.CommentCommand;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.exception.*;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.vo.CommentVO;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class CommentService {

    private final CommentDao commentDao;
    private final ContentDao contentDao;
    private final ContentCacheManager contentCacheManager;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(CommentService.class);

    @InjectConstructor
    public CommentService(CommentDao commentDao, ContentDao contentDao,
                          ContentCacheManager contentCacheManager,
                          TransactionTemplate transactionTemplate) {
        this.commentDao = commentDao;
        this.contentDao = contentDao;
        this.contentCacheManager = contentCacheManager;
        this.transactionTemplate = transactionTemplate;
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

        // 楼中楼：parentId 一律指向主楼。被回复评论本身是回复时，上溯挂到其主楼 id
        // 用数组持有归一化结果，避免在 lambda 内改写被捕获变量（编译约束）
        Long[] effectiveParentId = { parentId };
        CommentCacheDTO newComment = transactionTemplate.execute(conn -> {
            if(!contentDao.isContentExist(conn,contentId)){
                throw new NotFoundException("被评论的内容不存在");
            }
            if (effectiveParentId[0] != null && effectiveParentId[0] != 0) {
                if (!commentDao.isCommentExist(conn, effectiveParentId[0])) {
                    throw new ConflictException("被回复评论不存在或已删除");
                }
                CommentCacheDTO parent = commentDao.findCommentById(conn, effectiveParentId[0]);
                if (parent.getContentId() != contentId) {
                    throw new ConflictException("被回复评论不属于该视频或动态");
                }
                if (parent.getParentId() != null && parent.getParentId() != 0) {
                    effectiveParentId[0] = parent.getParentId();
                }
            }
            try {
                long commentId = commentDao.addComment(conn, contentId, userId, message, effectiveParentId[0]);
                contentDao.updateCommentCount(conn, contentId, 1);
                contentCacheManager.updateContentCommentCount(contentId, 1);
                return commentDao.findCommentById(conn, commentId);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "评论添加失败", e);
                throw new ServerException("评论写入失败");
            }
        });

        // 缓存更新放在事务提交后
        if (newComment != null) {
            contentCacheManager.addCommentToCache(contentId, newComment, effectiveParentId[0]);
        }
    }

    // ===== 删（软删除，均不可恢复）=====

    /** 用户自删：仅能删除自己的评论 */
    public void deleteCommentByUser(long commentId, long userId) {
        doDeleteComment(commentId, userId, false);
    }

    /** 管理员删：可删除任意评论 */
    public void deleteCommentByAdmin(long commentId) {
        doDeleteComment(commentId, 0L, true);
    }

    private void doDeleteComment(long commentId, long operatorUserId, boolean isAdmin) {
        DeletedComment deleted = transactionTemplate.execute(conn -> {
            try {
                if (!commentDao.isCommentExist(conn, commentId)) {
                    throw new NotFoundException("评论不存在");
                }
                CommentCacheDTO comment = commentDao.findCommentById(conn, commentId);
                if (!isAdmin && comment.getUserId() != operatorUserId) {
                    throw new ForbiddenException("只能删除自己的评论");
                }
                // 楼中楼删除规则：主楼整栋软删，回复只删自己
                boolean isMain = comment.getParentId() == null || comment.getParentId() == 0;
                int deletedCount;
                if (isMain) {
                    // 必须先计数再软删：countFloorReplies 过滤 is_deleted=0，若先软删会数到 0 导致计数少扣
                    deletedCount = 1 + commentDao.countFloorReplies(conn, commentId);
                    commentDao.softDeleteFloor(conn, commentId);
                } else {
                    commentDao.softDeleteOne(conn, commentId);
                    deletedCount = 1;
                }
                contentDao.updateCommentCount(conn, comment.getContentId(), -deletedCount);
                return new DeletedComment(comment.getContentId(), commentId, deletedCount, isMain);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "评论删除失败, commentId=" + commentId, e);
                throw new ServerException("评论删除失败");
            }
        });

        // 缓存/计数同步放在事务提交后
        if (deleted != null) {
            contentCacheManager.updateContentCommentCount(deleted.contentId, -deleted.deletedCount);
            contentCacheManager.removeCommentFromCache(deleted.contentId, deleted.commentId, deleted.isMain);
        }
    }

    private static final class DeletedComment {
        final long contentId;
        final long commentId;
        final int deletedCount;
        final boolean isMain;

        DeletedComment(long contentId, long commentId, int deletedCount, boolean isMain) {
            this.contentId = contentId;
            this.commentId = commentId;
            this.deletedCount = deletedCount;
            this.isMain = isMain;
        }
    }


}
