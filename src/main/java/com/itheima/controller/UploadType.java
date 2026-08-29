package com.itheima.controller;

import java.util.Arrays;
import java.util.List;

public enum UploadType {
    VIDEO("video", "video/", Arrays.asList(".mp4"),1),
    IMAGE("image", "image/", Arrays.asList(".jpg", ".png", ".jpeg"),2),
    COVER("cover", "image/", Arrays.asList(".jpg", ".png"),3);

    private final String dir;
    private final String contentTypePrefix;
    private final List<String> suffixes;
    private final int mediaType;

    public boolean isSuffixValid(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1|| dotIndex == fileName.length() - 1) {//防止没有 . 或 . 后没有东西
            return false;
        }
        String ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();//防止大小写问题
        //subString与lastIndexOf组合使用，可以获取最后一个 . 及后面的子串
        return suffixes.contains(ext);
    }


    UploadType(String dir, String contentTypePrefix, List<String> suffixes,int mediaType) {
        this.dir=dir;
        this.contentTypePrefix=contentTypePrefix;
        this.suffixes=suffixes;
        this.mediaType=mediaType;
    }

    private UploadType fromFileName(String fileName){//根据文件名匹配类型
        if (fileName == null) return null;
        String ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
        for (UploadType type : values()) {//values是所有枚举实例的数组
            if (type.suffixes.contains(ext)) {
                return type;
            }
        }
        return null;
    }
    public static UploadType fromContentType(String contentType) {//根据内容类型匹配
        if (contentType == null) return null;
        for (UploadType type : values()) {
            if (contentType.startsWith(type.contentTypePrefix)) {
                return type;   // 简单返回第一个匹配的
            }
        }
        return null;
    }

    /**
     * 按 media_type（content_media.type：1=视频,2=图片,3=封面）映射回上传类型，用于作者换源。
     */
    public static UploadType fromMediaType(int mediaType) {
        for (UploadType type : values()) {
            if (type.mediaType == mediaType) {
                return type;
            }
        }
        return null;
    }

    public String getDir() {
        return dir;
    }

    public String getContentTypePrefix() {
        return contentTypePrefix;
    }

    public List<String> getSuffixes() {
        return suffixes;
    }

    public int getMediaType() {
        return mediaType;
    }
}
