package com.itheima.pojo;

public class Video {
    private long videoId;
    private long uploadId;
//    private String authorName;
    private String videoTitle;
    private String briefIntroduction="-";

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

    public Video(long videoId, long uploadId, String videoTitle, String briefIntroduction) {
        this.videoId = videoId;
        this.uploadId = uploadId;
//        this.authorName = authorName;
        this.videoTitle = videoTitle;
        this.briefIntroduction = briefIntroduction;
    }


    public void setVideoID(long videoID) {
        this.videoId = videoID;
    }

    public long getUploadId() {
        return uploadId;
    }

    public void setUploadId(long uploadId) {
        this.uploadId = uploadId;
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
                ", uploadID=" + uploadId +
                ", videoTitle='" + videoTitle + '\'' +
                ",\n briefIntroduction='" + briefIntroduction + '\'' +
                '}'+'\n';
    }
}
