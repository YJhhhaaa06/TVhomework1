package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.pojo.Comment;
import com.itheima.pojo.ContentDetailVO;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommentService {

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
            conn.commit();
        }catch (SQLException e) {
            if(conn!=null){
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("评论写入失败");
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
                commentDao.hideCommentByContent(conn,videoId);
            }
            else {
                commentDao.unhideCommentByContent(conn,videoId);
            }

            conn.commit();
        }catch (SQLException e) {
            if(conn!=null){
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("评论区操作失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    //查


    private   List<Comment> findComment(long videoId) throws SQLException {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            List<Comment> commentList=commentDao.getComments(conn,videoId);
            return commentList;

        }catch (SQLException e){
            conn=null;
            throw e;
        } finally {
                MyConnectionPool.release(conn);
        }

    }

    //获取评论数量
    public  int getCommentCount(ContentDetailVO cdVO){
        List<Comment> wholeList=cdVO.getCommentList();
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
    private   boolean isCommentLegal(long userId,long videoId,String content){
        if(userId==0||videoId==0){
            return false;
        }
        if(content.length()>2000){
            return false;
        }
        return true;
    }


    //外界真正调用的
    //获取评论并整理评论，返回一级评论
    public List<Comment> getComment(long contentId){
        try {
            List<Comment> wholeList = findComment(contentId);
            return buildCommentTree(wholeList);
        }catch (SQLException e){
            e.printStackTrace();
            throw new NotFoundException("FAIL_TO_GET_COMMENT,VIDEO_ID="+contentId);
        }
    }






}
