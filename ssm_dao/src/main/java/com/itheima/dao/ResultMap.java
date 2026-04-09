package com.itheima.dao;

import com.itheima.pojo.Video;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

public class ResultMap {
    //封装视频,一次只封装一个
    public static Video mapResultToVideo(ResultSet res) throws SQLException {
        long videoID = res.getLong("videoID");
        long uploadID = res.getLong("uploadID");
        String videoTitle = res.getString("videoTitle");
        String briefIntroduction = res.getString("briefIntroduction");
        return new Video(videoID, uploadID, videoTitle, briefIntroduction);
    }

}
