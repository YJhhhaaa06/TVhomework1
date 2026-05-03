package com.itheima.service;

import com.itheima.command.ContentType;
import com.itheima.command.UploadCommand;
import com.itheima.controller.UploadType;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ErrorCode;
import com.itheima.exception.ServerException;
import com.itheima.pojo.*;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ContentService {
    private ContentDao contentDao=new ContentDao();
    private CommentDao commentDao=new CommentDao();
    private ContentMediaDao contentMediaDao=new ContentMediaDao();

    private static List<Content> contentList=new ArrayList<>();
    private static long size;
    private static Random r=new Random();

    private static ContentService instance=new ContentService();
    public static ContentService getInstance(){
        return instance;
    }

    private ContentService(){
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
            List<Content> newContentList= contentDao.findAllContent();
            contentList=newContentList;
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



    public List<Content> getContentList() {
        return contentList;
    }

    // ===== 查询 =====





    public  Content getContentById(long contentId) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            Content content=contentDao.findContent(conn,contentId);
            List<Comment> commentList = commentDao.getComments(conn, content.getId());
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, content.getId());
            buildContent(content, mediaMap, commentList);
            return content;
        }catch (SQLException e){
            throw new ConflictException(e.getMessage());
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<Content> search(String keyword) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            List<Content> list=contentDao.search(conn,keyword,1,20);
            for (Content content : list) {
                List<Comment> commentList = commentDao.getComments(conn, content.getId());
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, content.getId());
                buildContent(content, mediaMap, commentList);
            }
            return list;
        }catch (SQLException e){
            throw new RuntimeException("FAIL_TO_GET_VIDEO_DETAIL", e);
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    //推流
    public List<Content> getRecommend(int limit) {//推荐limit条视频

        List<Content> list = new ArrayList<>(contentList);

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
    public void addVideo(UploadCommand uc, String videoUrl, String coverUrl) {
        Connection conn = null;

        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            long videoId =addContent(conn,uc);//添加视频后获取id
            contentMediaDao.addMedia(conn,videoId, videoUrl, UploadType.VIDEO.getMediaType(),1);
            contentMediaDao.addMedia(conn,videoId,coverUrl,UploadType.COVER.getMediaType(), 1);
            conn.commit();//提交提交提交提交

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("数据库写入失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }



    public void addPost(UploadCommand uc, String coverUrl, List<String> imageUrls) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            long contentId = addContent(conn, uc);
            if (coverUrl != null) {
                contentMediaDao.addMedia(conn, contentId, coverUrl, UploadType.COVER.getMediaType(), 1);
            }
            int sort = 1;
            for (String imageUrl : imageUrls) {
                contentMediaDao.addMedia(conn, contentId, imageUrl, UploadType.IMAGE.getMediaType(), sort++);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "回滚失败", ex);
                }
            }
            throw new ServerException("数据库写入失败");
        } finally {
            MyConnectionPool.release(conn);
        }
    }

    public long addContent(Connection conn, UploadCommand uc) throws SQLException {
        long userId=uc.getUserId();
        String title = uc.getTitle();
        String description = uc.getDescription();
        int categoryId =uc.getCategoryId();
        return contentDao.addContent(conn, userId, uc.getType(), title,description,categoryId);
    }

    public void deleteContent(long contentId,long userId) { }


    public void buildContent(Content content, Map<Integer, List<ContentMedia>> mediaMap,List<Comment> commentList) {

        content.setCommentList(commentList);
        String coverUrl = null;
        List<ContentMedia> coverList = mediaMap.get(3);
        if (coverList != null && !coverList.isEmpty()) {
            coverUrl = coverList.getFirst().getUrl();
        }
        content.setCoverUrl(coverUrl);
        if (content.getType() == 1) {
            List<ContentMedia> videoList = mediaMap.get(1);
            if (videoList != null && !videoList.isEmpty()) {
                content.setVideoUrl(videoList.getFirst().getUrl());
            }
        } else if (content.getType() == 2) {
            List<ContentMedia> imageList = mediaMap.get(2);
            if (imageList != null && !imageList.isEmpty()) {
                List<String> imageUrls = new ArrayList<>();
                for (ContentMedia media : imageList) {
                    imageUrls.add(media.getUrl());
                }
                content.setImageUrls(imageUrls);
            }
            
        }
    }

    //输入的视频是否合法
    public boolean videoCheck(String title,String description,String url){
        if(title==null||title.length()>50){
            return false;
        }
        if (description!=null&&description.length()>500){
            return false;
        }
        if(url==null||url.length()>500){
            return false;
        }
        return !title.trim().isEmpty();
    }
}
