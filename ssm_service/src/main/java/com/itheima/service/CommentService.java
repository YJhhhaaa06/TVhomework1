package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.pojo.Comment;
import com.itheima.pojo.User;
import com.itheima.pojo.Video;
import com.itheima.pojo.VideoDetail;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommentService {

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

            if (parentId == null) {
                roots.add(c); // 一级评论
            } else {
                Comment parent = map.get(parentId);
                if (parent != null) {
                    parent.getChildren().add(c);
                }
            }
        }

        return roots;
    }

    //增
    //打包评论
    public  Comment packComment(User user,Video video,Comment parent,String content){

        long userId=user.getId();
        long videoId= video.getVideoId();
        Long parentId;
        if(!isCommentLegal(userId,videoId,content)){
            throw new RuntimeException("WRONG_INPUT");
        }
        if(parent==null){
            parentId=null;
        }
        else {
            parentId=parent.getCommentId();
        }
        Comment result=new Comment();
        result.setUserId(userId);
        result.setVideoId(videoId);
        result.setParentId(parentId);
        result.setContent(content);
        return result;

    }
    //写入评论到数据库
    public  void writeComment(Comment comment){
        long userId=comment.getUserId();
        long videoId=comment.getVideoId();
        String content=comment.getContent();
        Long parentId= comment.getParentId();
        Connection conn=null;
        try {
            conn= MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            CommentDao.addComment(conn,videoId,userId,content,parentId);
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
                CommentDao.hideCommentByVideo(conn,videoId);
            }
            else {
                CommentDao.unhideCommentByVideo(conn,videoId);
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
    //不需要了，videoService查询视频详细信息会自动获取评论区
//    public static void findComment(Connection conn,long videoId) throws SQLException {
//        List<Comment> commentList=CommentDao.findCommentsByVideo(conn,videoId);
//    }

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


}
