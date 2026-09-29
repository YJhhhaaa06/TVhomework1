package com.itheima.feed.service;

import com.itheima.config.AppConfig;
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
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedBigVRouter} 单测（feed2-21 T21 单作者判定；feed2-23 T23 增批量判定；feed3-T26 口径统一
 * + 写侧批量）：名单命中 / 阈值边界 / 降级（fail-open）/ 批量单点（分块多条 SQL ∪ 名单、名单项不查 DB、
 * SQL 失败只留名单项）/ **单作者委派** / **分块** / **批量尺寸非法 fail-fast**。
 *
 * <p>隔离手法（feed3-T26）：mock {@link UserDao} + {@link TransactionTemplate}（批量判定的数据面），
 * **不依赖真实 Redis / DB**——判定口径已统一到 DB 真值，本类**不再触 {@link FollowCache}**（该 mock 仅留作
 * "判定不得回退到计数缓存"的负向断言）。配置经反射改写 {@code AppConfig.PROPS} 注入
 * （沿 {@code AppConfigTest} 先例，用例后还原，不污染其它测试类）。
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
        followCache = mock(FollowCache.class);   // feed3-T26 起判定不再走它（负向断言用）
        userDao = mock(UserDao.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        router = new FeedBigVRouter(userDao, tt);
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
    void listedAuthorIsBigVWithoutAnySql() throws Exception {
        replaceProps(props("7, 8", "10000"));

        assertTrue(router.isBigV(7L));
        assertTrue(router.isBigV(8L), "名单项含前后空白也应命中（解析去空白）");

        verify(userDao, never()).findUserIdsByMinFollowerCount(any(), anyList(), anyInt());
        verify(followCache, never()).getFollowerCount(anyLong());
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    // ==================== 阈值边界（DB 真值） ====================

    @Test
    void thresholdIsInclusiveLowerBound() throws Exception {
        replaceProps(props("", "100"));
        when(userDao.findUserIdsByMinFollowerCount(conn, List.of(101L), 100)).thenReturn(List.of(101L));
        when(userDao.findUserIdsByMinFollowerCount(conn, List.of(100L), 100)).thenReturn(List.of(100L));
        when(userDao.findUserIdsByMinFollowerCount(conn, List.of(99L), 100)).thenReturn(List.of());

        assertTrue(router.isBigV(101L));
        assertTrue(router.isBigV(100L), "粉丝数 == 阈值 ⇒ 大V（>= 口径）");
        assertFalse(router.isBigV(99L));

        // 口径统一（feed3-T26）：粉丝数只从 DB 真值读，不再经计数缓存
        verify(followCache, never()).getFollowerCount(anyLong());
    }

    @Test
    void noListNoThresholdHitForNormalAuthor() throws Exception {
        replaceProps(props("", "10000"));
        when(userDao.findUserIdsByMinFollowerCount(any(), anyList(), anyInt())).thenReturn(List.of());

        assertFalse(router.isBigV(AUTHOR));
        assertTrue(probe.atLevel(Level.WARNING).isEmpty());
    }

    // ==================== 降级（fail-open） ====================

    @Test
    void degradesToNormalAuthorOnDbFailureWithSingleStack() throws Exception {
        replaceProps(props("", "100"));
        when(userDao.findUserIdsByMinFollowerCount(any(), anyList(), anyInt()))
                .thenThrow(new SQLException("db down"));

        assertFalse(router.isBigV(AUTHOR), "粉丝数查询失败 ⇒ 按普通作者处理（fail-open，宁可多写）");

        // 源头（事务回调）SEVERE + 栈恰一条；下游结论行 WARNING 恰一条、不带栈
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "大V批量判定查询失败, authorCount=1", SQLException.class);
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("大V判定降级"));
        assertNull(warnings.getFirst().getThrown(), "源头已持栈 → 结论行不带栈");
    }

    // ==================== 单作者委派（feed3-T26 口径唯一实现） ====================

    @Test
    void singleAuthorEntryDelegatesToBatchEntry() throws Exception {
        replaceProps(props("", "100"));
        when(userDao.findUserIdsByMinFollowerCount(conn, List.of(AUTHOR), 100)).thenReturn(List.of(AUTHOR));

        assertTrue(router.isBigV(AUTHOR));

        // 单作者入口就是批量入口（恰一次 SQL、入参 = 单元素关注集）⇒ 规则只有一份实现
        verify(userDao, times(1)).findUserIdsByMinFollowerCount(conn, List.of(AUTHOR), 100);
        verify(followCache, never()).getFollowerCount(anyLong());
    }

    // ==================== 批量判定 ====================

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

        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "大V批量判定查询失败, authorCount=1", SQLException.class);
        List<LogRecord> warnings = probe.atLevel(Level.WARNING);
        assertEquals(1, warnings.size(), () -> "应恰一条 WARNING，实际: " + probe.records());
        assertTrue(warnings.getFirst().getMessage().contains("大V判定降级"));
        assertNull(warnings.getFirst().getThrown(), "源头已持栈 → 结论行不带栈");
    }

    // ==================== 分块（feed3-T26） ====================

    @Test
    void batchSplitsIntoChunksWhenExceedingBatchSize() throws Exception {
        replaceProps(props("", "100", "2"));       // 每批 2 个 IN 参数
        when(userDao.findUserIdsByMinFollowerCount(any(), anyList(), anyInt())).thenReturn(List.of());

        router.isBigVBatch(List.of(1L, 2L, 3L, 4L, 5L));

        ArgumentCaptor<List<Long>> chunks = chunkCaptor();
        verify(userDao, times(3)).findUserIdsByMinFollowerCount(eq(conn), chunks.capture(), eq(100));
        List<List<Long>> batches = chunks.getAllValues();
        assertEquals(2, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
        assertEquals(1, batches.get(2).size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), concat(batches), "分块无重无漏、保原序");
    }

    @Test
    void batchSingleChunkWhenWithinLimit() throws Exception {
        replaceProps(props("", "100", "200"));
        when(userDao.findUserIdsByMinFollowerCount(any(), anyList(), anyInt())).thenReturn(List.of());

        router.isBigVBatch(List.of(1L, 2L));

        verify(userDao, times(1)).findUserIdsByMinFollowerCount(eq(conn), eq(List.of(1L, 2L)), eq(100));
    }

    @Test
    void batchChunkFailureFailsOpenWholeBatch() throws Exception {
        replaceProps(props("8", "100", "2"));      // 待查 4 个（1,2,3,4）⇒ 两块
        when(userDao.findUserIdsByMinFollowerCount(any(), anyList(), anyInt()))
                .thenReturn(List.of(1L, 2L))
                .thenThrow(new SQLException("db down"));

        Set<Long> bigVs = router.isBigVBatch(List.of(8L, 1L, 2L, 3L, 4L));

        assertEquals(Set.of(8L), bigVs,
                "整批 all-or-nothing：第二块失败 ⇒ 不回并第一块结果，只保留名单项（不产生半批口径）");
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "大V批量判定查询失败, authorCount=4", SQLException.class);
        assertEquals(1, probe.atLevel(Level.WARNING).size(), "恰一条结论行 WARNING");
    }

    @Test
    void batchThrowsOnNonPositiveQueryBatch() throws Exception {
        replaceProps(props("", "100", "0"));

        assertThrows(IllegalArgumentException.class, () -> router.isBigVBatch(List.of(9L)),
                "批量尺寸非正数 ⇒ 分块循环无法前进 ⇒ fail-fast（不得被 fail-open 吞成普通作者）");

        verify(userDao, never()).findUserIdsByMinFollowerCount(any(), anyList(), anyInt());
    }

    // ==================== 辅助 ====================

    private static Properties props(String userIds, String threshold) {
        return props(userIds, threshold, null);
    }

    private static Properties props(String userIds, String threshold, String queryBatch) {
        Properties p = new Properties();
        p.setProperty("feed.bigv.userIds", userIds);
        p.setProperty("feed.bigv.threshold", threshold);
        if (queryBatch != null) {
            p.setProperty("feed.bigv.queryBatch", queryBatch);
        }
        return p;
    }

    private static List<Long> concat(List<List<Long>> batches) {
        List<Long> all = new ArrayList<>();
        for (List<Long> b : batches) {
            all.addAll(b);
        }
        return all;
    }

    private static ArgumentCaptor<List<Long>> chunkCaptor() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        return captor;
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
