package com.itheima.dao;

import com.itheima.pojo.ContentMedia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ContentMediaDao {




    //添加content的封面、视频、图片
    public void addMedia(Connection conn,long contentId,String url,int type,int sort) throws SQLException {
        String sql = "insert into content(content_id,url,type,sort) VALUES (?, ?,?,?)";
        try (PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setLong(1,contentId);
            pstmt.setString(2,url);
            pstmt.setInt(3,type);
            pstmt.setInt(4,sort);
            pstmt.executeUpdate();
        }
    }

    //删除视频所有url
    public int deleteContentMedia(Connection conn,long contentId) throws SQLException{
        String sql = "delete from content_media where content_id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
        }
    }

    //根据id删除url
    public int deleteSpecificContentMedia(Connection conn,long mediaId) throws SQLException{
        String sql = "delete from content_media where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, mediaId);
            return pstmt.executeUpdate();
        }
    }

    //根据contentId,type和sort删除
    public int deleteSpecificContentMedia(Connection conn,long contentId,int type,int sort) throws SQLException{
        String sql = "delete from content_media where content_id=? and type=? and sort=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            pstmt.setInt(2,type);
            pstmt.setInt(3,sort);
            return pstmt.executeUpdate();
        }
    }


    //根据contentId查询所有media
    public Map<Integer,List<ContentMedia>> findMedia(Connection conn,long contentId)throws SQLException{
        String sql = "select id,content_id,url,type,sort from content_media where content_id=? order by type,sort";
        Map<Integer, List<ContentMedia>> map=new HashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            try(ResultSet rs= pstmt.executeQuery()){
                while (rs.next()){
                    int type= rs.getInt("type");
                    map.computeIfAbsent(type, k -> new ArrayList<>())  //如果 map 中不存在这个 type，就创建一个新的 ArrayList 并放进去
                            .add(ResultMap.buildContentMedia(rs));//；不管是否新建，都会返回这个 type 对应的 List，然后往这个 List 里 add 数据。
                    //k就是Key,但这里没有用到key
                }
            }
        }
        return map;
    }




    //换源
    public int updateMedia(Connection conn,long contentId,int type,int sort, String url) throws SQLException {
        String sql="update video set url=? where content_id=? and type=? and sort=?";
        int rows;
        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,url);
            pstmt.setLong(2,contentId);
            pstmt.setInt(3,type);
            pstmt.setInt(4,sort);
            rows=pstmt.executeUpdate();
        }
        return rows;
    }






}
