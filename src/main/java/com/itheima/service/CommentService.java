package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.pojo.Comment;
import com.itheima.pojo.VideoDetail;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommentService {

    static {
        System.out.println("进入");

    }
private CommentDao commentDao=new CommentDao();


    //建立索引关系
    //返回一级评论列表
    //map只用一次，就构建好了
    public  List<Comment> buildCommentTree(List<Comment> list) {
        Map<Long, Comment> map = new HashMap<>();
        List<Comment> roots = new ArrayList<>();

        //先把所有评论放进 map，并初始化 children
        for (Comment c : list) {
            c.setChildren(new ArrayList<>());
            map.put(c.getCommentId(), c);
        }

        //建立父子关系
        for (Comment c : list) {
            Long parentId = c.getParentId();

            if (parentId == null||parentId==0) {
                roots.add(c); // 一级评论
            } else {
                Comment parent = map.get(parentId);
                if (parent != null) {
                    parent.getChildren().add(c);
                }
            }
        }
        System.out.println(roots.size()+"条评论");

        return roots;
    }

    //增

    //写入评论到数据库
    public  void addComment(long userId,long videoId,Long parentId,String content){
        if(!isCommentLegal(userId,videoId,content)){
            throw new RuntimeException("WRONG_INPUT");
        }
        Connection conn=null;
        try {
            conn= MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            commentDao.addComment(conn,videoId,userId,content,parentId);
            conn.commit();//
            //提交
        }catch (SQLException e) {
            if(conn!=null){
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new RuntimeException("DB_ERROR",ex);
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {//这里说明连接可能已经异常
                    e.printStackTrace();
                    conn = null;//标记这个连接不可用
                } finally {
                    //无论如何都要执行
                    MyConnectionPool.release(conn);
                }
            }
        }


    }

    //删
    public  void hideComment(long videoId){
        hideOrUnhideComment(videoId,true);
    }
    public  void unhideComment(long videoId){
        hideOrUnhideComment(videoId,false);
    }
    //关闭评论区或开启评论区
    //choose为true时关闭，为false开启
    public  void hideOrUnhideComment(long videoId,boolean choose){
        Connection conn=null;
        try {
            conn= MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            if(choose){
                commentDao.hideCommentByVideo(conn,videoId);
            }
            else {
                commentDao.unhideCommentByVideo(conn,videoId);
            }

            conn.commit();//
            //提交
        }catch (SQLException e) {
            if(conn!=null){
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new RuntimeException("DB_ERROR",ex);
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    //这里说明连接可能已经异常
                    e.printStackTrace();
                    //标记这个连接不可用
                    conn = null;
                } finally {
                    //无论如何都要执行
                    MyConnectionPool.release(conn);
                }
            }
        }
    }

    //查
    //查询视频的所有评论
    //videoService查询视频详细信息会自动获取评论区
    public  List<Comment> findComment(Connection conn,long videoId) throws SQLException {
        List<Comment> commentList=commentDao.getCommentsByVideoId(conn,videoId);
        return commentList;
    }
    public  List<Comment> findComment(long videoId) throws SQLException {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            List<Comment> commentList=commentDao.getCommentsByVideoId(conn,videoId);
            return commentList;

        }catch (SQLException e){
            conn=null;
            throw e;
        } finally {
                MyConnectionPool.release(conn);
        }

    }

    //获取评论数量
    public  int getCommentCount(VideoDetail vd){
        List<Comment> wholeList=vd.getCommentList();
        return wholeList.size();
    }
    //获取回复数量
    public  int countReplies(Comment comment) {
        int count = 0;
        List<Comment> children = comment.getChildren();
        if (children == null || children.isEmpty()) {
            return 0;
        }
        for (Comment child : children) {
            count += 1; // 当前子评论
            count += countReplies(child); // 子评论的子评论
        }

        return count;
    }

    //改

    //写评论检查
    public  boolean isCommentLegal(long userId,long videoId,String content){
        if(userId==0||videoId==0){
            return false;
        }
        if(content.length()>2000){
            return false;
        }
        return true;
    }

    //获取评论并整理评论，返回一级评论
    public List<Comment> getComment(long videoId){
        try {
            List<Comment> wholeList = findComment(videoId);
            return buildCommentTree(wholeList);
        }catch (SQLException e){
            e.printStackTrace();
            throw new RuntimeException("FAIL_TO_GET_COMMENT,VIDEO_ID="+videoId,e);
        }
    }






}
