package com.itheima.service;

import com.itheima.dao.CommentDao;
import com.itheima.dao.ContentDao;
import com.itheima.exception.ConflictException;
import com.itheima.exception.NotFoundException;
import com.itheima.exception.ServerException;
import com.itheima.model.cache.CommentCacheDTO;
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

class CommentServiceTest {

    private CommentDao commentDao;
    private ContentDao contentDao;
    private ContentCacheManager contentCacheManager;
    private TransactionTemplate tt;
    private Connection conn;
    private CommentService service;

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
        when(commentDao.addComment(conn, 3L, 7L, "hello", null)).thenReturn(5L);
        CommentCacheDTO saved = new CommentCacheDTO("alice", 5L, 3L, 7L, "hello", null, 0);
        when(commentDao.findCommentById(conn, 5L)).thenReturn(saved);

        service.addComment(command);

        verify(contentDao).updateCommentCount(conn, 3L, 1);
        verify(contentCacheManager).updateContentCommentCount(3L, 1);
        verify(contentCacheManager).addCommentToCache(3L, saved, null);
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
        when(commentDao.isParentIdCorrect(conn, 99L, 3L)).thenReturn(false);

        assertThrows(ConflictException.class, () -> service.addComment(command));
    }

    @Test
    void addCommentSqlErrorThrowsServerException() throws SQLException {
        CommentCommand command = rootCommand();
        when(contentDao.isContentExist(conn, 3L)).thenReturn(true);
        when(commentDao.addComment(conn, 3L, 7L, "hello", null))
                .thenThrow(new SQLException("db down"));

        assertThrows(ServerException.class, () -> service.addComment(command));
    }

    @Test
    void convertToCommentVOListBuildsTreeWithLikedFlags() {
        CommentCacheDTO root = new CommentCacheDTO("alice", 1L, 3L, 7L, "root", null, 5);
        CommentCacheDTO child = new CommentCacheDTO("bob", 2L, 3L, 8L, "child", 1L, 2);
        root.setChildren(new ArrayList<>(List.of(child)));

        List<CommentVO> result = service.convertToCommentVOList(
                List.of(root), Map.of(1L, true, 2L, false));

        assertEquals(1, result.size());
        assertTrue(result.get(0).getIsLiked());
        assertEquals(1, result.get(0).getChildren().size());
        assertFalse(((CommentVO) result.get(0).getChildren().get(0)).getIsLiked());
    }
}
