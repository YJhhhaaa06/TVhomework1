package com.itheima.content.dao;

import com.itheima.ioc.annotation.Component;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.admin.model.vo.AdminContentVO;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContentDao {

    public long addContent(Connection conn, long userId, int type, String title, String description, int categoryId) throws SQLException {
        String sql = "insert into content (user_id, title,type, description,category_id) values (?, ?, ?,?,?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, title);
            pstmt.setInt(3, type);
            pstmt.setString(4, description);
            pstmt.setInt(5, categoryId);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    throw new SQLException("获取 contentID 失败");
                }
            }
        }
    }


    public boolean isContentExist(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM content WHERE id = ? AND is_deleted = 0";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
            return false;
        }
    }

    public ContentCacheDTO findContent(Connection conn, long contentId) throws SQLException {
        String sql = """
                SELECT
                   c.id,
                   c.title,
                   c.description,
                   c.type,
                   c.category_id,
                   c.comment_count,
                   c.like_count,
                   c.comment_enabled,
                   c.create_time,
                   u.username,
                   u.id AS user_id
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE c.id=? AND is_deleted =0
               """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, contentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return buildContentCacheDTO(rs);
                } else {
                    return null;
                }
            }
        }
    }

    /**
     * 按 id 集合批量查询内容（第五期 T2 装载合并）：列与 {@link #findContent} 完全一致
     * （JOIN users 取 authorName），仅多一层 {@code id IN (...)} 过滤，供批量缓存读的
     * miss/降级装载一趟收敛（替代逐条 findContent 的 N 次往返）。
     *
     * <p>无匹配行不出现在结果里（调用方按"缺失 = 确认无数据"处理）；空/ null 入参返回空列表
     * （不发 SQL）。查询顺序不保证，调用方按 id 归位。
     */
    public List<ContentCacheDTO> findContentsByIds(Connection conn, Collection<Long> contentIds) throws SQLException {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT
                   c.id,
                   c.title,
                   c.description,
                   c.type,
                   c.category_id,
                   c.comment_count,
                   c.like_count,
                   c.comment_enabled,
                   c.create_time,
                   u.username,
                   u.id AS user_id
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE c.is_deleted = 0 AND c.id IN (""");
        for (int i = 0; i < contentIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        List<ContentCacheDTO> list = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long contentId : contentIds) {
                pstmt.setLong(idx++, contentId);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(buildContentCacheDTO(rs));
                }
            }
        }
        return list;
    }

    public List<ContentCacheDTO> findAllContent(Connection conn) throws SQLException {
        List<ContentCacheDTO> list = new ArrayList<>();
        String sql = """
                SELECT
                   c.id,
                   c.title,
                   c.description,
                   c.type,
                   c.category_id,
                   c.comment_count,
                   c.like_count,
                   c.comment_enabled,
                   c.create_time,
                   u.username,
                   u.id AS user_id
               FROM content c
               JOIN users u ON c.user_id = u.id
               WHERE is_deleted =0
               ORDER BY c.create_time DESC, c.id DESC
               """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet res = pstmt.executeQuery()) {
            while (res.next()) {
                list.add(buildContentCacheDTO(res));
            }
        }
        return list;
    }

    // 查询全部未删除内容 id（运维扫描用）
    public List<Long> findAllContentIds(Connection conn) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "select id from content where is_deleted = 0 order by id";
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    // 更新内容的媒体完整性聚合状态
    public int updateFileExists(Connection conn, long contentId, boolean exists, Timestamp lastVerifyTime) throws SQLException {
        String sql = "update content set file_exists=?, last_verify_time=? where id=?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, exists ? 1 : 0);
            pstmt.setTimestamp(2, lastVerifyTime);
            pstmt.setLong(3, contentId);
            return pstmt.executeUpdate();
        }
    }



    public List<Long> findContentIdsByUser(Connection conn, long userId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT c.id FROM content c WHERE c.user_id = ? AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    /**
     * 按作者**分页窗口**取内容 id（feed2-25 T25，治 `U-24`）：替代"全量 id 读 + 内存切片"。
     *
     * <p><b>为什么用 contentId 排序</b>：{@code content.id} 自增 ⇒ id 越大发布越晚，与
     * {@link #findContentIdsByUser} 的 {@code ORDER BY create_time DESC, id DESC} 运行期次序一致
     * （同秒并列时 id 即 tie-break；T22 窗口已用只读 SQL 在 3306 / 3307 实测"每作者内 id 序与
     * create_time, id 序**名次零不一致**"）。
     *
     * <p><b>不新增索引</b>：`idx_user_id (user_id)` 的 InnoDB 二级索引物理为 {@code (user_id, id)}
     * 升序 ⇒ {@code WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?} 反向索引扫描、**免 filesort**，
     * 成本 ∝ {@code offset + pageSize}（而非该作者内容总量）；若沿用 create_time 排序则需对该作者
     * 全部行 filesort（无 {@code (user_id, create_time)} 索引）。
     *
     * <p>⚠ 与 {@link #findContentIdsByUser}（全量；{@code ContentCache} 改名级联失效仍需全量）并存，
     * 调用方按需选择：分页装载用本方法，'取该作者全部 id' 用原方法。
     *
     * @param offset   起始偏移（&lt; 0 不发 SQL，返回空列表）
     * @param pageSize 页大小（&lt;= 0 不发 SQL，返回空列表）
     */
    public List<Long> findContentIdsByUserWindow(Connection conn, long userId,
                                                 int offset, int pageSize) throws SQLException {
        if (offset < 0 || pageSize <= 0) {
            return Collections.emptyList();
        }
        String sql = "SELECT c.id FROM content c WHERE c.user_id = ? AND c.is_deleted = 0"
                + " ORDER BY c.id DESC LIMIT ? OFFSET ?";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, pageSize);
            pstmt.setInt(3, offset);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    public List<Long> findContentIdsByUsers(Connection conn, List<Long> userIds, int offset, int pageSize) throws SQLException {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT c.id FROM content c WHERE c.user_id IN (");
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(") AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC LIMIT ?, ?");

        List<Long> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < userIds.size(); i++) {
                pstmt.setLong(i + 1, userIds.get(i));
            }
            pstmt.setInt(userIds.size() + 1, offset);
            pstmt.setInt(userIds.size() + 2, pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    /**
     * 每作者各取最近 {@code perAuthorLimit} 条内容 id（feed2-22 T22 窗口重算）：
     * 每关注作者一个 {@code (SELECT … ORDER BY id DESC LIMIT ?)} 分支，`UNION ALL` 成一条语句
     * （一趟往返；调用方按 {@code feed.rebuild.authorBatch}（feed3-T29）切分作者列表以约束 SQL 长度）。
     *
     * <p><b>排序口径 = contentId（自增单调）</b>：{@code content.id} 自增 ⇒ id 越大发布越晚，
     * 与拉模式 {@code ORDER BY create_time DESC, id DESC} 的运行期次序一致（同秒并列时
     * id 即 tie-break），且与"排序 / 归并 / 裁剪全按 contentId"的收件箱层口径同源
     * （{@code feed_inbox} 不存时间字段）。
     *
     * <p><b>不新增索引</b>：`idx_user_id (user_id)` 的 InnoDB 二级索引物理为 {@code (user_id, id)}
     * 升序 ⇒ `WHERE user_id = ? ORDER BY id DESC LIMIT ?` 反向索引扫描、免 filesort；
     * 且每分支 `LIMIT` 可提前终止（无软删时实际取数 ≈ K 行）。⚠️ `is_deleted` 不在该索引中，
     * 需回表过滤 ⇒ **最坏上界仍为该作者的内容量**（大量软删时扫描放大），不是纯粹 ∝ K。
     *
     * <p>⚠️ `UNION ALL` 的外层顺序不保证（由调用方归并），且**不指定 author 归属**
     * （每条 id 已唯一确定作者，调用方无需按作者分组）。
     *
     * @param userIds        作者 id 列表（空 / null 不发 SQL，返回空列表）
     * @param perAuthorLimit 每作者保留条数 K（&lt;= 0 不发 SQL，返回空列表）
     */
    public List<Long> findRecentContentIdsByUsers(Connection conn, List<Long> userIds,
                                                  int perAuthorLimit) throws SQLException {
        if (userIds == null || userIds.isEmpty() || perAuthorLimit <= 0) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append("(SELECT id FROM content WHERE user_id = ? AND is_deleted = 0"
                    + " ORDER BY id DESC LIMIT ?)");
        }
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long userId : userIds) {
                pstmt.setLong(idx++, userId);
                pstmt.setInt(idx++, perAuthorLimit);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    /**
     * 每作者各取最近 {@code perAuthorLimit} 条内容 id，**并带回作者归属**（feed2-23 T23 大V发件箱批量回源）。
     *
     * <p>与 {@link #findRecentContentIdsByUsers} 的唯一差别：本方法在分支里多选一列
     * {@code user_id AS author_id}（`idx_user_id` 物理为 {@code (user_id, id)}，该列随索引即可取到），
     * 于是**一趟查询**即可按作者切分结果、直接用于逐作者缓存回填——避免"不回源就不知道 id 归谁"。
     *
     * <p><b>排序口径 = contentId 降序</b>（同 {@link #findRecentContentIdsByUsers}，
     * 与 {@code feed_inbox} 不存时间字段的收件箱层口径同源）；⚠️ `UNION ALL` 的**外层顺序不保证**，
     * 故本方法在 Java 侧对每个作者的结果**显式降序重排**，不依赖引擎的拼接顺序。
     *
     * <p>⚠️ `is_deleted` 不在 `idx_user_id` 中、需回表过滤 ⇒ 每分支最坏上界为该作者的内容量
     * （同 {@link #findRecentContentIdsByUsers} 的登记口径）。
     *
     * @param authorIds      作者 id 列表（空 / null / {@code perAuthorLimit <= 0} 不发 SQL，返回空映射）
     * @param perAuthorLimit 每作者保留条数 N
     * @return 作者 id → 该作者最近 N 条 contentId（降序）；**无内容的作者不出现在返回映射中**
     */
    public Map<Long, List<Long>> findRecentContentIdsByAuthor(Connection conn, List<Long> authorIds,
                                                             int perAuthorLimit) throws SQLException {
        if (authorIds == null || authorIds.isEmpty() || perAuthorLimit <= 0) {
            return Collections.emptyMap();
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < authorIds.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append("(SELECT user_id AS author_id, id FROM content WHERE user_id = ? AND is_deleted = 0"
                    + " ORDER BY id DESC LIMIT ?)");
        }
        Map<Long, List<Long>> byAuthor = new LinkedHashMap<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long authorId : authorIds) {
                pstmt.setLong(idx++, authorId);
                pstmt.setInt(idx++, perAuthorLimit);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    byAuthor.computeIfAbsent(rs.getLong("author_id"), k -> new ArrayList<>())
                            .add(rs.getLong("id"));
                }
            }
        }
        // UNION ALL 外层顺序不保证 ⇒ 显式降序重排（同时保证每作者内不重复）
        for (List<Long> ids : byAuthor.values()) {
            ids.sort(Collections.reverseOrder());
        }
        return byAuthor;
    }

    public int countContentByUsers(Connection conn, List<Long> userIds) throws SQLException {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM content WHERE user_id IN (");
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(") AND is_deleted = 0");

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < userIds.size(); i++) {
                pstmt.setLong(i + 1, userIds.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    public int countContentByUser(Connection conn, long userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM content WHERE user_id = ? AND is_deleted = 0";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }



    public List<Long> keywordSearchInBrief(Connection conn, String keyword, int page, int pageSize) throws SQLException {
        String kw = keyword.trim();
        String sql;
        boolean singleChar = kw.length() == 1;
        if (singleChar) {
            sql = "SELECT c.id FROM content c WHERE c.title LIKE ? AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC LIMIT ?,?";
        } else {
            sql = """
                    SELECT c.id FROM content c
                    WHERE MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE)
                      AND c.is_deleted = 0
                    ORDER BY c.create_time DESC, c.id DESC
                    LIMIT ?,?
                    """;
        }
        List<Long> contentIdList = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (singleChar) {
                pstmt.setString(1, "%" + kw + "%");
            } else {
                pstmt.setString(1, kw);
            }
            int offset = (page - 1) * pageSize;
            pstmt.setInt(2, offset);
            pstmt.setInt(3, pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    contentIdList.add(rs.getLong(1));
                }
                return contentIdList;
            }
        }
    }

    public int countKeywordSearch(Connection conn, String keyword) throws SQLException {
        String kw = keyword.trim();
        String sql;
        boolean singleChar = kw.length() == 1;
        if (singleChar) {
            sql = "SELECT COUNT(*) FROM content c WHERE c.title LIKE ? AND c.is_deleted = 0";
        } else {
            sql = "SELECT COUNT(*) FROM content c WHERE MATCH(c.title, c.description) AGAINST (? IN NATURAL LANGUAGE MODE) AND c.is_deleted = 0";
        }
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if (singleChar) {
                pstmt.setString(1, "%" + kw + "%");
            } else {
                pstmt.setString(1, kw);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }



    public void updateLikeCount(Connection conn, Long contentId, int delta) throws SQLException {
        String sql = "UPDATE content SET like_count = like_count + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, contentId);
            ps.executeUpdate();
        }
    }

    public void updateCommentCount(Connection conn, Long contentId, int delta) throws SQLException {
        String sql = "UPDATE content SET comment_count = comment_count + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, contentId);
            ps.executeUpdate();
        }
    }

    /** 作者开关评论区（1-开, 0-关） */
    public int updateCommentEnabled(Connection conn, long contentId, boolean enabled) throws SQLException {
        String sql = "UPDATE content SET comment_enabled = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, contentId);
            return ps.executeUpdate();
        }
    }

    /** 作者编辑作品信息：更新标题与简介（A3，全文索引由 MySQL 自动维护） */
    public int updateContentInfo(Connection conn, long contentId, String title, String description) throws SQLException {
        String sql = "UPDATE content SET title = ?, description = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setLong(3, contentId);
            return ps.executeUpdate();
        }
    }

    /** 作者删除作品：软删（A1，复用现有 is_deleted 字段，无 DDL） */
    public int softDeleteContent(Connection conn, long contentId) throws SQLException {
        String sql = "UPDATE content SET is_deleted = 1 WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            return ps.executeUpdate();
        }
    }

    /** 读取内容当前 is_deleted 状态（A2）：0 正常 / 1 作者删除 / 2 管理员下架；不存在返回 -1 */
    public int getContentStatus(Connection conn, long contentId) throws SQLException {
        String sql = "SELECT is_deleted FROM content WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, contentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return -1;
            }
        }
    }

    /** 设置内容 is_deleted 状态（A2）：0 正常 / 1 作者删除 / 2 管理员下架 */
    public int updateContentDeletedState(Connection conn, long contentId, int state) throws SQLException {
        String sql = "UPDATE content SET is_deleted = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, state);
            ps.setLong(2, contentId);
            return ps.executeUpdate();
        }
    }

    /** 管理端内容清单（A2）：含正常与已下架，不含已删除(1)的内容 */
    public List<AdminContentVO> findContentForAdmin(Connection conn) throws SQLException {
        List<AdminContentVO> list = new ArrayList<>();
        String sql = """
                SELECT c.id, c.title, c.type, c.is_deleted, u.username AS author_name
                FROM content c
                JOIN users u ON c.user_id = u.id
                WHERE c.is_deleted IN (0, 2)
                ORDER BY c.create_time DESC, c.id DESC
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(buildAdminContent(rs));
            }
        }
        return list;
    }

    // ===== ResultSet → 对象映射（T14：由 com.itheima.dao.ResultMap 按域拆分下沉，方法体逐行不变）=====

    private static ContentCacheDTO buildContentCacheDTO(ResultSet rs) throws SQLException {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(rs.getLong("id"));
        dto.setAuthorId(rs.getLong("user_id"));
        dto.setType(rs.getInt("type"));
        dto.setTitle(rs.getString("title"));
        dto.setDescription(rs.getString("description"));
        dto.setCategoryId(rs.getInt("category_id"));
        dto.setCommentCount(rs.getInt("comment_count"));
        dto.setLikeCount(rs.getInt("like_count"));
        dto.setCommentEnabled(rs.getInt("comment_enabled") != 0);
        dto.setAuthorName(rs.getString("username"));
        dto.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        return dto;
    }

    /** 管理端内容清单（A2 审核下架）：hidden = is_deleted == 2 */
    private static AdminContentVO buildAdminContent(ResultSet rs) throws SQLException {
        AdminContentVO vo = new AdminContentVO();
        vo.setId(rs.getLong("id"));
        vo.setTitle(rs.getString("title"));
        vo.setType(rs.getInt("type"));
        vo.setAuthorName(rs.getString("author_name"));
        vo.setHidden(rs.getInt("is_deleted") == 2);
        return vo;
    }
}
