package com.itheima.dao;

import com.itheima.pojo.Content;
import com.itheima.util.MyConnectionPool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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
    //根据视频ID查询视频和动态
    public Content findContent(Connection conn,long contentId) throws SQLException{
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
    public List<Content> findAllContent(Connection conn) throws SQLException {
        List<Content> list = new ArrayList<>();
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
                    com.itheima.pojo.Content content = ResultMap.buildContent(res);
                    list.add(content);
                }
            }
        return list;
    }

    public List<Content> findAllContent() throws SQLException {
        Connection conn=null;
        try{
            conn = MyConnectionPool.getConnection();
            return findAllContent(conn);

        }finally {
            MyConnectionPool.release(conn);
        }

    }


    //根据创作者id查视频和动态
    public List<Content> findContentByUser(Connection conn,long userId,int page, int pageSize) throws SQLException {
        List<Content> list= new ArrayList<>();
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
    public List<Content> search(Connection conn, String keyword, int page, int pageSize)throws SQLException {
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
        List<Content> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String likeKeyword = keyword.trim() ;//trim()删除首尾的空格
            pstmt.setString(1, likeKeyword);
            pstmt.setString(2, likeKeyword);
            int offset=(page-1)*pageSize;
            pstmt.setInt(3,offset);
            pstmt.setInt(4,pageSize);
            try (ResultSet rs = pstmt.executeQuery()){
                while (rs.next()) {
                    com.itheima.pojo.Content content = ResultMap.buildContent(rs);
                    list.add(content);
                }
            }
        }
        return list;
    }


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




}
