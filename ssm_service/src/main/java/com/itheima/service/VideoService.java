package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.VideoDao;
import com.itheima.pojo.Comment;
import com.itheima.pojo.User;
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
    private VideoDao videoDao=new VideoDao();
    private CommentDao commentDao=new CommentDao();

    private static List<Video> videoInfoList=new ArrayList<>();
    private static long size;
    private static Random r=new Random();

    private static VideoService instance=new VideoService();
    public static VideoService getInstance(){
        return instance;
    }

    private VideoService(){
        init();
    }

    // ===== 初始化 =====
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            System.err.println("初始化视频缓存失败！");
            e.printStackTrace();
            throw e;
        }         //启动时加载一次
        startScheduler();   //开启定时刷新
    }

    public void refresh(){
        try {
            List<Video> newVideoList= videoDao.findAllVideoInfo();
            videoInfoList=newVideoList;
        }catch (Exception e){
            throw new RuntimeException("FAIL_TO_REFRESH",e);
        }
    }
    public void startScheduler() {
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



    public List<Video> getVideoList() {
        return videoInfoList;
    }

    // ===== 查询 =====
    public Video getVideoById(long videoId) {
        for (Video video:videoInfoList){
            if (video.getVideoId()==videoId){
                return video;
            }
        }
        return null;
    }

    public  VideoDetail getVideoDetail(long videoId) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            VideoDetail vd=videoDao.findVideo(conn,videoId);
//            List<Comment> commentList= commentDao.findCommentsByVideo(conn,videoId);
//            vd.setCommentList(commentList);
            return vd;
        }catch (SQLException e){
            throw new RuntimeException("FAIL_TO_GET_VIDEO_DETAIL", e);
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<Video> search(String keyword) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            return new ArrayList<>(videoDao.searchVideoInfo(conn, keyword));
        }catch (SQLException e){
            throw new RuntimeException("FAIL_TO_GET_VIDEO_DETAIL", e);
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    //推流
    public List<Video> getRecommendedVideos(int limit) {//推荐limit条视频

        List<Video> list = new ArrayList<>(videoInfoList);

        // 打乱顺序（随机）
        Collections.shuffle(list);

        // 取limit和list.size的最小值防止越界
        int size = Math.min(limit, list.size());

        return list.subList(0, size);
    }

    public Video getNextVideo(List<Video> list) {
        int randomNum=r.nextInt(list.size());
        return list.get(randomNum);
    }

    // ===== 管理 =====
    public void addVideo(VideoDetail video, User user) {
        Connection conn = null;
        String title = video.getTitle();
        String intro = video.getIntro();
        String url = video.getUrl();
        if (videoCheck(title, intro, url)) {
            try {
                conn = MyConnectionPool.getConnection();
                conn.setAutoCommit(false);

                long videoId = videoDao.addVideoInfo(conn, user.getId(), title, intro);//添加视频后获取id
                videoDao.addVideo(conn, url, videoId);
                conn.commit();//提交提交提交提交


            } catch (SQLException e) {
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
        else {
            throw new IllegalArgumentException("VIDEO_INPUT_ILLEGAL");
        }
    }

    public void deleteVideo(long videoId) { }


    //输入的视频是否合法
    public boolean videoCheck(String title,String intro,String url){
        if(title==null||title.length()>50){
            return false;
        }
        if (intro!=null&&intro.length()>500){
            return false;
        }
        if(url==null||url.length()>500){
            return false;
        }
        return !title.trim().isEmpty();
    }
}
