package com.itheima.util;

import java.util.logging.Logger;

/**
 * 审计记录器（日志体系第二张清单 T8 / NEEDS 4.0 决策节）：管理端写操作与用户敏感变更的
 * **成功路径**留痕，回答"谁、何时、做了什么"。
 *
 * <p><b>形态</b>（立项已拍板）：补点在 **controller 层**（管理端 4 个写操作，操作者取自
 * {@code request.getAttribute("userId")}——{@code LoginFilter} 已放入、{@code AuthFilter} 亦在读同一
 * attribute，**零签名改动**）+ **用户侧 service 层**（操作者即方法入参 {@code userId}）；记录
 * **只落文件** {@code audit.log}（**不落库、不新增表、无 DDL**）。输出端由 {@link LogUtil} 规格表装配
 * （专属 logger {@link LogUtil#AUDIT_LOGGER_NAME} + {@code useParentHandlers=false}）→ 审计行
 * **不进 {@code system.log}、也不进控制台，与 access 输出端同构（D9 底座的首个实践）。
 *
 * <p><b>行形态</b>（复用 {@link LogFormatter} 前缀，时间与请求标识不再在 msg 内重复）：
 * <pre>{@code ts=... level=INFO logger=audit req=... msg=action=admin.content.hide operatorId=13 target=contentId:42 result=success}</pre>
 * 字段口径 = 操作者（{@code operatorId}）· 操作（{@code action}）· 对象（{@code target}，形如
 * {@code contentId:42}）· 结果（{@code result}）· 时间（前缀 {@code ts=}，3 位毫秒 + 带冒号时区）·
 * 请求关联（前缀 {@code req=}，同线程自动带，可与 access/system 行串联）。
 *
 * <p><b>三条口径</b>：
 * <ol>
 *   <li><b>只记成功</b>：失败路径由既有 {@code SEVERE} 记录承载（源头记录 + {@code ExceptionFilter}
 *       结论行），审计不重复记——避免"同一失败两条记录"（LOG_CONVENTION §3.1 附加纪律 2）。</li>
 *   <li><b>审计是旁路</b>：写入失败一律吞掉并降级，**绝不影响业务**（与"缓存失败不导致业务失败"同族）。
 *       捕获 {@code Exception} 而非 {@code Throwable}——{@code Error}（OOM/栈溢出）不属"审计写失败"
 *       该吞的范畴，且此时业务本身已不可用。JUL 自带 Handler 内部另有 ErrorManager 兜底 IO 故障，
 *       本守卫针对自定义/第三方 Handler 与装配期异常（由 {@code AuditLogTest} 用抛异常的 Handler 强制触达）。</li>
 *   <li><b>不记敏感值</b>：{@code target} 只放**对象标识**（contentId / mediaId / commentId / userId），
 *       不放变更后的值（手机号属 PII、用户名无记录必要）；请求体 / query 串 / 密码 / token 一律不落盘。</li>
 * </ol>
 */
public final class AuditLog {

    /** 审计输出端专属 logger（{@link LogUtil} 静态块按规格表装配；只落 audit.log，阈值固定 INFO）。 */
    private static final Logger AUDIT_LOGGER = LogUtil.getAuditLogger();

    /** 结果标记：本类当前只承载成功路径，字段保留以固定行形态（将来若加失败路径不必改格式）。 */
    static final String RESULT_SUCCESS = "success";

    private AuditLog() {
    }

    /**
     * 记一条**成功**审计记录。7 个操作点各调用一次、各恰好一条。
     *
     * @param action     操作名（稳定字面量）：管理端 = {@code admin.<域>.<动作>}，用户侧 = {@code user.<方法语义>}
     * @param operatorId 操作者 userId：管理端 = {@code (Long) req.getAttribute("userId")}（{@code AuthFilter}
     *                   对 {@code /api/admin} 已保证非空，理论不可达 null）；用户侧 = 方法入参本身
     * @param target     操作对象标识，形如 {@code contentId:42} / {@code mediaId:7} / {@code userId:13}
     */
    public static void success(String action, Long operatorId, String target) {
        try {
            AUDIT_LOGGER.info(buildLine(action, operatorId, target));
        } catch (Exception ignored) {
            // 旁路降级：审计写失败不得影响业务（红线）。此处**不再补记日志**，避免在异常路径上
            // 再触发一次日志写入而放大故障；JUL Handler 的 ErrorManager 负责上报底层 IO 故障。
        }
    }

    /**
     * 审计行 msg 内容（**纯函数**，JUnit 直测）：{@code action=… operatorId=… target=… result=success}。
     *
     * <p>{@code operatorId == null}（理论不可达，见 {@link #success}）→ {@code -}，与访问日志的
     * {@code userId=-} 同口径；时间 / 请求标识由 {@link LogFormatter} 前缀提供，不在本行重复。
     */
    static String buildLine(String action, Long operatorId, String target) {
        return "action=" + action
                + " operatorId=" + (operatorId == null ? "-" : operatorId)
                + " target=" + target
                + " result=" + RESULT_SUCCESS;
    }
}
