package com.itheima.dao;

import com.itheima.pojo.Video;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class VideoDao {

    private static final String url = "jdbc:mysql:///TVDatabase?useSSL=false";
    private static final String username = "root";
    private static final String password = "MySQL";


    //读取所有视频基本信息
    public static List<Video> findAllVideos() {
        List<Video> VideoList = new ArrayList<>();
        //面向接口：后续要改类型只用改new的类型
        String sql = "select * from videoInfo";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet res = pstmt.executeQuery();) {

            while (res.next()) {
                Video video = ResultMap.mapResultToVideo(res);
                VideoList.add(video);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return VideoList;
    }


    //获取视频内容（实际上只有视频封面）
    public static String findVideoInDetail(long VideoID) {
        String sql = "select videoID,video_url from video where videoID=?";
        try(Connection conn = DriverManager.getConnection(url, username, password);
            PreparedStatement pstmt = conn.prepareStatement(sql);
        ) {

            pstmt.setLong(1, VideoID);

            //try两次，因为中间设置参数不能try
            //try括号内的数据类型有要求
            try (ResultSet res = pstmt.executeQuery(); ) {
                if (res.next()) {
                    return res.getString("video_url");
                } else {
                    return null;
                }
            } catch (SQLException e) {
                return null;
                //暂时不处理异常
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
            //暂时不处理异常
        }
    }


}
