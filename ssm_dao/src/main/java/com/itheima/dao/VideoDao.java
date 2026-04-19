package com.itheima.dao;

import com.itheima.pojo.Video;
import com.itheima.pojo.VideoDetail;

import java.sql.*;
import com.itheima.util.MyConnectionPool;
import java.util.ArrayList;
import java.util.List;

public class VideoDao {
    //Dao层只关心如何执行sql，控制事务由Service进行
    //一个类只干一件事，比如这个类只操作数据库中的video、videoInfo表，如果干了别的事就要放到别的类里面解耦合


    //增
    //添加视频基本信息
    public long addVideoInfo(Connection conn,long uploadID, String videoTitle, String intro) throws SQLException {
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
    public void addVideo(Connection conn,String videoUrl,long videoID) throws SQLException {
        String sql = "insert into video(videoID, video_url) VALUES (?, ?)";
        try (PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setLong(1,videoID);
            pstmt.setString(2,videoUrl);
            pstmt.executeUpdate();
        }

    }

    //删
    //根据视频ID删除视频基本信息
    public int deleteVideoInfo(Connection conn,long videoID) throws SQLException{
        String sql = "delete from videoInfo where videoID=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, videoID);
            return pstmt.executeUpdate();
        }
    }
    //根据视频ID删除视频url
    public int deleteVideo(Connection conn,long videoID) throws SQLException{
        String sql = "delete from video where videoID=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, videoID);
            return pstmt.executeUpdate();
        }
    }

    //查
    //根据视频ID查询视频基本信息
    public Video findVideoInfo(long videoID) throws SQLException{
        String sql="select * from videoInfo where videoID=?";
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)){
                pstmt.setLong(1, videoID);
                try (ResultSet rs = pstmt.executeQuery()){
                    if (rs.next()){
                        return ResultMap.mapResultToVideoInfo(rs);
                    } else {
                        return null;
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    //根据视频ID查询视频内容（实际上只有视频url）
    public VideoDetail findVideo(Connection conn,long VideoID) throws SQLException {
        String sql = """
        SELECT 
            vInfo.videoTitle,
            vInfo.briefIntroduction,
            v.video_url,
            u.username,
            u.id,
            v.videoID
        FROM videoInfo vInfo  
        JOIN users u ON vInfo.uploadID = u.id
        JOIN video v ON vInfo.videoID = v.videoID
        WHERE 
            vInfo.videoID=?
          """;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)){
                pstmt.setLong(1, VideoID);
                try (ResultSet rs = pstmt.executeQuery()){
                    if (rs.next()) {//如果查得到结果
                        return ResultMap.mapResultToVideoDetail(rs);
                    } else {
                        return null;
                    }
                }
            }
    }

    //查询所有视频基本信息
    public List<Video> findAllVideoInfo() throws SQLException {
        List<Video> VideoList = new ArrayList<>();
        //面向接口：后续要改类型只用改new的类型
        String sql = "select * from videoInfo";
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet res = pstmt.executeQuery()){
                while (res.next()) {
                    Video video = ResultMap.mapResultToVideoInfo(res);
                    VideoList.add(video);
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
        return VideoList;
    }

    //根据创作者id获取视频基本信息
    public List<Video> findVideoInfoByUploadID(long uploadID) throws SQLException {
        List<Video> videoList = new ArrayList<>();
        String sql = "select * from videoInfo where uploadID = ?";
        java.sql.Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, uploadID);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        videoList.add(ResultMap.mapResultToVideoInfo(rs));
                    }
                }
            }
        } finally {
            MyConnectionPool.release(conn);
        }
        return videoList;
    }

    //根据视频标题或作者名，模糊查询视频基本信息+视频内容
    public List<VideoDetail> searchVideo(Connection conn,String keyword)throws SQLException {
        String sql = """
        SELECT 
            vInfo.videoTitle,
            vInfo.briefIntroduction,
            vInfo.videoID,
            v.video_url,
            u.username,
            u.id
        FROM videoInfo vInfo  
        JOIN users u ON vInfo.uploadID = u.id
        JOIN video v ON vInfo.videoID = v.videoID
        WHERE 
            vInfo.videoTitle LIKE ? 
            OR u.username LIKE ?;
    """;//给videoInfo,userTable,video起了别名
        //三个双引号是java15的新特性：多行字符串

        List<VideoDetail> list = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                String likeKeyword = "%"+keyword.trim() + "%";//trim()删除首尾的空格
                //只有右边有百分号，支持索引查询，暂时没有在表中加索引，后面再升级
                pstmt.setString(1, likeKeyword);
                pstmt.setString(2, likeKeyword);

                try (ResultSet rs = pstmt.executeQuery()){
                    while (rs.next()) {
                        VideoDetail vd = ResultMap.mapResultToVideoDetail(rs);
                        list.add(vd);
                    }
                }
            }
        return list;
    }

    //模糊查询，只获取视频基本信息
    //JOIN video v ON vInfo.videoID = v.videoID
    //VideoDetail和Comment绑定，为了确保用的是同一个connection，conn由调用者发放
    public List<Video> searchVideoInfo(Connection conn,String keyword)throws SQLException{
        String sql = """
        SELECT 
            u.username,
            vInfo.videoTitle,
            vInfo.briefIntroduction,
            vInfo.uploadID,
            vInfo.videoID
        FROM videoInfo vInfo  
        JOIN users u ON vInfo.uploadID = u.id
        WHERE 
            vInfo.videoTitle LIKE ? 
            OR u.username LIKE ?;
    """;
        List<Video> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String likeKeyword ="%"+ keyword.trim() + "%";//trim()删除首尾的空格
            //只有右边有百分号，支持索引查询，暂时没有在表中加索引，后面再升级
            pstmt.setString(1, likeKeyword);
            pstmt.setString(2, likeKeyword);

            try (ResultSet rs = pstmt.executeQuery()){
                while (rs.next()) {
                    Video video = ResultMap.mapResultToVideoInfo(rs);
                    list.add(video);
                }
            }
        }
        return list;
    }

    //更新视频标题或简介
    public int updateVideoInfo(Connection conn,long videoID, String videoTitle, String intro)throws SQLException{
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
    public int updateVideo(Connection conn,long videoID,String videoUrl) throws SQLException {
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





