package com.itheima.user.service;

import com.itheima.config.AppConfig;
import com.itheima.config.FeedBigVConfig;
import com.itheima.exception.ServerException;
import com.itheima.user.dao.UserDao;
import com.itheima.util.LogProbe;
import com.itheima.util.LogUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AutoBigVStateService} 单测（feed3-T28-A）：滞回判定能力——
 * **带内不翻转**（两个方向）/ **两条线的边界值**（恰好阈值、恰好降级线、刚跌破）/
 * **edge 恰一次**（只认 affected rows）/ **系数 1.0 等价关闭**（带消失）/ **配置非法 fail-fast** /
 * **SQL 失败 fail-atomic 抛出且只有一条带栈记录**。
 *
 * <p><b>隔离手法</b>：mock {@link UserDao}（数据面）＋真实 {@link FeedBigVConfig}（不配外部文件 ⇒
 * 每次现读 {@code AppConfig} 静态值），配置经反射改写 {@code AppConfig.PROPS} 注入（沿
 * {@code AppConfigTest} / {@code FeedBigVRouterTest} 先例，用例后还原）；**不依赖 DB / Redis / IoC**。
 *
 * <p>阈值 / 系数口径：升级线 {@code count >= threshold}、降级线 {@code count < ratio × threshold}；
 * 带 = {@code [ratio×threshold, threshold)}。
 */
class AutoBigVStateServiceTest {

    private static final long AUTHOR = 9L;

    private static Properties originalProps;

    private UserDao userDao;
    private Connection conn;
    private AutoBigVStateService service;
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
        replaceProps(props("10000", "0.8"));
        userDao = mock(UserDao.class);
        conn = mock(Connection.class);
        // 未配 feed.bigv.configFile ⇒ FeedBigVConfig 每次现读 AppConfig（与生产默认形态一致）
        service = new AutoBigVStateService(userDao, new FeedBigVConfig());
        probe = LogProbe.attachTo(LogUtil.getLogger(AutoBigVStateService.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        probe.detach();
        replaceProps(originalProps);
    }

    // ==================== 升级线（count >= 阈值 ⇒ 幂等入表） ====================

    @Test
    void authorAtThresholdIsPromotedOnce() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(10000);
        when(userDao.insertAutoBigV(conn, AUTHOR)).thenReturn(true);

        assertEquals(AutoBigVStateService.Transition.UPGRADED, service.evaluate(conn, AUTHOR),
                "粉丝数 == 阈值 ⇒ 达线（>= 口径，边界含）");
        verify(userDao).insertAutoBigV(conn, AUTHOR);
        assertTrue(probe.records().isEmpty(), "状态迁移本身不记日志（调用方在提交后记）");
    }

    @Test
    void authorAboveThresholdWithoutStateRowIsNotAnEdge() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(50000);
        when(userDao.insertAutoBigV(conn, AUTHOR)).thenReturn(false);   // INSERT IGNORE 撞唯一键

        assertEquals(AutoBigVStateService.Transition.NONE, service.evaluate(conn, AUTHOR),
                "affected rows = 0 ⇒ 状态本就一致，不是本次迁移（edge 恰一次的判据）");
    }

    // ==================== 降级线（count < 系数 × 阈值 ⇒ 幂等出表） ====================

    @Test
    void justBelowDowngradeLineIsDemoted() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(7999);   // 0.8 × 10000 = 8000
        when(userDao.deleteAutoBigV(conn, AUTHOR)).thenReturn(true);

        assertEquals(AutoBigVStateService.Transition.DOWNGRADED, service.evaluate(conn, AUTHOR));
        verify(userDao).deleteAutoBigV(conn, AUTHOR);
    }

    @Test
    void noRowNoEdgeOnDowngradeAttempt() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(3);
        when(userDao.deleteAutoBigV(conn, AUTHOR)).thenReturn(false);        // 本就不在表中

        assertEquals(AutoBigVStateService.Transition.NONE, service.evaluate(conn, AUTHOR),
                "无行可删 ⇒ 本次无迁移（并发下只有先到者 affected == 1，见类注释的行锁论证）");
    }

    // ==================== 带内不翻转（滞回本体，两个方向） ====================

    @Test
    void authorInBandKeepsBigVStateWithoutAnyWrite() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(8000);    // 恰好降级线 = 带内下沿

        assertEquals(AutoBigVStateService.Transition.NONE, service.evaluate(conn, AUTHOR),
                "count == 系数×阈值 ⇒ 不算跌破（降级线是 <，边界含）；已升者保持大V");
        verify(userDao, never()).deleteAutoBigV(any(), anyLong());
        verify(userDao, never()).insertAutoBigV(any(), anyLong());
    }

    @Test
    void authorInBandWithoutRowIsNotPromoted() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(9999);    // 带内上沿

        assertEquals(AutoBigVStateService.Transition.NONE, service.evaluate(conn, AUTHOR),
                "未达升级线 ⇒ 不升（滞回不让带内作者翻转，两个方向一致）");
        verify(userDao, never()).insertAutoBigV(any(), anyLong());
        verify(userDao, never()).deleteAutoBigV(any(), anyLong());
    }

    @Test
    void nonDivisibleThresholdKeepsBandSemantics() throws Exception {
        // T=3、ratio=0.5 ⇒ 降级线 = 1.5（非整数）：带 = [1.5, 3)。评审建议补：边界不仅出现在整除组合
        replaceProps(props("3", "0.5"));
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(2);

        assertEquals(AutoBigVStateService.Transition.NONE, service.evaluate(conn, AUTHOR),
                "2 >= 1.5 ⇒ 带内（已升者不翻转，且不得误删）");
        verify(userDao, never()).deleteAutoBigV(any(), anyLong());

        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(1);
        when(userDao.deleteAutoBigV(conn, AUTHOR)).thenReturn(true);

        assertEquals(AutoBigVStateService.Transition.DOWNGRADED, service.evaluate(conn, AUTHOR),
                "1 < 1.5 ⇒ 跌破降级线");
    }

    @Test
    void bandIsEmptyWhenRatioIsOne() throws Exception {
        replaceProps(props("10000", "1.0"));
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(9999);
        when(userDao.deleteAutoBigV(conn, AUTHOR)).thenReturn(true);

        assertEquals(AutoBigVStateService.Transition.DOWNGRADED, service.evaluate(conn, AUTHOR),
                "系数 1.0 ⇒ 降级线 = 升级线（带消失）⇒ 行为与 T28-A 之前一致（等价关闭）");
    }

    @Test
    void authorAtThresholdWithRatioOneIsPromoted() throws Exception {
        replaceProps(props("10000", "1.0"));
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(10000);
        when(userDao.insertAutoBigV(conn, AUTHOR)).thenReturn(true);

        assertEquals(AutoBigVStateService.Transition.UPGRADED, service.evaluate(conn, AUTHOR));
        verify(userDao, never()).deleteAutoBigV(any(), anyLong());
    }

    // ==================== 配置非法 fail-fast ====================

    @Test
    void illegalRatioFailsFastBeforeAnySql() throws Exception {
        replaceProps(props("10000", "1.5"));

        assertThrows(IllegalArgumentException.class, () -> service.evaluate(conn, AUTHOR),
                "系数 > 1 会让降级线高于升级线（带内必翻转）⇒ 不得静默降级");
        verifyNoInteractions(userDao);
    }

    @Test
    void illegalThresholdFailsFastBeforeAnySql() throws Exception {
        replaceProps(props("abc", "0.8"));

        assertThrows(IllegalArgumentException.class, () -> service.evaluate(conn, AUTHOR),
                "阈值解析失败照旧 fail-fast（与读侧判定同口径）");
        verifyNoInteractions(userDao);
    }

    // ==================== SQL 失败：fail-atomic（向上抛，由调用方事务回滚） ====================

    @Test
    void sqlFailureThrowsServerExceptionWithSingleStack() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.evaluate(conn, AUTHOR),
                "维护失败 ⇒ 抛（关注 / 取关事务随整体回滚，fail-atomic）");

        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "自动大V状态维护失败, userId=" + AUTHOR, SQLException.class);
        assertTrue(probe.atLevel(Level.WARNING).isEmpty(),
                "包装点即源头 ⇒ 只此一条带栈记录，无第二条结论行");
    }

    @Test
    void writeFailureAlsoThrowsSoTransactionCanRollBack() throws Exception {
        when(userDao.getFollowerCountById(conn, AUTHOR)).thenReturn(50000);
        when(userDao.insertAutoBigV(conn, AUTHOR)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.evaluate(conn, AUTHOR));
        LogProbe.assertExactlyOneStacked(probe, Level.SEVERE,
                "自动大V状态维护失败, userId=" + AUTHOR, SQLException.class);
    }

    // ==================== 辅助 ====================

    /** 参数顺序：阈值、降级系数。名单**留空**——写侧判定刻意不看名单（见类注释）。 */
    private static Properties props(String threshold, String ratio) {
        Properties p = new Properties();
        p.setProperty("feed.bigv.threshold", threshold);
        p.setProperty("feed.bigv.downgradeRatio", ratio);
        p.setProperty("feed.bigv.userIds", "");
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
