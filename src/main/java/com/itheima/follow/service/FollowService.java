package com.itheima.follow.service;

import com.itheima.follow.dao.FollowDao;
import com.itheima.common.model.dto.PageResult;
import com.itheima.cache.ZSetCache;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ServerException;
import com.itheima.ioc.annotation.Component;
import com.itheima.ioc.annotation.InjectConstructor;
import com.itheima.user.model.entity.User;
import com.itheima.util.TransactionTemplate;

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
        // 里程碑（T9）：关系状态迁移（关注）——DB 已提交即记；置于缓存双写之前
        LOGGER.log(Level.INFO, "关注成功, userId=" + userId + ", followedUserId=" + followedUserId);
        // DB 提交后缓存双写（NEEDS 4.10：MULTI 原子，失败双 DEL 自愈，不影响主流程）
        followCache.cacheFollow(userId, followedUserId);
    }

    /**
     * 关注列表**分页**（T7 B2：page/pageSize 由 Controller 解析归一后传入；T11-A 起为**唯一读入口**
     * ——缺省（不传参）由 Controller 归一为 page 1 / pageSize 200，不再有"缺省全量数组"分支）：
     * 缓存侧经 A1 有序窗口读（ZRANGE[start,stop] + ZCARD，一趟 pipeline）只取该页 ids 与总数，
     * **不再全量回传**；DB 装载与判重也只针对该页 ids（事务内只做 DB 装载，缓存读见
     * {@link #loadUserList} 的 T12 说明——池 U-14②已随 T12 处置）。
     *
     * <p>分页信封（{@code common.model.dto.PageResult}，T14 起为全项目唯一信封）在事务**外**组装，
     * 事务内语句集与改造前一致。
     *
     * @param page     页码（≥1，已归一）
     * @param pageSize 页大小（1~200，已归一；缺省 200 = follow 域信封）
     */
    public PageResult<Map<String, Object>> getFollowingList(long userId, Long currentUserId,
                                                                  int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        return buildPage(followCache.getFollowingWindow(userId, offset, pageSize),
                currentUserId, page, pageSize);
    }

    /**
     * 粉丝列表**分页**：逻辑同 {@link #getFollowingList(long, Long, int, int)}，缓存入口换粉丝集。
     */
    public PageResult<Map<String, Object>> getFollowerList(long userId, Long currentUserId,
                                                                 int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        return buildPage(followCache.getFollowerWindow(userId, offset, pageSize),
                currentUserId, page, pageSize);
    }

    /**
     * 该页 ids → 用户视图列表（分页信封组装共用）：空 ids 直接返回空列表（不打事务、不触碰缓存）；
     * 非空走**事务内 DB 装载 + 事务外判关注态**。
     *
     * <p>T12（治池 U-14②）：**DB 查询与缓存读分离**——事务回调只承载 DB 装载
     * （{@code userDao.findUsersByIds}），提交归还连接后，再在**事务外**批量判关注态
     * （{@code followCache.batchIsFollowing}，内部三态读 + miss 回填 + Redis 挂降级 DB）
     * 并组装视图——消除"外层事务持连接 + 缓存 miss 装载再取新连接"的叠加（自研
     * {@link TransactionTemplate} 无传播语义，嵌套读各自取新连接）。对外行为零变化：
     * 事务内语句集与改造前一致（{@code SQLException → ServerException("查询失败")} 仍在回调内产生）、
     * 返回集/顺序/isFollowed/isSelf 口径一概不变。
     */
    private List<Map<String, Object>> loadUserList(List<Long> ids, Long currentUserId) {
        if (ids.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        // T12：事务回调只做 DB 装载，不触碰任何缓存（缓存读见下方事务外段）
        List<User> users = transactionTemplate.execute(conn -> {
            try {
                return userDao.findUsersByIds(conn, ids);
            } catch (SQLException e) {
                // T11-B：包装点即源头——本行是该链唯一带堆栈记录（LOG_CONVENTION §3.1 附加纪律 2）；
                // 只记 ids 规模，不记具体 id 列表
                LOGGER.log(Level.SEVERE, "用户批量查询失败, ids=" + ids.size(), e);
                throw new ServerException("查询失败");
            }
        });
        return buildUserViews(users, ids, currentUserId);
    }

    /**
     * 事务外：批量判关注态（仅 {@code currentUserId} 非空时）后按 DB 返回序组装用户视图。
     * 判重口径与改造前一致——仍按传入的**该页 ids** 批量查缓存（不因 users 为空而跳过）。
     */
    private List<Map<String, Object>> buildUserViews(List<User> users, List<Long> ids, Long currentUserId) {
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

    /**
     * 分页信封组装：该页 ids 走统一装载逻辑（{@link #loadUserList}），
     * 总数取缓存窗口的 total（同一 key 的 ZCARD，与页内容同源）。
     */
    private PageResult<Map<String, Object>> buildPage(ZSetCache.Window window,
                                                            Long currentUserId,
                                                            int page, int pageSize) {
        List<Map<String, Object>> users = loadUserList(window.getIds(), currentUserId);
        int total = (int) Math.min(window.getTotal(), Integer.MAX_VALUE);
        return new PageResult<>(users, total, page, pageSize);
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
        // 里程碑（T9）：关系状态迁移（取关）——口径同 follow
        LOGGER.log(Level.INFO, "取关成功, userId=" + userId + ", followedUserId=" + followedUserId);
        // DB 提交后缓存双写（SREM，失败双 DEL 自愈，不影响主流程）
        followCache.cacheUnfollow(userId, followedUserId);
    }
}