package com.itheima.dao;

import com.itheima.pojo.ContentDetailVO;
import com.itheima.pojo.RecommendVO;
import com.itheima.util.MyConnectionPool;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ContentDao {
    //Dao层只关心如何执行sql，控制事务由Service进行
    //一个类只干一件事，比如这个类只操作数据库中的video、videoInfo表，如果干了别的事就要放到别的类里面解耦合


    //增
    //添加内容基本信息
    public long addContent(Connection conn, long userId,int type, String title, String description ,int categoryId) throws SQLException {
        String sql = "insert into content (user_id, title,type, description,category_id) values (?, ?, ?,?,?)";
        try(PreparedStatement pstmt=conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {//返回视频或动态id
            pstmt.setLong(1, userId);
            pstmt.setString(2, title);
            pstmt.setInt(3,type);
            pstmt.setString(4, description);
            pstmt.setInt(5, categoryId);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {//try两次，自动关闭rs
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    throw new SQLException("获取 contentID 失败");
                }
            }
        }
    }



    //删
    //根据视频ID删除内容基本信息
    public int deleteContent(Connection conn,long contentId) throws SQLException{
        String sql = "delete from content where content_id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
        }
    }

    public int hideContent(Connection conn,long contentId)throws SQLException{
        String sql = "UPDATE content SET is_deleted = 1 WHERE content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
        }
    }

    public int unhideContent(Connection conn,long contentId)throws SQLException{
        String sql = "UPDATE content SET is_deleted = 0 WHERE content_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            return pstmt.executeUpdate();
        }
    }


    //查
    //content是否存在
    public boolean isContentExist(Connection conn,long contentId)throws SQLException {
        String sql = "SELECT COUNT(*) " +
                "FROM content " +
                "WHERE id = ? " +
                "  AND is_deleted = 0";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    // 数量>0：content存在
                    return count > 0;
                }
            }
            return false;
        }
    }

    //根据ID查询视频和动态
    public ContentDetailVO findContent(Connection conn, long contentId) throws SQLException{
        String sql= """
SELECT 
           c.id,
           c.title,
           c.description,
           c.type,
           c.category_id,
           c.comment_count,
           c.like_count,
           c.create_time,
           u.username,
           u.id AS user_id 
       FROM content c 
       JOIN users u ON c.user_id = u.id 
       where c.id=? AND is_deleted =0 
""";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)){
            pstmt.setLong(1, contentId);
            try (ResultSet rs = pstmt.executeQuery()){
                if (rs.next()){
                    return ResultMap.buildContent(rs);
                } else {
                    return null;
                }
            }
        }
    }




    //查询所有视频基本信息
    public List<RecommendVO> findAllContent(Connection conn) throws SQLException {
        List<RecommendVO> list = new ArrayList<>();
        //面向接口：后续要改类型只用改new的类型
        String sql = """
        SELECT 
           c.id,
           c.title,
           c.description,
           c.type,
           c.category_id,
           c.comment_count,
           c.like_count,
           c.create_time,
           u.username,
           u.id AS user_id 
       FROM content c 
       JOIN users u ON c.user_id = u.id 
       where is_deleted =0 
       ORDER BY c.create_time DESC, c.id DESC  
""";
            try (PreparedStatement pstmt = conn.prepareStatement(sql);

                 ResultSet res = pstmt.executeQuery()){
                while (res.next()) {
                    com.itheima.pojo.RecommendVO recommendVO = ResultMap.buildRecommendVO(res);
                    list.add(recommendVO);
                }
            }
        return list;
    }

    public List<RecommendVO> findAllContent() throws SQLException {
        Connection conn=null;
        try{
            conn = MyConnectionPool.getConnection();
            return findAllContent(conn);

        }finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<ContentDetailVO> findAllContentDetail() throws SQLException {
        Connection conn=null;
        try{
            conn = MyConnectionPool.getConnection();
            return findAllContentDetail(conn);

        }finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<ContentDetailVO> findAllContentDetail(Connection conn) throws SQLException {
        List<ContentDetailVO> list = new ArrayList<>();
        //面向接口：后续要改类型只用改new的类型
        String sql = """
        SELECT 
           c.id,
           c.title,
           c.description,
           c.type,
           c.category_id,
           c.comment_count,
           c.like_count,
           c.create_time,
           u.username,
           u.id AS user_id 
       FROM content c 
       JOIN users u ON c.user_id = u.id 
       where is_deleted =0 
       ORDER BY c.create_time DESC, c.id DESC  
""";
        try (PreparedStatement pstmt = conn.prepareStatement(sql);

             ResultSet rs = pstmt.executeQuery()){
            while (rs.next()) {
                list.add(ResultMap.buildContent(rs));
            }
        }
        return list;
    }



    //根据创作者id查视频和动态
    public List<ContentDetailVO> findContentByUser(Connection conn, long userId, int page, int pageSize) throws SQLException {
        List<ContentDetailVO> list= new ArrayList<>();
        String sql = """
        SELECT 
           c.id,
           c.title,
           c.description,
           c.type,
           c.category_id,
           c.comment_count,
           c.like_count,
           c.create_time,
           u.username,
           u.id AS user_id 
       FROM content c 
       JOIN users u ON c.user_id = u.id 
       where c.user_id=? AND is_deleted =0 
       ORDER BY c.create_time DESC, c.id DESC  
       LIMIT ?,?

""";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, userId);
                int offset=(page-1)*pageSize;
                pstmt.setInt(2,offset);
                pstmt.setInt(3,pageSize);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(ResultMap.buildContent(rs));
                    }
                }
            }
        return list;
    }

    //根据视频标题或描述，模糊查询内容(不含media),不支持用户名模糊查询，一次只查pageSize条
    public List<ContentDetailVO> search(Connection conn, String keyword, int page, int pageSize)throws SQLException {
        String sql = """
       SELECT
           c.id,
           c.title,
           c.description,
           c.type,
           c.category_id,
           c.comment_count,
           c.like_count,
           c.create_time,
           u.username,
           u.id AS user_id,
           MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE) AS score
       FROM content c
       JOIN users u ON c.user_id = u.id
       WHERE
           MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE)
       ORDER BY score DESC, c.create_time DESC
       LIMIT ?,?;
       
    """;
        //三个双引号是java15的新特性：多行字符串
        List<ContentDetailVO> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String likeKeyword = keyword.trim() ;//trim()删除首尾的空格
            pstmt.setString(1, likeKeyword);
            pstmt.setString(2, likeKeyword);
            int offset=(page-1)*pageSize;
            pstmt.setInt(3,offset);
            pstmt.setInt(4,pageSize);
            try (ResultSet rs = pstmt.executeQuery()){
                while (rs.next()) {
                    ContentDetailVO contentDetailVO = ResultMap.buildContent(rs);
                    list.add(contentDetailVO);
                }
            }
        }
        return list;
    }




    //改

    //更新视频标题或简介
    public int updateContentInfo(Connection conn,long contentId, String title, String description)throws SQLException{
        String sql="update content set title=?,description=? where id=?";
        int rows;
        try(PreparedStatement pstmt=conn.prepareStatement(sql)){
            pstmt.setString(1,title);
            pstmt.setString(2,description);
            pstmt.setLong(3,contentId);
            rows=pstmt.executeUpdate();
        }
        return rows;
    }

    public int getLikeCount(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT like_count FROM content WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    //更新点赞数量
    public void updateLikeCount(Connection conn, Long contentId, int delta) throws SQLException {
        String sql = "UPDATE content SET like_count = like_count + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, contentId);
            ps.executeUpdate();
        }
    }


}
