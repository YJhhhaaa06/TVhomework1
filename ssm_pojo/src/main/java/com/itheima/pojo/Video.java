package com.itheima.pojo;

public class Video {
    private long videoId;
    private long uploadID;
//    private String authorName;
    private String videoTitle;
    private String briefIntroduction;

    public long getVideoId() {
        return videoId;
    }

    public void setVideoId(long videoId) {
        this.videoId = videoId;
    }

//    public String getAuthorName() {
//        return authorName;
//    }
//
//    public void setAuthorName(String authorName) {
//        this.authorName = authorName;
//    }

    public Video(long videoId, long uploadID, String videoTitle, String briefIntroduction) {
        this.videoId = videoId;
        this.uploadID = uploadID;
//        this.authorName = authorName;
        this.videoTitle = videoTitle;
        this.briefIntroduction = briefIntroduction;
    }

    public long getVideoID() {
        return videoId;
    }

    public void setVideoID(long videoID) {
        this.videoId = videoID;
    }

    public long getUploadID() {
        return uploadID;
    }

    public void setUploadID(long uploadID) {
        this.uploadID = uploadID;
    }

    public String getVideoTitle() {
        return videoTitle;
    }

    public void setVideoTitle(String videoTitle) {
        this.videoTitle = videoTitle;
    }

    public String getBriefIntroduction() {
        return briefIntroduction;
    }

    public void setBriefIntroduction(String briefIntroduction) {
        this.briefIntroduction = briefIntroduction;
    }
    @Override
    public String toString() {
        return "Video{" +
                "videoID=" + videoId +
                ", uploadID=" + uploadID +
                ", videoTitle='" + videoTitle + '\'' +
                ", briefIntroduction='" + briefIntroduction + '\'' +
                '}';
    }
}
