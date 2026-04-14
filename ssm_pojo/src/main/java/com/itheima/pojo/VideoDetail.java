package com.itheima.pojo;

import java.util.List;

public class VideoDetail {
    private String title;//
    private String intro;//
    private String authorName;//
    private long videoId;
    private long uploadID;//
    private String url;//
    public List<Comment> commentList;

    public VideoDetail(String title, String intro, String authorName, String url) {
        this.title = title;
        this.intro = intro;
        this.authorName = authorName;
        this.url = url;
    }

    public VideoDetail() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public long getVideoId() {
        return videoId;
    }

    public void setVideoId(long videoId) {
        this.videoId = videoId;
    }

    public long getUploadID() {
        return uploadID;
    }

    public void setUploadID(long uploadID) {
        this.uploadID = uploadID;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<Comment> getCommentList() {
        return commentList;
    }

    public void setCommentList(List<Comment> commentList) {
        this.commentList = commentList;
    }
    @Override
    public String toString(){
       return "Video{" +
        "videoID=" + videoId +
        ", uploadID=" + uploadID +
        ", videoTitle='" + title + '\'' +
        ", briefIntroduction='" + intro + '\'' +
               ",url='"+url+'\''+
               ",authorName="+authorName+'\''+
        '}';
    }
}
