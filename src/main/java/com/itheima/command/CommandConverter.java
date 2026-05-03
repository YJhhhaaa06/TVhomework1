
package com.itheima.command;

import com.itheima.DTO.LoginDTO;
import com.itheima.DTO.RegisterDTO;
import com.itheima.exception.ParamException;
import com.itheima.util.PasswordUtil;
import com.itheima.util.StringUtil;

public class CommandConverter {
    public static LoginCommand loginToCommand(LoginDTO dto) {
        String account = dto.getAccount();
        String password= dto.getPassword();

        if(account == null || password == null){
            throw new ParamException("参数不能为空");
        }
        if(StringUtil.phoneCheck(account)){//符合phone规则，优先，以phone作为账号登录
            return LoginCommand.byPhone(account, password);
        }
        if(account.length()<15&&StringUtil.isAllDigit(account)){
            return LoginCommand.byId(Long.parseLong(account),password);
        }
        throw new ParamException("账号或密码格式错误");
    }
    public static RegisterCommand registerToCommand(RegisterDTO dto){
        String username=dto.getUsername();
        String phone= dto.getPhone();
        String password= dto.getPassword();
        if(username==null||phone==null||password==null||username.isBlank()||phone.isBlank()||password.isBlank()){
            throw new ParamException("输入不能为空");
        }
        if(username.length()>50){
            throw new ParamException("用户名过长");
        }
        if(!StringUtil.phoneCheck(phone)){
            throw new ParamException("电话号码格式错误");
        }
        if(!PasswordUtil.isPasswordLegal(password)){
            throw new ParamException("密码只能由6~16位大小写字母与数字组成");
        }
        return RegisterCommand.getInstance(phone,password,username);
    }

    public static UploadCommand uploadVideoToCommand(String title,String description,String categoryId, long userId){
        if(title==null||title.isBlank()){
            throw new ParamException("标题不能为空");
        }
        if(title.length()>50){
            throw new ParamException("标题不得超过50字");
        }
        if (description==null||description.isBlank()){
            description="-";
        }
        if(description.length()>1000){
            throw new ParamException("简介不得超过1000字");
        }
        if(categoryId.length()>1||!StringUtil.isAllDigit(categoryId)){
            //0其它
            //1游戏
            //2音乐
            //3咨讯
            //4动画
            //5娱乐
            //6动物
            //7体育
            //8鬼畜
            //9绘画
            throw new ParamException("暂时没有这个分区");
        }
        int category=Integer.parseInt(categoryId);
        return UploadCommand.asVideo(title,description,userId,category);
    }

    public static UploadCommand uploadPostToCommand(String title,String description,String categoryId, long userId){
        if(title==null||title.isBlank()){
            throw new ParamException("标题不能为空");
        }
        if(title.length()>50){
            throw new ParamException("标题不得超过50字");
        }
        if (description==null||description.isBlank()){
            description="-";
        }
        if(description.length()>10000){
            throw new ParamException("内容不得超过10000字");
        }

        if(categoryId.length()>1||!StringUtil.isAllDigit(categoryId)){
            throw new ParamException("暂时没有这个分区");
        }
        int category=Integer.parseInt(categoryId);

        return UploadCommand.asPost(title,description,userId,category);
    }

}
