package com.itheima.pojo;

public class UploadResult {

    private String url;
    private String absolutePath;

    public UploadResult() {
    }

    public UploadResult(String url, String absolutePath) {
        this.url = url;
        this.absolutePath = absolutePath;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    public void setAbsolutePath(String absolutePath) {
        this.absolutePath = absolutePath;
    }
}
