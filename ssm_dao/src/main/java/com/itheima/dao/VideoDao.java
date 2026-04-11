package com.itheima.dao;

import com.itheima.pojo.Video;
import com.itheima.pojo.VideoResult;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class VideoDao {
    //Dao层只关心如何执行sql，控制事务由Service进行
    //一个类只干一件事，比如这个类只操作数据库中的video、videoInfo表，如果干了别的事就要放到别的类里面解耦合


    //增
    //添加视频基本信息
    public static long addVideoInfo(Connection conn,long uploadID, String videoTitle, String intro) throws SQLException {
        String sql = "insert into videoInfo (uploadID, videoTitle, briefIntroduction) values (?, ?, ?)";
        try(PreparedStatement pstmt=conn.prepareStatement(sql);) {//自动关闭pstmt
            pstmt.setLong(1, uploadID);
            pstmt.setString(2, videoTitle);
            pstmt.setString(3, intro);

            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {//try两次，自动关闭rs
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    throw new SQLException("获取 videoID 失败");
                }
            }
        }
    }

    //添加视频内容
    public static void addVideo(Connection conn,String videoUrl,long videoID) throws SQLException {
        String sql = "insert into video(videoID, video_url) VALUES (?, ?)";
        try (PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setLong(1,videoID);
            pstmt.setString(2,videoUrl);
            pstmt.executeUpdate();
        }

    }

    //删
    //根据视频ID删除视频基本信息
    public static int deleteVideoInfo(Connection conn,long videoID) throws SQLException{
        String sql = "delete from videoInfo where videoID=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, videoID);
            return pstmt.executeUpdate();
        }
    }
    //根据视频ID删除视频url
    public static int deleteVideo(Connection conn,long videoID) throws SQLException{
        String sql = "delete from video where videoID=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, videoID);
            return pstmt.executeUpdate();
        }
    }

    //查
    //根据视频ID查询视频基本信息
    public static Video findVideoInfo(Connection conn,long videoID) throws SQLException{
        String sql="select * from videoInfo where videoID=?";
        try (PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setLong(1,videoID);
            ResultSet rs=pstmt.executeQuery();
            if (rs.next()){
                return ResultMap.mapResultToVideoInfo(rs);
            }
            else{
                return null;
            }
        }
    }

    //根据视频ID查询视频内容（实际上只有视频url）
    public static String findVideo(Connection conn,long VideoID) throws SQLException {
        String sql = "select videoID,video_url from video where videoID=?";

        PreparedStatement pstmt = conn.prepareStatement(sql);
        pstmt.setLong(1, VideoID);

        try (ResultSet res = pstmt.executeQuery(); ) {
            if (res.next()) {//如果查得到结果
                return res.getString("video_url");
            } else {
                return null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    //查询所有视频基本信息
    public static List<Video> findAllVideoInfo(Connection conn) throws SQLException {
        List<Video> VideoList = new ArrayList<>();
        //面向接口：后续要改类型只用改new的类型
        String sql = "select * from videoInfo";
        try {
            PreparedStatement pstmt = conn.prepareStatement(sql);
            ResultSet res = pstmt.executeQuery();

            while (res.next()) {
                Video video = ResultMap.mapResultToVideoInfo(res);
                VideoList.add(video);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return VideoList;
    }

    //根据创作者id获取视频基本信息
    public static List<Video> findVideoInfoByUploadID(Connection conn, long uploadID) throws SQLException {
        List<Video> videoList = new ArrayList<>();
        String sql = "select * from videoInfo where uploadID = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, uploadID);
            try (ResultSet res = pstmt.executeQuery()) {
                while (res.next()) {
                    videoList.add(ResultMap.mapResultToVideoInfo(res));
                }
            }
        }
        return videoList;
    }

    //根据视频标题或作者名，模糊查询视频基本信息+视频内容
    public static List<VideoResult> searchVideo(Connection conn,String keyword) {
        String sql = """
        SELECT 
            vInfo.videoTitle,
            vInfo.briefIntroduction,
            v.video_url,
            u.username
        FROM videoInfo vInfo  
        JOIN userTable u ON vInfo.uploadID = u.id
        JOIN video v ON vInfo.videoID = v.videoID
        WHERE 
            vInfo.videoTitle LIKE ? 
            OR u.username LIKE ?;
    """;//给videoInfo,userTable,video起了别名
        //三个双引号是java15的新特性：多行字符串

        List<VideoResult> list = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String likeKeyword = keyword.trim() + "%";//trim()删除首尾的空格
            //只有右边有百分号，支持索引查询，暂时没有在表中加索引，后面再升级
            pstmt.setString(1, likeKeyword);
            pstmt.setString(2, likeKeyword);

            try(ResultSet rs = pstmt.executeQuery();){
                while (rs.next()) {
                    VideoResult vr = ResultMap.mapResultToVideoResult(rs);
                    list.add(vr);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    //更新视频标题或简介
    public static int updateVideoInfo(Connection conn,long videoID, String videoTitle, String intro)throws SQLException{
        String sql="update videoInfo set videoTitle=?,briefIntroduction=? where videoID=?";
        int rows;
        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,videoTitle);
            pstmt.setString(2,intro);
            pstmt.setLong(3,videoID);
            rows=pstmt.executeUpdate();
        }
        return rows;
    }

    //视频换源
    public static int updateVideo(Connection conn,long videoID,String videoUrl) throws SQLException {
        String sql="update video set video_url=? where videoID=?";
        int rows;
        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,videoUrl);
            pstmt.setLong(2,videoID);
            rows=pstmt.executeUpdate();
        }
        return rows;
    }
    



    
}





