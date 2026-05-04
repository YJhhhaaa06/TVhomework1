package com.itheima.service;

import com.itheima.command.UploadCommand;
import com.itheima.controller.UploadType;
import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.dao.ContentMediaDao;
import com.itheima.dao.FollowDao;
import com.itheima.exception.*;
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
    private FollowDao followDao=new FollowDao();

    private static List<RecommendVO> recommendList =new ArrayList<>();
    private static Map<Long,ContentDetailVO> detailVOMap=new HashMap<>();
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
            List<RecommendVO> newRecommendList = contentDao.findAllContent();
            Map<Long,ContentDetailVO> newDetailVOMap=getDetailVOMap();
            for (RecommendVO rvo : newRecommendList) {
                ContentDetailVO detail = newDetailVOMap.get(rvo.getId());//从detailMap中取出封面
                if (detail != null && detail.getCoverUrl() != null) {
                    rvo.setCoverUrl(detail.getCoverUrl());
                }
            }
            recommendList = newRecommendList;
            detailVOMap=newDetailVOMap;

        }catch (Exception e){
            e.printStackTrace();
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



    public List<RecommendVO> getContentList() {
        return recommendList;
    }

    // ===== 查询 =====
    //从缓存取出视频和动态
    public ContentDetailVO getRecommendedContent(long contentId){
        return detailVOMap.get(contentId);
    }




    public ContentDetailVO getContentById(long contentId) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            ContentDetailVO contentDetailVO =contentDao.findContent(conn,contentId);
            if (contentDetailVO==null){
                return null;
            }
            List<Comment> commentList = commentDao.getComments(conn, contentDetailVO.getId());
            Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentDetailVO.getId());
            buildContent(contentDetailVO, mediaMap, commentList);
            return contentDetailVO;
        }catch (SQLException e){
            throw new ConflictException(e.getMessage());
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    public List<ContentDetailVO> search(String keyword) {
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            List<ContentDetailVO> list=contentDao.search(conn,keyword,1,20);
            for (ContentDetailVO contentDetailVO : list) {
                List<Comment> commentList = commentDao.getComments(conn, contentDetailVO.getId());
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentDetailVO.getId());
                buildContent(contentDetailVO, mediaMap, commentList);
            }
            return list;
        }catch (SQLException e){
            e.printStackTrace();
            throw new ServerException("搜索失败，请重试");
        }finally {
            MyConnectionPool.release(conn);
        }
    }

    //推流
    public List<RecommendVO> getRecommend(int limit) {//推荐limit条视频

        List<RecommendVO> list = new ArrayList<>(recommendList);

        // 打乱顺序（随机）
        Collections.shuffle(list);

        // 取limit和list.size的最小值防止越界
        int size = Math.min(limit, list.size());

        return list.subList(0, size);
    }

    // 批量填充 isFollow，不污染缓存
    public void fillFollowStatus(List<? extends ContentBaseVO> list, Long userId) {
        if (userId == null || list == null || list.isEmpty()) return;

        List<Long> authorIds = new ArrayList<>();
        for (ContentBaseVO vo : list) {
            if (vo.getAuthorId() > 0) {
                authorIds.add(vo.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) return;

        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            Set<Long> followedSet = followDao.getFollowedIds(conn, userId, authorIds);
            for (ContentBaseVO vo : list) {
                vo.setIsFollow(followedSet.contains(vo.getAuthorId()));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            MyConnectionPool.release(conn);
        }
    }

//    public Video getNextVideo(List<Video> list) {
//        int randomNum=r.nextInt(list.size());
//        return list.get(randomNum);
//    }

    // ===== 管理 =====
    public void addVideo(UploadCommand uc, String videoUrl, String coverUrl) {
        Connection conn = null;

        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            long videoId = doAddContent(conn,uc);//添加视频后获取id
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
            long contentId = doAddContent(conn, uc);
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

    public long doAddContent(Connection conn, UploadCommand uc) throws SQLException {
        long userId=uc.getUserId();
        String title = uc.getTitle();
        String description = uc.getDescription();
        int categoryId =uc.getCategoryId();
        return contentDao.addContent(conn, userId, uc.getType(), title,description,categoryId);
    }

    public void deleteContent(long contentId,long userId) { }


    public void buildContent(ContentDetailVO contentDetailVO, Map<Integer, List<ContentMedia>> mediaMap, List<Comment> commentList) {

        contentDetailVO.setCommentList(commentList);
        List<ContentMedia> coverList = mediaMap.get(3);
        if (coverList != null && !coverList.isEmpty()) {
            contentDetailVO.setCoverUrl(jointUrl(coverList.getFirst().getUrl()));
        }
        int type= contentDetailVO.getType();
        switch (type){
            case 1:
                List<ContentMedia> videoList = mediaMap.get(1);
                if (videoList != null && !videoList.isEmpty()) {
                    contentDetailVO.setVideoUrl(jointUrl(videoList.getFirst().getUrl()));
                } else {
                    throw new NotFoundException("资源已丢失");
                }
                break;
            case 2:
                List<ContentMedia> imageList = mediaMap.get(2);
                if (imageList != null && !imageList.isEmpty()) {
                    List<String> imageUrls = new ArrayList<>();
                    for (ContentMedia media : imageList) {
                        imageUrls.add(media.getUrl());
                    }
                    contentDetailVO.setImageUrls(imageUrls);
                }
                break;
            default:
                throw new ServerException("未知内容类型: " + type);
        }




    }

    private Map<Long,ContentDetailVO> getDetailVOMap(){
        Connection conn=null;
        try {
            conn=MyConnectionPool.getConnection();
            List<ContentDetailVO> list=contentDao.findAllContentDetail(conn);
            for (ContentDetailVO contentDetailVO : list) {
                List<Comment> commentList = commentDao.getComments(conn, contentDetailVO.getId());
                Map<Integer, List<ContentMedia>> mediaMap = contentMediaDao.findMedia(conn, contentDetailVO.getId());
                buildContent(contentDetailVO, mediaMap, commentList);
            }
            Map<Long,ContentDetailVO> map=new HashMap<>();
            for (ContentDetailVO cdVO:list){
                map.put(cdVO.getId(),cdVO);
            }
            return map;
        }catch (SQLException e){
            e.printStackTrace();
            throw new ServerException("缓存失败");
        }finally {
            MyConnectionPool.release(conn);
        }
    }


    private String jointUrl(String url){
        return "http://localhost:8080/MyAPP"+url;
    }
}
