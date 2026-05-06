package com.itheima.pojo;

import java.util.List;

public class ContentDetailVO extends ContentBaseVO{

//    private LocalDateTime crateTime=LocalDateTime.now();//时间
    public List<CommentVO> commentVOList;//评论

    private String videoUrl;
    private String coverUrl;
    private List<String> imageUrls;



    public ContentDetailVO() {
    }

    public ContentDetailVO(long id, long authorId, int type, String title, String description, int categoryId, int commentCount, int likeCount, String authorName, List<CommentVO> commentVOList, String videoUrl, String coverUrl){
       super(id,authorId,type,title,description,categoryId,commentCount,likeCount,authorName);
    }



    public List<CommentVO> getCommentList() {
        return commentVOList;
    }

    public void setCommentList(List<CommentVO> commentVOList) {
        this.commentVOList = commentVOList;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }
}
