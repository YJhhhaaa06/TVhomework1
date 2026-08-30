package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.ForbiddenException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.model.cache.CommentCacheDTO;
import com.itheima.model.cache.ContentCacheDTO;
import com.itheima.model.command.CommentCommand;
import com.itheima.model.vo.CommentVO;
import com.itheima.util.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class CommentServiceTest {

    private CommentDao commentDao;
    private ContentDao contentDao;
    private ContentCacheManager contentCacheManager;
    private TransactionTemplate tt;
    private Connection conn;
    private CommentService service;
    @SuppressWarnings("rawtypes")
    private final ArgumentCaptor<CommentCacheDTO> commentCaptor = ArgumentCaptor.forClass(CommentCacheDTO.class);

    @BeforeEach
    void setUp() throws Exception {
        commentDao = mock(CommentDao.class);
        contentDao = mock(ContentDao.class);
        contentCacheManager = mock(ContentCacheManager.class);
        tt = mock(TransactionTemplate.class);
        conn = mock(Connection.class);
        service = new CommentService(commentDao, contentDao, contentCacheManager, tt);
        when(tt.execute(any(TransactionTemplate.TransactionAction.class))).thenAnswer(inv -> {
            TransactionTemplate.TransactionAction<?> action = inv.getArgument(0);
            return action.execute(conn);
        });
    }

    private CommentCommand rootCommand() {
        return new CommentCommand(3L, 7L, "hello", null);
    }

    @Test
    void addRootCommentSuccessUpdatesCountsAndCache() throws SQLException {
        CommentCommand command = rootCommand();
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.addComment(conn, 3L, 7L, "hello", null, null)).thenReturn(5L);
        CommentCacheDTO saved = new CommentCacheDTO("alice", 5L, 3L, 7L, "hello", null, 0);
        when(commentDao.findCommentById(conn, 5L)).thenReturn(saved);

        service.addComment(command);

        verify(contentDao).updateCommentCount(conn, 3L, 1);
        verify(contentCacheManager).updateContentCommentCount(3L, 1);
        verify(contentCacheManager).addCommentToCache(3L, saved, null);
    }

    @Test
    void addCommentWhenCommentsDisabledThrowsConflict() throws SQLException {
        CommentCommand command = rootCommand();
        ContentCacheDTO dto = new ContentCacheDTO();
        dto.setCommentEnabled(false);
        when(contentCacheManager.getContentFromCache(3L)).thenReturn(dto);

        assertThrows(ConflictException.class, () -> service.addComment(command));
        verify(commentDao, never()).addComment(any(), anyLong(), anyLong(), anyString(), any(), any());
    }

    @Test
    void addCommentContentMissingThrowsNotFound() throws SQLException {
        when(contentDao.isContentExist(conn, 3L)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.addComment(rootCommand()));
    }

    @Test
    void addReplyWrongParentThrowsConflict() throws SQLException {
        CommentCommand command = rootCommand();
        command.setParentId(99L);
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.isCommentExist(conn, 99L)).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.addComment(command));
    }

    @Test
    void addReplyToReplyResolvesToMainFloor() throws SQLException {
        CommentCommand command = rootCommand();
        command.setParentId(99L);
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.isCommentExist(conn, 99L)).thenReturn(true);
        // 被回复的 99 是楼内回复（其主楼为 5，作者 8）=> 上溯挂主楼 5 且记录 @ 引用 8
        CommentCacheDTO reply = new CommentCacheDTO("bob", 99L, 3L, 8L, "child", 5L, 0);
        when(commentDao.findCommentById(conn, 99L)).thenReturn(reply);
        when(commentDao.addComment(conn, 3L, 7L, "hello", 5L, 8L)).thenReturn(100L);
        CommentCacheDTO saved = new CommentCacheDTO("alice", 100L, 3L, 7L, "hello", 5L, 0);
        when(commentDao.findCommentById(conn, 100L)).thenReturn(saved);

        service.addComment(command);

        verify(commentDao).addComment(conn, 3L, 7L, "hello", 5L, 8L);
        verify(contentCacheManager).addCommentToCache(3L, saved, 5L);
    }

    @Test
    void addReplyToReplySetsReplyToUserId() throws SQLException {
        CommentCommand command = rootCommand();
        command.setParentId(99L);
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.isCommentExist(conn, 99L)).thenReturn(true);
        // 被回复的 99 是楼内回复（其主楼为 5，作者 8）=> 上溯挂主楼 5 且 @ 目标为 8
        CommentCacheDTO reply = new CommentCacheDTO("bob", 99L, 3L, 8L, "child", 5L, 0);
        when(commentDao.findCommentById(conn, 99L)).thenReturn(reply);
        when(commentDao.addComment(conn, 3L, 7L, "hello", 5L, 8L)).thenReturn(100L);
        CommentCacheDTO saved = new CommentCacheDTO("alice", 100L, 3L, 7L, "hello", 5L, 0);
        saved.setReplyToUserId(8L);
        saved.setReplyToUsername("bob");
        when(commentDao.findCommentById(conn, 100L)).thenReturn(saved);

        service.addComment(command);

        verify(commentDao).addComment(conn, 3L, 7L, "hello", 5L, 8L);
        ArgumentCaptor<Long> parentIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(contentCacheManager).addCommentToCache(eq(3L), commentCaptor.capture(), parentIdCaptor.capture());
        assertEquals(5L, parentIdCaptor.getValue());
        assertEquals(8L, commentCaptor.getValue().getReplyToUserId());
        assertEquals("bob", commentCaptor.getValue().getReplyToUsername());
    }

    @Test
    void addReplyToDeletedCommentThrowsConflict() throws SQLException {
        CommentCommand command = rootCommand();
        command.setParentId(99L);
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.isCommentExist(conn, 99L)).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.addComment(command));
    }

    // ===== 删除：用户自删 =====

    @Test
    void deleteOwnMainFloorCascadesAndDecrements() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        CommentCacheDTO main = new CommentCacheDTO("alice", 9L, 3L, 7L, "root", null, 0);
        when(commentDao.findCommentById(conn, 9L)).thenReturn(main);
        when(commentDao.countFloorReplies(conn, 9L)).thenReturn(3);

        service.deleteCommentByUser(9L, 7L);

        verify(commentDao).softDeleteFloor(conn, 9L);
        verify(contentDao).updateCommentCount(conn, 3L, -4);
        verify(contentCacheManager).updateContentCommentCount(3L, -4);
        verify(contentCacheManager).removeCommentFromCache(3L, 9L, true);
    }

    @Test
    void deleteMainFloorCountsRepliesBeforeSoftDelete() throws SQLException {
        // 回归守卫：必须先计数后软删，否则 countFloorReplies(is_deleted=0) 会数到 0 导致计数少扣
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        CommentCacheDTO main = new CommentCacheDTO("alice", 9L, 3L, 7L, "root", null, 0);
        when(commentDao.findCommentById(conn, 9L)).thenReturn(main);

        service.deleteCommentByUser(9L, 7L);

        InOrder inOrder = inOrder(commentDao);
        inOrder.verify(commentDao).countFloorReplies(conn, 9L);
        inOrder.verify(commentDao).softDeleteFloor(conn, 9L);
    }

    @Test
    void deleteOwnReplyOnlyDeletesSelf() throws SQLException {
        when(commentDao.isCommentExist(conn, 10L)).thenReturn(true);
        CommentCacheDTO reply = new CommentCacheDTO("bob", 10L, 3L, 7L, "reply", 9L, 0);
        when(commentDao.findCommentById(conn, 10L)).thenReturn(reply);

        service.deleteCommentByUser(10L, 7L);

        verify(commentDao).softDeleteOne(conn, 10L);
        verify(contentDao).updateCommentCount(conn, 3L, -1);
        verify(contentCacheManager).removeCommentFromCache(3L, 10L, false);
    }

    @Test
    void deleteOthersCommentThrowsForbidden() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        CommentCacheDTO main = new CommentCacheDTO("alice", 9L, 3L, 7L, "root", null, 0);
        when(commentDao.findCommentById(conn, 9L)).thenReturn(main);

        assertThrows(ForbiddenException.class, () -> service.deleteCommentByUser(9L, 8L));
        verify(commentDao, never()).softDeleteFloor(any(), anyLong());
    }

    @Test
    void deleteMissingCommentThrowsNotFound() throws SQLException {
        when(commentDao.isCommentExist(conn, 999L)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.deleteCommentByUser(999L, 7L));
    }

    // ===== 删除：管理员 =====

    @Test
    void adminCanDeleteAnyComment() throws SQLException {
        when(commentDao.isCommentExist(conn, 9L)).thenReturn(true);
        CommentCacheDTO main = new CommentCacheDTO("alice", 9L, 3L, 7L, "root", null, 0);
        when(commentDao.findCommentById(conn, 9L)).thenReturn(main);

        service.deleteCommentByAdmin(9L);

        verify(commentDao).softDeleteFloor(conn, 9L);
        verify(contentCacheManager).removeCommentFromCache(3L, 9L, true);
    }

    @Test
    void adminDeleteMissingCommentThrowsNotFound() throws SQLException {
        when(commentDao.isCommentExist(conn, 999L)).thenReturn(false);

        assertThrows(NotFoundException.class, () -> service.deleteCommentByAdmin(999L));
    }

    @Test
    void addCommentSqlErrorThrowsServerException() throws SQLException {
        CommentCommand command = rootCommand();
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.addComment(conn, 3L, 7L, "hello", null, null))
                .thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.addComment(command));
    }

    @Test
    void convertToCommentVOListBuildsTreeWithLikedFlags() {
        CommentCacheDTO root = new CommentCacheDTO("alice", 1L, 3L, 7L, "root", null, 5);
        CommentCacheDTO child = new CommentCacheDTO("bob", 2L, 3L, 8L, "child", 1L, 2);
        child.setReplyToUserId(8L);
        child.setReplyToUsername("bob");
        root.setChildren(new ArrayList<>(List.of(child)));

        List<CommentVO> result = service.convertToCommentVOList(
                List.of(root), Map.of(1L, true, 2L, false));

        assertEquals(1, result.size());
        assertTrue(result.get(0).getIsLiked());
        assertEquals(1, result.get(0).getChildren().size());
        CommentVO childVO = (CommentVO) result.get(0).getChildren().get(0);
        assertFalse(childVO.getIsLiked());
        assertEquals(8L, childVO.getReplyToUserId());
        assertEquals("bob", childVO.getReplyToUsername());
    }
}
