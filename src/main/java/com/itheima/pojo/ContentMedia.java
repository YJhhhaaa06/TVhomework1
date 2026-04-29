package com.itheima.pojo;

import java.util.List;
import java.util.Map;

public class ContentMedia {
    private long mediaId;
    private long contentId;
    private String url;
    private int type;
    private int sort;

    public ContentMedia(long mediaId, long contentId, String url, int type, int sort) {
        this.mediaId = mediaId;
        this.contentId = contentId;
        this.url = url;
        this.type = type;
        this.sort = sort;
    }

    public long getMediaId() {
        return mediaId;
    }

    public void setMediaId(long mediaId) {
        this.mediaId = mediaId;
    }

    public ContentMedia() {
    }

    public long getContentId() {
        return contentId;
    }

    public void setContentId(long contentId) {
        this.contentId = contentId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getSort() {
        return sort;
    }

    public void setSort(int sort) {
        this.sort = sort;
    }
}
