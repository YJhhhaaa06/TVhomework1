package com.itheima.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T19 分页归一化单测：{@link BaseServletUtil} 的 request 无关归一方法（{@code normalizePage} /
 * {@code normalizePageSize}）以及两个 request 形态入口（{@code parsePage} / 三参 {@code parsePageSize}）。
 *
 * <p>背景：T19 把四域分页统一为「域级上限 + 域级信封」（feed/search/profile/follow 均 100），
 * 归一逻辑从 request 形态**下沉为纯函数**（供 search 的 JSON body 分支复用，避免造出第二份口径），
 * 并删除无参 / 两参重载与公共 `DEFAULT_PAGE_SIZE_MAX/DEFAULT_PAGE_SIZE`。
 *
 * <p>本测试锁三件事：① 缺省 / 非法 / ≤0 一律回落；② **返回值恒 ≤ max** 的不变量（含
 * `defaultSize > max` 的越界配置）；③ request 形态入口的归一语义与改造前逐条一致
 * （缺省、空串、非数字、超上限、原样回显）。
 */
class BaseServletUtilTest {

    /** T19 落地值（feed/search/profile/follow 同口径）。 */
    private static final int MAX = 100;
    private static final int DEFAULT = 100;

    private static HttpServletRequest reqWithPage(String raw) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getParameter("page")).thenReturn(raw);
        return req;
    }

    private static HttpServletRequest reqWithPageSize(String raw) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getParameter("pageSize")).thenReturn(raw);
        return req;
    }

    // ---------- normalizePage（纯函数） ----------

    @Test
    void normalizePageFallsBackToFirstPageOnNullZeroOrNegative() {
        assertEquals(1, BaseServletUtil.normalizePage(null));
        assertEquals(1, BaseServletUtil.normalizePage(0));
        assertEquals(1, BaseServletUtil.normalizePage(-7));
    }

    @Test
    void normalizePageKeepsPositiveValue() {
        assertEquals(1, BaseServletUtil.normalizePage(1));
        assertEquals(42, BaseServletUtil.normalizePage(42));
    }

    // ---------- normalizePageSize（纯函数） ----------

    @Test
    void normalizePageSizeFallsBackToDefaultWhenAbsentOrIllegal() {
        assertEquals(DEFAULT, BaseServletUtil.normalizePageSize(null, MAX, DEFAULT));
        assertEquals(DEFAULT, BaseServletUtil.normalizePageSize(0, MAX, DEFAULT));
        assertEquals(DEFAULT, BaseServletUtil.normalizePageSize(-3, MAX, DEFAULT));
    }

    @Test
    void normalizePageSizeKeepsExplicitValueWithinMax() {
        assertEquals(1, BaseServletUtil.normalizePageSize(1, MAX, DEFAULT));
        assertEquals(51, BaseServletUtil.normalizePageSize(51, MAX, DEFAULT));
        assertEquals(MAX, BaseServletUtil.normalizePageSize(MAX, MAX, DEFAULT));
    }

    @Test
    void normalizePageSizeCapsAtMax() {
        assertEquals(MAX, BaseServletUtil.normalizePageSize(999, MAX, DEFAULT));
        assertEquals(500, BaseServletUtil.normalizePageSize(9999, 500, 200));
    }

    @Test
    void normalizePageSizeNeverExceedsMaxEvenWhenDefaultIsLarger() {
        // 不变量：返回值恒 ≤ max —— 防某域把 defaultSize 配得比 max 大而悄悄越界
        assertEquals(MAX, BaseServletUtil.normalizePageSize(null, MAX, 500));
        assertEquals(MAX, BaseServletUtil.normalizePageSize(0, MAX, 500));
        assertEquals(MAX, BaseServletUtil.normalizePageSize(999, MAX, 500));
    }

    @Test
    void normalizePageSizeSupportsDomainSpecificEnvelope() {
        // 评论域口径 (max 500 / 信封 200)：缺省取 200、显式 3 原样（小信封能力保留）
        assertEquals(200, BaseServletUtil.normalizePageSize(null, 500, 200));
        assertEquals(3, BaseServletUtil.normalizePageSize(3, 500, 200));
    }

    // ---------- parsePage（request 形态） ----------

    @Test
    void parsePageFallsBackToFirstPageOnMissingBlankOrIllegal() {
        assertEquals(1, BaseServletUtil.parsePage(reqWithPage(null)));
        assertEquals(1, BaseServletUtil.parsePage(reqWithPage("")));
        assertEquals(1, BaseServletUtil.parsePage(reqWithPage("   ")));
        assertEquals(1, BaseServletUtil.parsePage(reqWithPage("abc")));
        assertEquals(1, BaseServletUtil.parsePage(reqWithPage("0")));
        assertEquals(1, BaseServletUtil.parsePage(reqWithPage("-3")));
    }

    @Test
    void parsePageKeepsPositiveValue() {
        assertEquals(5, BaseServletUtil.parsePage(reqWithPage("5")));
    }

    // ---------- parsePageSize（request 形态，三参为 T19 起唯一重载） ----------

    @Test
    void parsePageSizeFallsBackToDomainEnvelopeWhenAbsentBlankOrIllegal() {
        assertEquals(DEFAULT, BaseServletUtil.parsePageSize(reqWithPageSize(null), MAX, DEFAULT));
        assertEquals(DEFAULT, BaseServletUtil.parsePageSize(reqWithPageSize(""), MAX, DEFAULT));
        assertEquals(DEFAULT, BaseServletUtil.parsePageSize(reqWithPageSize("abc"), MAX, DEFAULT));
        assertEquals(DEFAULT, BaseServletUtil.parsePageSize(reqWithPageSize("0"), MAX, DEFAULT));
    }

    @Test
    void parsePageSizeCapsExplicitValueAtDomainMax() {
        assertEquals(MAX, BaseServletUtil.parsePageSize(reqWithPageSize("999"), MAX, DEFAULT));
        assertEquals(500, BaseServletUtil.parsePageSize(reqWithPageSize("999"), 500, 200));
    }

    @Test
    void parsePageSizeKeepsExplicitValueBelowMax() {
        // 51 在 T19 前会被公共 cap 50 截到 50；T19 起域级上限 100 → 原样回显
        assertEquals(51, BaseServletUtil.parsePageSize(reqWithPageSize("51"), MAX, DEFAULT));
        // 小信封仍生效（pytest 三域"页间不重不漏"的验证能力依赖此路径）
        assertEquals(1, BaseServletUtil.parsePageSize(reqWithPageSize("1"), MAX, DEFAULT));
    }
}
