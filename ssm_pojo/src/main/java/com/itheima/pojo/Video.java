package com.itheima.pojo;

public class Video {
    private long videoID;
    private long uploadID;
    private String videoTitle;
    private String briefIntroduction;

    public Video(long videoID, long uploadID, String videoTitle, String briefIntroduction) {
        this.videoID = videoID;
        this.uploadID = uploadID;
        this.videoTitle = videoTitle;
        this.briefIntroduction = briefIntroduction;
    }

    public long getVideoID() {
        return videoID;
    }

    public void setVideoID(long videoID) {
        this.videoID = videoID;
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
                "videoID=" + videoID +
                ", uploadID=" + uploadID +
                ", videoTitle='" + videoTitle + '\'' +
                ", briefIntroduction='" + briefIntroduction + '\'' +
                '}';
    }
}
