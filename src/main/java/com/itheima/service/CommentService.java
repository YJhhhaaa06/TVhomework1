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
import com.itheima.util.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
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
    private ContentCacheManager contentCacheManager;
    @Inject
    private TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(CommentService.class);

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

        CommentCacheDTO newComment = transactionTemplate.execute(conn -> {
            if(!contentDao.isContentExist(conn,contentId)){
                throw new NotFoundException("被点赞的内容不存在");
            }
            if (parentId != null && parentId != 0) {
                if (!commentDao.isParentIdCorrect(conn, parentId, contentId)) {
                    throw new ConflictException("被回复评论不属于该视频或动态");
                }
            }
            try {
                long commentId = commentDao.addComment(conn, contentId, userId, message, parentId);
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
            contentCacheManager.addCommentToCache(contentId, newComment, parentId);
        }
    }


}
