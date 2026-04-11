package com.itheima.pojo;

public class VideoResult {
    private String title;
    private String intro;
    private String url;
    private String authorName;

    public VideoResult(String title, String intro, String url, String authorName) {
        this.title = title;
        this.intro = intro;
        this.url = url;
        this.authorName = authorName;
    }

    public VideoResult() {
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    @Override
    public String toString(){
        return title+"\n"+intro+"\n"+authorName+"\n"+url;
    }


}
