package com.itheima.follow.service;

import com.itheima.follow.dao.FollowDao;
import com.itheima.follow.model.dto.FollowPageResult;
import com.itheima.cache.ZSetCache;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.model.entity.User;
import com.itheima.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import com.itheima.util.LogUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FollowService {

    private final FollowDao followDao;
    private final UserDao userDao;
    private final FollowCache followCache;
    private final TransactionTemplate transactionTemplate;
    private static final Logger LOGGER =
            LogUtil.getLogger(FollowService.class);

    @InjectConstructor
    public FollowService(FollowDao followDao, UserDao userDao, FollowCache followCache,
                         TransactionTemplate transactionTemplate) {
        this.followDao = followDao;
        this.userDao = userDao;
        this.followCache = followCache;
        this.transactionTemplate = transactionTemplate;
    }

    public void follow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException("不能关注自己");
        }
        transactionTemplate.execute(conn -> {
            // 先检查是否已关注
            boolean alreadyFollowed = followDao.isFollowing(conn, userId, followedUserId);
            if (alreadyFollowed) {
                throw new ConflictException("已关注，不可重复操作");
            }
            try {
                followDao.addFollow(conn, userId, followedUserId);
                userDao.updateFollowCount(conn, userId, 1);
                userDao.updateFollowerCount(conn, followedUserId, 1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "关注失败, userId=" + userId + ", followedUserId=" + followedUserId, e);
                throw new ServerException("关注失败");
            }
        });
        // DB 提交后缓存双写（NEEDS 4.10：MULTI 原子，失败双 DEL 自愈，不影响主流程）
        followCache.cacheFollow(userId, followedUserId);
    }

    /**
     * 关注列表（**缺省路径**：调用方未传分页参数时使用，T7 起语义与改造前逐字节一致）：
     * 取缓存全量关注 ids（升序）→ 事务内批量装载用户 + 批量判关注态。
     */
    public List<Map<String, Object>> getFollowingList(long userId, Long currentUserId) {
        List<Long> ids = followCache.getFollowingIds(userId);
        return loadUserList(ids, currentUserId);
    }

    /**
     * 粉丝列表（**缺省路径**，逻辑同 {@link #getFollowingList(long, Long)}，缓存入口换粉丝集）。
     */
    public List<Map<String, Object>> getFollowerList(long userId, Long currentUserId) {
        List<Long> ids = followCache.getFollowerIds(userId);
        return loadUserList(ids, currentUserId);
    }

    /**
     * 关注列表**分页**（T7 B2：page/pageSize 由 Controller 解析归一后传入）：
     * 缓存侧经 A1 有序窗口读（ZRANGE[start,stop] + ZCARD，一趟 pipeline）只取该页 ids 与总数，
     * **不再全量回传**；DB 装载与判重也只针对该页 ids（事务边界与缺省路径完全一致——
     * 私有 {@code buildUserList} 原样复用，U-14②号点不在本任务范围）。
     *
     * <p>分页信封（{@code FollowPageResult}）在事务**外**组装，事务内语句集与全量路径相同。
     *
     * @param page     页码（≥1，已归一）
     * @param pageSize 页大小（1~50，已归一）
     */
    public FollowPageResult<Map<String, Object>> getFollowingList(long userId, Long currentUserId,
                                                                  int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        return buildPage(followCache.getFollowingWindow(userId, offset, pageSize),
                currentUserId, page, pageSize);
    }

    /**
     * 粉丝列表**分页**：逻辑同 {@link #getFollowingList(long, Long, int, int)}，缓存入口换粉丝集。
     */
    public FollowPageResult<Map<String, Object>> getFollowerList(long userId, Long currentUserId,
                                                                 int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        return buildPage(followCache.getFollowerWindow(userId, offset, pageSize),
                currentUserId, page, pageSize);
    }

    /**
     * 全量 ids → 用户视图列表（缺省路径共用）：空 ids 直接返回空列表（不打事务），
     * 非空走事务批量装载 + 批量判关注态（事务内语句集与改造前一致）。
     */
    private List<Map<String, Object>> loadUserList(List<Long> ids, Long currentUserId) {
        if (ids.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return transactionTemplate.execute(conn -> {
            try {
                return buildUserList(conn, ids, currentUserId);
            } catch (SQLException e) {
                throw new ServerException("查询失败");
            }
        });
    }

    /**
     * 分页信封组装：该页 ids 走与全量路径同一装载逻辑（{@link #loadUserList}），
     * 总数取缓存窗口的 total（同一 key 的 ZCARD，与页内容同源）。
     */
    private FollowPageResult<Map<String, Object>> buildPage(ZSetCache.Window window,
                                                            Long currentUserId,
                                                            int page, int pageSize) {
        List<Map<String, Object>> users = loadUserList(window.getIds(), currentUserId);
        int total = (int) Math.min(window.getTotal(), Integer.MAX_VALUE);
        return new FollowPageResult<>(users, total, page, pageSize);
    }

    private List<Map<String, Object>> buildUserList(Connection conn, List<Long> ids, Long currentUserId) throws SQLException {
        if (ids.isEmpty()) return java.util.Collections.emptyList();
        List<User> users = userDao.findUsersByIds(conn, ids);
        Set<Long> followedSet = new HashSet<>();
        if (currentUserId != null) {
            Map<Long, Boolean> followedMap = followCache.batchIsFollowing(currentUserId, ids);
            for (Map.Entry<Long, Boolean> entry : followedMap.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    followedSet.add(entry.getKey());
                }
            }
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (User u : users) {
            Map<String, Object> map = new HashMap<>();
            map.put("userId", u.getId());
            map.put("username", u.getUserName());
            map.put("isFollowed", followedSet.contains(u.getId()));
            map.put("isSelf", currentUserId != null && u.getId() == currentUserId.longValue());
            result.add(map);
        }
        return result;
    }

    public void unfollow(long userId, long followedUserId) {
        if (userId == followedUserId) {
            throw new ConflictException( "不能取关自己");
        }
        transactionTemplate.execute(conn -> {
            // 先检查是否已关注
            boolean isFollowing = followDao.isFollowing(conn, userId, followedUserId);
            if (!isFollowing) {
                throw new ConflictException("未关注，不可取消");
            }
            try {
                followDao.deleteFollow(conn, userId, followedUserId);
                userDao.updateFollowCount(conn, userId, -1);
                userDao.updateFollowerCount(conn, followedUserId, -1);
                return null;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "取关失败, userId=" + userId + ", followedUserId=" + followedUserId, e);
                throw new ServerException("取关失败");
            }
        });
        // DB 提交后缓存双写（SREM，失败双 DEL 自愈，不影响主流程）
        followCache.cacheUnfollow(userId, followedUserId);
    }
}