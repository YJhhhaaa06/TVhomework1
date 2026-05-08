package com.itheima.controller;

import com.itheima.command.CommandConverter;
import com.itheima.command.CommentCommand;
import com.itheima.exception.BusinessException;
import com.itheima.exception.ErrorCode;
import com.itheima.factory.BeanFactory;
import com.itheima.service.CommentService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/comment/*")
public class CommentController extends HttpServlet {
    private CommentService commentService = BeanFactory.getCommentService();


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException  {
        try{
            String action = req.getPathInfo();
            if(action==null){
                BaseServletUtil.writeError(resp, ErrorCode.PARAM_ERROR,"action不能为空");
                return;
            }
            switch (action){
                case "/add":
                    addComment(req,resp);
                    break;
                case "/show":
                    break;
                default:
                    BaseServletUtil.writeError(resp,ErrorCode.PARAM_ERROR,"未识别功能");
            }
        } catch (BusinessException e) {
            e.printStackTrace();
            BaseServletUtil.writeError(resp,e.getCode(),e.getMessage());
        }catch (Exception e){
            e.printStackTrace();
            BaseServletUtil.writeError(resp,ErrorCode.SERVER_ERROR,e.getMessage());
        }


    }




    protected void addComment(HttpServletRequest req, HttpServletResponse resp)throws Exception{

        com.itheima.DTO.CommentDTO dto=RequestParser.parse(req, com.itheima.DTO.CommentDTO.class);
        Long userId = (Long) req.getAttribute("userId");
        dto.setUserId(userId);
        CommentCommand commentCommand= CommandConverter.commentToCommand(dto);
        commentService.addComment(commentCommand);
        BaseServletUtil.writeSuccess(resp,"评论成功");

    }
    protected void showComment(HttpServletRequest req, HttpServletResponse resp)throws Exception{

    }

}
