package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.VideoDao;
import com.itheima.pojo.Comment;
import com.itheima.pojo.Video;
import com.itheima.pojo.VideoDetail;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VideoService {
    private static List<Video> videoInfoList=new ArrayList<>();
    private static long size;
    private static Random r=new Random();


    // ===== 初始化 =====
    public static void init() {
        try {
            refresh();
        } catch (Exception e) {
            System.err.println("初始化视频缓存失败！");
            e.printStackTrace();
            throw e;
        }         //启动时加载一次
        startScheduler();   //开启定时刷新
    }

    public static void refresh(){
        try {
            List<Video> newVideoList= VideoDao.findAllVideoInfo();
            videoInfoList=newVideoList;
        }catch (Exception e){
            throw new RuntimeException("FAIL_TO_REFRESH",e);
        }
    }
    public static void startScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // 参数：任务, 首次执行延迟, 连续执行间隔, 时间单位
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refresh();
            } catch (Exception e) {
                e.printStackTrace(); // 防止异常导致调度终止
            }
        }, 0, 1, TimeUnit.MINUTES);
    }



    public static List<Video> getVideoList() {
        return videoInfoList;
    }

    // ===== 查询 =====
    public static Video getVideoById(long videoId) {
        for (Video video:videoInfoList){
            if (video.getVideoID()==videoId){
                return video;
            }
        }
        return null;
    }

    public static VideoDetail getVideoDetail(long videoId) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            VideoDetail vd=VideoDao.findVideo(conn,videoId);
            List<Comment> commentList= CommentDao.findCommentsByVideo(conn,videoId);
            vd.setCommentList(commentList);
            return vd;
        }catch (SQLException e){
            throw new RuntimeException("FAIL_TO_GET_VIDEO_DETAIL", e);
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    public static List<Video> search(String keyword) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            return new ArrayList<>(VideoDao.searchVideoInfo(conn, keyword));
        }catch (SQLException e){
            throw new RuntimeException("FAIL_TO_GET_VIDEO_DETAIL", e);
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    //推流
    public static List<Video> getRecommendedVideos(int limit) {//推荐limit条视频

        List<Video> list = new ArrayList<>(videoInfoList);

        // 打乱顺序（随机）
        Collections.shuffle(list);

        // 取limit和list.size的最小值防止越界
        int size = Math.min(limit, list.size());

        return list.subList(0, size);
    }

    public static Video getNextVideo(List<Video> list) {
        int randomNum=r.nextInt(list.size());
        return list.get(randomNum);
    }

    // ===== 管理 =====
    public static void addVideo(Video video) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
        }catch (SQLException e){
            try{
                conn.rollback();
            }catch (SQLException e1){
                throw new RuntimeException("");
            }
        }
        finally {
            if(conn!=null){
                try{
                conn.setAutoCommit(true);
                }catch (Exception ex){
                    throw new RuntimeException("");
                }
            }
            MyConnectionPool.release(conn);
        }
    }

    public static void deleteVideo(long videoId) { }


}
