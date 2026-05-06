package com.itheima.service;

import com.itheima.command.CommentCommand;
import com.itheima.dao.CommentDao;
import com.itheima.exception.*;
import com.itheima.pojo.CommentVO;
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
    public  List<CommentVO> buildCommentTree(List<CommentVO> list) {
        Map<Long, CommentVO> map = new HashMap<>();
        List<CommentVO> roots = new ArrayList<>();

        //先把所有评论放进 map，并初始化 children
        for (CommentVO c : list) {
            c.setChildren(new ArrayList<>());
            map.put(c.getCommentId(), c);
        }

        //建立父子关系
        for (CommentVO c : list) {
            Long parentId = c.getParentId();

            if (parentId == null||parentId==0) {
                roots.add(c); // 一级评论
            } else {
                CommentVO parent = map.get(parentId);
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
    public  void addComment(CommentCommand commentCommand){
        long userId=commentCommand.getUserId();
        long contentId=commentCommand.getContentId();
        Long parentId=commentCommand.getParentId();
        String message=commentCommand.getMessage();

        Connection conn=null;
        try {
            conn= MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            if(parentId!=null&&parentId!=0){
                if(!commentDao.isParentIdCorrect(conn,parentId,contentId)){
                    throw new ConflictException("被回复评论不属于该视频或动态");
                }
            }
            commentDao.addComment(conn,contentId,userId,message,parentId);
            conn.commit();
        }catch (SQLException e) {
            e.printStackTrace();
            if(conn!=null){
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("评论写入失败");
        } finally {
            MyConnectionPool.release(conn);
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


    private   List<CommentVO> findComment(long videoId) throws SQLException {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            List<CommentVO> commentVOList =commentDao.getComments(conn,videoId);
            return commentVOList;

        }catch (SQLException e){
            conn=null;
            throw e;
        } finally {
                MyConnectionPool.release(conn);
        }

    }

    //获取评论数量
    public  int getCommentCount(ContentDetailVO cdVO){
        List<CommentVO> wholeList=cdVO.getCommentList();
        return wholeList.size();
    }
    //获取回复数量
    public  int countReplies(CommentVO commentVO) {
        int count = 0;
        List<CommentVO> children = commentVO.getChildren();
        if (children == null || children.isEmpty()) {
            return 0;
        }
        for (CommentVO child : children) {
            count += 1; // 当前子评论
            count += countReplies(child); // 子评论的子评论
        }

        return count;
    }

    //改

    //写评论检查
    private   boolean isCommentLegal(long userId,long contentId,String content){
        if(userId==0||contentId==0){
            return false;
        }
        if(content.length()>2000){
            return false;
        }
        return true;
    }


    //外界真正调用的
    //获取评论并整理评论，返回一级评论
    public List<CommentVO> getComment(long contentId){
        try {
            List<CommentVO> wholeList = findComment(contentId);
            return buildCommentTree(wholeList);
        }catch (SQLException e){
            e.printStackTrace();
            throw new NotFoundException("FAIL_TO_GET_COMMENT,VIDEO_ID="+contentId);
        }
    }






}
