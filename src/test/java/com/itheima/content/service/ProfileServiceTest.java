package com.itheima.content.service;

import com.itheima.content.dao.ContentDao;
import com.itheima.follow.service.FollowCache;
import com.itheima.like.service.LikeService;
import com.itheima.user.dao.UserDao;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.content.model.cache.ContentCacheDTO;
import com.itheima.content.model.dto.PageResult;
import com.itheima.user.model.entity.User;
import com.itheima.content.model.vo.ContentVO;
import com.itheima.content.model.vo.ProfileVO;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileServiceTest {

    private UserDao userDao;
    private ContentDao contentDao;
    private FollowCache followCache;
    private ContentCache contentCache;
    private LikeService likeService;
    private TransactionTemplate tt;
    private Connection conn;
    private ProfileService service;

    @BeforeEach
    void setUp() throws Exception {
        userDao = mock(UserDao.class);
        contentDao = mock(ContentDao.class);
        followCache = mock(FollowCache.class);
        contentCache = mock(ContentCache.class);
        likeService = mock(LikeService.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new ProfileService(userDao, contentDao, followCache, contentCache, likeService, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
        // T8：profile 页内改批量读；默认空映射，用例内自行覆盖
        when(contentCache.getContentsBatch(anyList())).thenReturn(Collections.emptyMap());
    }

    /** getContentsBatch 桩（id → DTO，null 值=缓存 miss 跳过）。 */
    private void stubGetBatch(Map<Long, ContentCacheDTO> values) {
        when(contentCache.getContentsBatch(anyList())).thenReturn(values);
    }

    private User user() {
        // User(long id, String username, int followCount, int followerCount)
        return new User(7L, "alice", 20, 10);
    }

    private ContentCacheDTO dto(long id) {
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setId(id);
        dto.setAuthorId(7L);
        dto.setType(1);
        dto.setTitle("title" + id);
        dto.setCategoryId(1);
        return dto;
    }

    private ContentVO vo(long id) {
        ContentVO vo = new ContentVO();
        vo.setId(id);
        return vo;
    }

    @Test
    void getProfileUserNotFoundThrowsNotFound() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.getProfile(7L, 8L, 1, 10));
        verify(contentDao, never()).findContentIdsByUser(any(), anyLong());
    }

    @Test
    void getProfileNoContentAndNoCurrentUser() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(Collections.emptyList());

        ProfileVO profile = service.getProfile(7L, null, 1, 10);

        assertEquals(7L, profile.getUserId());
        assertEquals("alice", profile.getUsername());
        assertEquals(10, profile.getFollowerCount());
        assertEquals(20, profile.getFollowCount());
        assertNull(profile.getIsFollowed());
        assertTrue(profile.getContentPage().getList().isEmpty());
        assertEquals(0, profile.getContentPage().getTotal());
        verify(followCache, never()).isFollowing(anyLong(), anyLong());
        verify(likeService, never()).batchIsContentLiked(anyLong(), anyList());
    }

    @Test
    void getProfileNormalWithFollowAndLikedStatus() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L, 2L));
        ContentCacheDTO dto1 = dto(1L);
        ContentCacheDTO dto2 = dto(2L);
        stubGetBatch(Map.of(1L, dto1, 2L, dto2));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));
        when(contentCache.toContentVO(dto2)).thenReturn(vo(2L));
        when(followCache.isFollowing(8L, 7L)).thenReturn(true);
        when(likeService.batchIsContentLiked(8L, java.util.List.of(1L, 2L)))
                .thenReturn(java.util.Map.of(1L, true, 2L, false));

        ProfileVO profile = service.getProfile(7L, 8L, 1, 10);

        assertEquals(Boolean.TRUE, profile.getIsFollowed());
        PageResult<ContentVO> page = profile.getContentPage();
        assertEquals(2, page.getList().size());
        assertEquals(2, page.getTotal());
        assertEquals(1, page.getPage());
        assertEquals(10, page.getPageSize());
        assertTrue(page.getList().get(0).getIsLiked());
        assertFalse(page.getList().get(1).getIsLiked());
    }

    @Test
    void getProfileSkipsCacheMissAndQueriesLikedForSurvivors() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L, 2L));
        ContentCacheDTO dto2 = dto(2L);
        // 1 为缓存 miss（null）；Map.of 不允许 null，用 HashMap
        Map<Long, ContentCacheDTO> values = new HashMap<>();
        values.put(1L, null);
        values.put(2L, dto2);
        stubGetBatch(values);
        when(contentCache.toContentVO(dto2)).thenReturn(vo(2L));
        when(likeService.batchIsContentLiked(8L, java.util.List.of(2L)))
                .thenReturn(java.util.Map.of(2L, true));

        ProfileVO profile = service.getProfile(7L, 8L, 1, 10);

        assertEquals(1, profile.getContentPage().getList().size());
        assertEquals(2L, profile.getContentPage().getList().get(0).getId());
        assertTrue(profile.getContentPage().getList().get(0).getIsLiked());
    }

    @Test
    void getProfileSameUserSkipsFollowQueryButFillsLiked() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L));
        ContentCacheDTO dto1 = dto(1L);
        stubGetBatch(Map.of(1L, dto1));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));
        when(likeService.batchIsContentLiked(7L, java.util.List.of(1L)))
                .thenReturn(java.util.Map.of(1L, true));

        ProfileVO profile = service.getProfile(7L, 7L, 1, 10);

        assertNull(profile.getIsFollowed());
        assertTrue(profile.getContentPage().getList().get(0).getIsLiked());
        verify(followCache, never()).isFollowing(anyLong(), anyLong());
    }

    @Test
    void getProfileNoCurrentUserSkipsFollowAndLiked() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L));
        ContentCacheDTO dto1 = dto(1L);
        stubGetBatch(Map.of(1L, dto1));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));

        ProfileVO profile = service.getProfile(7L, null, 1, 10);

        assertNull(profile.getIsFollowed());
        assertFalse(profile.getContentPage().getList().get(0).getIsLiked());
        verify(followCache, never()).isFollowing(anyLong(), anyLong());
        verify(likeService, never()).batchIsContentLiked(anyLong(), anyList());
    }

    @Test
    void getProfileNullLikedMapLeavesLikedFalse() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L));
        ContentCacheDTO dto1 = dto(1L);
        stubGetBatch(Map.of(1L, dto1));
        when(contentCache.toContentVO(dto1)).thenReturn(vo(1L));
        when(likeService.batchIsContentLiked(8L, java.util.List.of(1L))).thenReturn(null);

        ProfileVO profile = service.getProfile(7L, 8L, 1, 10);

        assertFalse(profile.getContentPage().getList().get(0).getIsLiked());
    }

    @Test
    void getProfileOutOfRangePageReturnsEmptyPage() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L));

        ProfileVO profile = service.getProfile(7L, 8L, 3, 2);

        assertTrue(profile.getContentPage().getList().isEmpty());
        assertEquals(1, profile.getContentPage().getTotal());
        verify(contentCache, never()).getContent(anyLong());
    }

    @Test
    void getProfileMiddlePageSlicesCorrectly() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(java.util.List.of(1L, 2L, 3L));
        ContentCacheDTO dto3 = dto(3L);
        stubGetBatch(Map.of(3L, dto3));
        when(contentCache.toContentVO(dto3)).thenReturn(vo(3L));

        ProfileVO profile = service.getProfile(7L, 8L, 2, 2);

        assertEquals(1, profile.getContentPage().getList().size());
        assertEquals(3L, profile.getContentPage().getList().get(0).getId());
        assertEquals(3, profile.getContentPage().getTotal());
    }

    @Test
    void getProfileNotFollowedSetsFalse() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenReturn(Collections.emptyList());
        when(followCache.isFollowing(8L, 7L)).thenReturn(false);

        ProfileVO profile = service.getProfile(7L, 8L, 1, 10);

        assertEquals(Boolean.FALSE, profile.getIsFollowed());
    }

    @Test
    void getProfileUserQuerySqlErrorThrowsServerException() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getProfile(7L, 8L, 1, 10));
    }

    @Test
    void getProfileContentQuerySqlErrorThrowsServerException() throws SQLException {
        when(userDao.getUserForProfileById(conn, 7L)).thenReturn(user());
        when(contentDao.findContentIdsByUser(conn, 7L)).thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.getProfile(7L, 8L, 1, 10));
    }
}