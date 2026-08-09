# 2026.8.9  

 

本人手动在IDEA删除了一些代码，为重构做准备

被删除的方法都有一个共同点：未被引用

除此之外，这些代码通常有以下特征中的一条或多条：

- 没有这个需求
- 有这个需求，但只是实现了一部分，没有完整实现
- 已经被类似的方法替代
- 因为架构决策改变而废弃
- 实现的时候叠了多层调用，上游直接调用了下游的方法，导致这个方法被绕过

## Service层

- CommentService.java

| 方法声明                                                     | 作用                                      | 删除理由                   |
| ------------------------------------------------------------ | ----------------------------------------- | -------------------------- |
| public void hideComment(long videoId)                        | 隐藏评论                                  | 暂时不做这个功能           |
| public void unhideComment(long videoId)                      | 取消隐藏评论                              | 暂时不做这个功能           |
| public void hideOrUnhideComment(long videoId, boolean choose) | 实施隐藏或取消隐藏评论                    | 暂时不做这个功能，避免误导 |
| public List<CommentVO> getComment(long contentId)            | 根据contentId，前往数据库获取包装好的评论 | 已被缓存查询策略代替       |
| public List<CommentVO> getComment(long contentId, long userId) | 已登录用户查询评论                        | 已被缓存查询策略代替       |
|                                                              |                                           |                            |
|                                                              |                                           |                            |
|                                                              |                                           |                            |



- ContentService.java

| 方法声明                                                     | 作用                      | 删除理由                                                |
| ------------------------------------------------------------ | ------------------------- | ------------------------------------------------------- |
| public void deleteContent(long contentId, long userId)       | 删除视频或帖子            | 暂时不做这个功能                                        |
| public ContentVO getContentVO(long contentId, Long userId)   | 从缓存拿信息组装相应VO    | 有个方法体一模一样的方法，只保留一个                    |
| public List<ContentVO> getContentList()                      | 获取推荐列表              | 被分区过滤获取推荐列表取代                              |
| public List<ContentVO> getRecommend(int limit)               | 获取指定大小的推荐列表    | 被分区过滤获取推荐列表取代                              |
| public ContentCacheDTO getContentById(long contentId)        | 不走缓存，从DB获取Content | 已被缓存中的backfill方法取代                            |
| private void fillContentLikeStatus(ContentVO cVO, long contentId, long userId) | 填充点赞状态              | 有个方法体一模一样的方法，ContentVO可能已经没有存在必要 |
| private void fillFollowStatus(ContentVO vo, Long userId)     | 填充关注状态              | 有个方法体一模一样的方法，ContentVO可能已经没有存在必要 |
|                                                              |                           |                                                         |
|                                                              |                           |                                                         |



## Controller层

- RequestParser.java

| 方法声明                                                     | 作用     | 删除理由                       |
| ------------------------------------------------------------ | -------- | ------------------------------ |
| public static String getBody(HttpServletRequest req) throws IOException | 解析输入 | 这是遗留的，一直在注释中的代码 |
| public static String getBody(HttpServletRequest req) throws IOException | 解析输入 | 这是遗留的，一直在注释中的代码 |
| public static String getBody(HttpServletRequest req) throws IOException | 解析输入 | 这是遗留的，一直在注释中的代码 |
|                                                              |          |                                |



## Dao层

- CommentDao.java

| 方法声明                                                     | 作用                                     | 删除理由                                            |
| ------------------------------------------------------------ | ---------------------------------------- | --------------------------------------------------- |
| public int hideCommentByContent(Connection conn, long contentId)throws SQLException | 隐藏评论区                               | 暂时不做这个功能                                    |
| public int unhideCommentByContent(Connection conn, long ContentId)throws SQLException | 开放评论区                               | 暂时不做这个功能                                    |
| public int getLikeCount(Connection conn, long commentId) throws SQLException | 统计点赞数量                             | 暂时不做这个功能，暂时信任comment表的like_count字段 |
| public int deleteCommentById(Connection conn,long commentId)throws SQLException | 从数据库根据评论ID删除评论，管理员使用   | 暂时不做这个功能                                    |
| public int deleteCommentByVideo(Connection conn,long contentId) throws SQLException | 从数据库根据视频ID删除评论，用于删除视频 | 暂时不做这个功能                                    |
| public int deleteCommentByUser(Connection conn,long userId) throws SQLException | 从数据库根据用户删除评论，用于注销账号   | 暂时不做这个功能                                    |
|                                                              |                                          |                                                     |
|                                                              |                                          |                                                     |
|                                                              |                                          |                                                     |



- ContentDao.java

| 方法声明                                                     | 作用                                       | 删除理由                                                     |
| ------------------------------------------------------------ | ------------------------------------------ | ------------------------------------------------------------ |
| public List<ContentCacheDTO> findContentByUser(Connection conn, long userId, int page, int pageSize) throws SQLException | 根据用户ID查询作品                         | 太重了，被先搜contentId，后查缓存，缓存查不到就走数据库回填的策略淘汰 |
| public List<ContentCacheDTO> keywordSearchInDetail(Connection conn, String keyword, int page, int pageSize) throws SQLException | 根据关键词查询作品                         | 太重了，被先搜contentId，后查缓存，缓存查不到就走数据库回填的策略淘汰 |
| public int updateContentInfo(Connection conn, long contentId, String title, String description) throws SQLException | 修改作品信息                               | 暂时不做这个功能                                             |
| public int getLikeCount(Connection conn, long contentId) throws SQLException | 查询点赞数量                               | 暂时不做这个功能，暂时信任content表的like_count字段          |
| public List<ContentCacheDTO> findAllContent() throws SQLException | 获取所有content，用于填充缓存              | 下游方法被service层直接调用，省去了这个中间方法              |
| public List<ContentCacheDTO> findAllContentDetail() throws SQLException | 获取所有content，用于填充缓存              | 下游方法被service层直接调用，省去了这个中间方法              |
| public List<ContentCacheDTO> findAllContentDetail(Connection conn) throws SQLException | 获取所有content，用于填充缓存,从上游拿连接 | 和上一条一样                                                 |
| public int deleteContent(Connection conn, long contentId) throws SQLException | 删除content                                | 暂时不做这个功能                                             |
| public int hideContent(Connection conn, long contentId) throws SQLException | 隐藏content                                | 暂时不做这个功能                                             |
| public int unhideContent(Connection conn, long contentId) throws SQLException | 取消隐藏                                   | 暂时不做这个功能                                             |
|                                                              |                                            |                                                              |



- UserDao.java

| 方法声明                                                     | 作用                       | 删除理由                               |
| ------------------------------------------------------------ | -------------------------- | -------------------------------------- |
| public String findPhoneById(long id) throws SQLException     | 根据ID查手机号             | 好像没有这个需求                       |
| public long findIDbyPhone(String phone) throws SQLException  | 根据手机号查ID             | 好像没有这个需求                       |
| public boolean isUserExist(long id) throws SQLException      | 根据id查询用户是否存在     | 被同名的，从上游拿连接的方法代替       |
| public boolean isPhoneUsed(String phone) throws SQLException | 查询手机号是否被使用       | 被同名的，从上游拿连接的方法代替       |
| public User getUserForProfileByPhone(String phone)throws SQLException | 根据手机号查询用户所有信息 | 好像没有这个需求                       |
| public User getUserForProfileByPhone(Connection conn,String phone)throws SQLException | 根据手机号查询用户所有信息 | 好像没有这个需求                       |
| public User getUserForProfileById(long id) throws SQLException | 根据id查询用户所有信息     | 被同名的，从上游拿连接的方法代替       |
| public String findUsernameByPhone(String phone) throws SQLException | 根据手机号查询用户名       | 好像没有这个需求                       |
| public String findUsernameById(long id) throws SQLException  | 根据ID获取用户名           | 被一个根据id获取用户所有信息的方法取代 |
| public int deleteUser(Connection conn, String phone, long id) throws SQLException | 删除用户                   | 暂时不做这个功能                       |
|                                                              |                            |                                        |



- ContentLikeDao.java

| 方法声明                                                     | 作用                         | 删除理由                                                     |
| ------------------------------------------------------------ | ---------------------------- | ------------------------------------------------------------ |
| public Set<Long> findAllLikedContentIds(Connection conn, long userId) throws SQLException | 查询用户点赞过的内容         | 由于缓存策略是以content为中心，记录所有给content点过赞的人，这个方法被舍弃 |
| public int deleteContentMedia(Connection conn,long contentId) throws SQLException | 删除视频所有url              | 暂时不做这个功能                                             |
| public int deleteSpecificContentMedia(Connection conn,long mediaId) throws SQLException | 根据id删除url                | 暂时不做这个功能                                             |
| public int deleteSpecificContentMedia(Connection conn,long contentId,int type,int sort) throws SQLException | 根据contentId,type和sort删除 | 暂时不做这个功能                                             |
| public int updateMedia(Connection conn,long contentId,int type,int sort, String url) throws SQLException | 换源                         | 暂时不做这个功能                                             |

