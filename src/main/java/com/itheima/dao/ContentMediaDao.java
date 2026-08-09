package com.itheima.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.pojo.ContentMedia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContentMediaDao {

    //添加content的封面、视频、图片
    public void addMedia(Connection conn,long contentId,String url,int type,int sort) throws SQLException {
        String sql = "insert into content_media(content_id,url,type,sort) VALUES (?, ?,?,?)";
        try (PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setLong(1,contentId);
            pstmt.setString(2,url);
            pstmt.setInt(3,type);
            pstmt.setInt(4,sort);
            pstmt.executeUpdate();
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




    // 查询全部媒体（运维扫描用）
    public List<ContentMedia> findAllMedia(Connection conn) throws SQLException {
        String sql = "select id,content_id,url,type,sort from content_media order by id";
        List<ContentMedia> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                list.add(ResultMap.buildContentMedia(rs));
            }
        }
        return list;
    }

    // 按 media id 查询单条媒体（运维恢复用）
    public ContentMedia findMediaById(Connection conn, long mediaId) throws SQLException {
        String sql = "select id,content_id,url,type,sort from content_media where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, mediaId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildContentMedia(rs);
                }
                return null;
            }
        }
    }

    // 更新单条媒体的文件存在状态
    public int updateFileExists(Connection conn, long mediaId, boolean exists, Timestamp lastVerifyTime) throws SQLException {
        String sql = "update content_media set file_exists=?, last_verify_time=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, exists ? 1 : 0);
            pstmt.setTimestamp(2, lastVerifyTime);
            pstmt.setLong(3, mediaId);
            return pstmt.executeUpdate();
        }
    }







}
