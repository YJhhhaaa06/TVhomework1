package com.itheima.pojo;

import java.time.LocalDateTime;

public class MediaAuditItem {
    private long mediaId;
    private long contentId;
    private String contentTitle;
    private int type;
    private String url;
    private boolean fileExists;
    private LocalDateTime lastVerifyTime;
    private String expectedPath;
    private String status;

    public long getMediaId() {
        return mediaId;
    }

    public void setMediaId(long mediaId) {
        this.mediaId = mediaId;
    }

    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
    }

    public String getContentTitle() {
        return contentTitle;
    }

    public void setContentTitle(String contentTitle) {
        this.contentTitle = contentTitle;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isFileExists() {
        return fileExists;
    }

    public void setFileExists(boolean fileExists) {
        this.fileExists = fileExists;
    }

    public LocalDateTime getLastVerifyTime() {
        return lastVerifyTime;
    }

    public void setLastVerifyTime(LocalDateTime lastVerifyTime) {
        this.lastVerifyTime = lastVerifyTime;
    }

    public String getExpectedPath() {
        return expectedPath;
    }

    public void setExpectedPath(String expectedPath) {
        this.expectedPath = expectedPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
