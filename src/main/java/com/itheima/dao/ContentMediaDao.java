package com.itheima.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.model.entity.ContentMedia;

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

    // 按 contentId + type + sort 定位单条媒体（作者编辑作品：换源/删除前置）
    public ContentMedia findMediaByContentTypeSort(Connection conn, long contentId, int type, int sort) throws SQLException {
        String sql = "select id,content_id,url,type,sort from content_media where content_id=? and type=? and sort=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            pstmt.setInt(2, type);
            pstmt.setInt(3, sort);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return ResultMap.buildContentMedia(rs);
                }
                return null;
            }
        }
    }

    // 换源：更新单条媒体记录的 url 与文件存在状态
    public int updateMediaUrl(Connection conn, long mediaId, String url, boolean exists, Timestamp lastVerifyTime) throws SQLException {
        String sql = "update content_media set url=?, file_exists=?, last_verify_time=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, url);
            pstmt.setInt(2, exists ? 1 : 0);
            pstmt.setTimestamp(3, lastVerifyTime);
            pstmt.setLong(4, mediaId);
            return pstmt.executeUpdate();
        }
    }

    // 按媒体 id 删除媒体记录（B2 底座，阶段四 A1 复用）
    public int deleteMediaById(Connection conn, long mediaId) throws SQLException {
        String sql = "delete from content_media where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, mediaId);
            return pstmt.executeUpdate();
        }
    }

    // 按 contentId + type + sort 删除单条媒体记录（B2 底座，作者删错图用）
    public int deleteMediaByContentIdAndTypeSort(Connection conn, long contentId, int type, int sort) throws SQLException {
        String sql = "delete from content_media where content_id=? and type=? and sort=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            pstmt.setInt(2, type);
            pstmt.setInt(3, sort);
            return pstmt.executeUpdate();
        }
    }

    // 删除某张图后重排剩余图片的 sort，保持 1..n 连续（保证前端 index+1 定位成立）
    public int compactImageSort(Connection conn, long contentId, int deletedSort) throws SQLException {
        String sql = "update content_media set sort=sort-1 where content_id=? and type=2 and sort>?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            pstmt.setInt(2, deletedSort);
            return pstmt.executeUpdate();
        }
    }



}
