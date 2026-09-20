package com.itheima.comment.controller;

import com.itheima.controller.BaseServlet;
import com.itheima.controller.BaseServletUtil;
import com.itheima.controller.RequestParser;
import com.itheima.content.model.command.CommandConverter;
import com.itheima.comment.model.command.CommentCommand;
import com.itheima.exception.ErrorCode;
import com.itheima.ioc.annotation.Inject;
import com.itheima.comment.service.CommentService;
import com.itheima.content.service.ContentService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

@WebServlet("/comment/*")
public class CommentController extends BaseServlet {
    /** T10-B：评论域 pageSize 上限（大 chunk 前端承载；公共 parsePageSize cap 50 不动）。 */
    private static final int COMMENT_PAGE_SIZE_MAX = 500;
    /**
     * T11-B：评论域**信封大小**（缺省值）——由后端域级常量决定，前端只传 {@code page}；
     * 镜像 follow 域 {@code FOLLOW_PAGE_SIZE_DEFAULT} 先例（取值沿用 T10-B 前端原本显式传的 200，
     * 故前端行为不变，只是"要多少条"的决定权从请求参数挪到后端）。
     */
    private static final int COMMENT_PAGE_SIZE_DEFAULT = 200;

    @Inject
    private CommentService commentService;
    @Inject
    private ContentService contentService;


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if(action==null){
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR,"action不能为空");
            return;
        }
        switch (action){
            case "/add":
                addComment(req,resp);
                break;
            case "/delete":
                deleteComment(req,resp);
                break;
            default:
                BaseServletUtil.writeError(resp,ErrorCode.PARAM_ERROR,"未识别功能");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getPathInfo();
        if (action == null) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "action不能为空");
            return;
        }
        if ("/show".equals(action)) {
            showComment(req, resp);
        } else if ("/replies".equals(action)) {
            repliesComment(req, resp);
        } else {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "未识别功能");
        }
    }




    protected void addComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        com.itheima.comment.model.dto.CommentDTO dto=RequestParser.parse(req, com.itheima.comment.model.dto.CommentDTO.class);
        Long userId = (Long) req.getAttribute("userId");
        dto.setUserId(userId);
        CommentCommand commentCommand= CommandConverter.commentToCommand(dto);
        commentService.addComment(commentCommand);
        BaseServletUtil.writeSuccess(resp,"评论成功");

    }
    protected void showComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String contentIdStr = req.getParameter("contentId");
        if (contentIdStr == null || contentIdStr.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "contentId不能为空");
            return;
        }
        long contentId = Long.parseLong(contentIdStr);
        Long userId = (Long) req.getAttribute("userId");
        // T8：显式区分"是否传了分页参数"——**缺省（都不传）维持全量数组返回**，与改造前逐字节一致
        // （既有调用方与 pytest 用例零破坏；不能用"默认 page=1&pageSize=50"代替，上限 50 会截断全量）；
        // 传任一分页参数 → 分页信封（主楼分页 + 楼中楼前 K 条）。
        // T11-B：分页解析 = 公共 parsePage + 域级「上限 500 / 信封 200」——前端传 page 即可，
        // 不再由前端决定"要多少条"（显式传 pageSize 仍生效，上限 500）。
        if (hasPagingParams(req)) {
            BaseServletUtil.writeSuccess(resp, contentService.getCommentsForContent(contentId, userId,
                    BaseServletUtil.parsePage(req),
                    // T10-B 域级上限 500；T11-B 域级信封 200（未传 pageSize 时用 200）
                    BaseServletUtil.parsePageSize(req, COMMENT_PAGE_SIZE_MAX, COMMENT_PAGE_SIZE_DEFAULT)));
        } else {
            List<?> comments = contentService.getCommentsForContent(contentId, userId);
            BaseServletUtil.writeSuccess(resp, comments);
        }
    }

    /** T10-B：展开某主楼全部回复（分页信封；未登录可看，AuthFilter 与 /show 同鉴权）。 */
    protected void repliesComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String rootIdStr = req.getParameter("rootId");
        if (rootIdStr == null || rootIdStr.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "rootId不能为空");
            return;
        }
        long rootId;
        try {
            rootId = Long.parseLong(rootIdStr);
        } catch (NumberFormatException e) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "rootId格式错误");
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        BaseServletUtil.writeSuccess(resp, commentService.getRepliesForRoot(rootId, userId,
                BaseServletUtil.parsePage(req),
                // T11-B：展开回复同样按域级信封 200 取页（前端只传 page）
                BaseServletUtil.parsePageSize(req, COMMENT_PAGE_SIZE_MAX, COMMENT_PAGE_SIZE_DEFAULT)));
    }

    /**
     * 是否显式传了分页参数（T8 缺省兼容判据，与 `FollowController` 同构）：
     * {@code page} 与 {@code pageSize} **任一**出现即视为分页请求。
     *
     * <p>不用"默认值是否等于 1/10"来判断——那无法区分"没传"与"显式传 page=1&pageSize=10"，
     * 也就无法保留"不传 → 全量返回"的既有行为。
     */
    private boolean hasPagingParams(HttpServletRequest req) {
        return req.getParameter("page") != null || req.getParameter("pageSize") != null;
    }

    /** 用户自删评论（软删除，不可恢复；AuthFilter 保证已登录） */
    protected void deleteComment(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String commentIdStr = req.getParameter("commentId");
        if (commentIdStr == null || commentIdStr.isEmpty()) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "commentId不能为空");
            return;
        }
        long commentId;
        try {
            commentId = Long.parseLong(commentIdStr);
        } catch (NumberFormatException e) {
            BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR, "commentId格式错误");
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        commentService.deleteCommentByUser(commentId, userId);
        BaseServletUtil.writeSuccess(resp, "删除成功");
    }

}
