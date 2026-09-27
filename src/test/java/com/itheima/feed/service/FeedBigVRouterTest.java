package com.itheima.feed.service;

import com.itheima.config.AppConfig;
import com.itheima.exception.DatabaseException;
import com.itheima.follow.service.FollowCache;
import com.itheima.user.dao.UserDao;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedBigVRouter} 单测（feed2-21 T21 单作者判定；feed2-23 T23 增批量判定）：名单命中 /
 * 阈值边界 / 降级（fail-open）/ 批量单点（一条 SQL ∪ 名单、名单项不查 DB、SQL 失败只留名单项）。
 *
 * <p>隔离手法：mock {@link FollowCache}（粉丝数读取）与 {@link UserDao} + {@link TransactionTemplate}
 * （批量判定），**不依赖真实 Redis / DB**；配置经反射改写 {@code AppConfig.PROPS} 注入
 * （沿 {@code AppConfigTest} 既有先例，用例后还原，不污染其它测试类）。
 */
class FeedBigVRouterTest {

    private static final long AUTHOR = 9L;

    private static Properties originalProps;

    private FollowCache followCache;
    private UserDao userDao;
    private TransactionTemplate tt;
    private Connection conn;
    private FeedBigVRouter router;
    private LogProbe probe;

    @BeforeAll
    static void saveOriginalProps() throws Exception {
        originalProps = new Properties();
        originalProps.putAll(propsField());
    }

    @AfterAll
    static void restoreOriginalProps() throws Exception {
        replaceProps(originalProps);
    }

    @BeforeEach
    void setUp() throws Exception {
        followCache = mock(FollowCache.class);
        userDao = mock(UserDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        router = new FeedBigVRouter(followCache, userDao, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
        probe = LogProbe.attachTo(LogUtil.getLogger(FeedBigVRouter.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        probe.detach();
        replaceProps(originalProps);
    }

    // ==================== 名单命中 ====================

    @Test
    void listedAuthorIsBigVWithoutFollowerCountQuery() throws Exception {
        replaceProps(props("7, 8", "10000"));

        assertTrue(router.isBigV(7L));
        assertTrue(router.isBigV(8L), "名单项含前后空白也应命中（解析去空白）");

        verify(followCache, never()).getFollowerCount(anyLong());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    // ==================== 阈值边界 ====================

    @Test
    void thresholdIsInclusiveLowerBound() throws Exception {
        replaceProps(props("", "100"));

        when(followCache.getFollowerCount(101L)).thenReturn(101);
        when(followCache.getFollowerCount(100L)).thenReturn(100);
        when(followCache.getFollowerCount(99L)).thenReturn(99);

        assertTrue(router.isBigV(101L));
        assertTrue(router.isBigV(100L), "粉丝数 == 阈值 ⇒ 大V（>= 口径）");
        assertFalse(router.isBigV(99L));
    }

    @Test
    void noListNoThresholdHitForNormalAuthor() throws Exception {
        replaceProps(props("", "10000"));

        when(followCache.getFollowerCount(AUTHOR)).thenReturn(3);

        assertFalse(router.isBigV(AUTHOR));
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    // ==================== 降级（fail-open） ====================

    @Test
    void degradesToNormalAuthorOnCountFailureWithoutDoubleStack() throws Exception {
        replaceProps(props("", "100"));
        when(followCache.getFollowerCount(AUTHOR)).thenThrow(new DatabaseException("db down"));

        assertFalse(router.isBigV(AUTHOR), "计数读取失败 ⇒ 按普通作者处理（fail-open，宁可多写）");

        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("大V判定降级"));
        assertNull(warnings.getFirst().getThrown(), "源头（FollowCache.loadCount / 缓存层）已持栈 → 结论行不带栈");
    }

    // ==================== 批量判定（feed2-23 T23 读侧） ====================

    @Test
    void batchEmptyInputReturnsEmptyWithoutAnyQuery() throws SQLException {
        assertTrue(router.isBigVBatch(null).isEmpty());
        assertTrue(router.isBigVBatch(List.of()).isEmpty());

        verify(userDao, never()).findUserIdsByMinFollowerCount(any(), anyList(), anyInt());
        verify(followCache, never()).getFollowerCount(anyLong());
    }

    @Test
    void batchAllListedSkipsDbQuery() throws Exception {
        replaceProps(props("7, 8", "10000"));

        Set<Long> bigVs = router.isBigVBatch(List.of(7L, 8L));

        assertEquals(Set.of(7L, 8L), bigVs);
        verify(userDao, never()).findUserIdsByMinFollowerCount(any(), anyList(), anyInt());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    @Test
    void batchQueriesUnlistedAuthorsInOneSqlAndUnionsListed() throws Exception {
        replaceProps(props("8", "100"));
        when(userDao.findUserIdsByMinFollowerCount(conn, List.of(9L), 100)).thenReturn(List.of(9L));

        Set<Long> bigVs = router.isBigVBatch(List.of(8L, 9L));

        assertEquals(Set.of(8L, 9L), bigVs);
        verify(userDao, times(1)).findUserIdsByMinFollowerCount(conn, List.of(9L), 100);
    }

    @Test
    void batchDegradesToListedOnlyOnDbFailureWithoutDoubleStack() throws Exception {
        replaceProps(props("8", "100"));
        when(userDao.findUserIdsByMinFollowerCount(any(), anyList(), anyInt()))
                .thenThrow(new SQLException("db down"));

        Set<Long> bigVs = router.isBigVBatch(List.of(8L, 9L));

        assertEquals(Set.of(8L), bigVs, "SQL 失败 ⇒ fail-open：只保留名单命中项（9 按普通作者处理）");

        // 源头（事务回调）SEVERE + 栈恰一条；下游结论行 WARNING 恰一条、不带栈
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "大V批量判定查询失败, authorCount=1", SQLException.class);
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("大V批量判定降级"));
        assertNull(warnings.getFirst().getThrown(), "源头已持栈 → 结论行不带栈");
    }

    // ==================== 辅助 ====================

    private static Properties props(String userIds, String threshold) {
        Properties p = new Properties();
        p.setProperty("feed.bigv.userIds", userIds);
        p.setProperty("feed.bigv.threshold", threshold);
        return p;
    }

    private static Properties propsField() throws Exception {
        Field f = AppConfig.class.getDeclaredField("PROPS");
        f.setAccessible(true);
        return (Properties) f.get(null);
    }

    private static void replaceProps(Properties p) throws Exception {
        // PROPS 为 static final，不能替换字段引用（JDK 17+ 强封装）；改写其 map 内容（先例 AppConfigTest）
        Properties current = propsField();
        current.clear();
        current.putAll(p);
    }
}