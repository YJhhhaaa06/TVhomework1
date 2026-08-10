package com.itheima.model.vo;
import com.itheima.model.cache.CommentCacheDTO;

public class CommentVO extends CommentCacheDTO {
    private boolean isLiked;

    public CommentVO() {
        super();
    }

    public CommentVO(String username, long commentId, long contentId, long userId, String content, Long parentId, int likeCount) {
        super(username, commentId, contentId, userId, content, parentId, likeCount);
    }

    public boolean getIsLiked() {
        return isLiked;
    }

    public void setIsLiked(boolean isLiked) {
        this.isLiked = isLiked;
    }
}