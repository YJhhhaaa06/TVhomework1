package com.itheima.content.model.command;

import com.itheima.exception.ParamException;
import com.itheima.user.model.command.ChangePasswordCommand;
import com.itheima.user.model.command.RegisterCommand;
import com.itheima.user.model.dto.ChangePasswordDTO;
import com.itheima.user.model.dto.RegisterDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CommandConverter 注册/改密密码格式校验测试（U-03：正则收紧为 6~16 位后对齐文案）
 */
class CommandConverterTest {

    private RegisterDTO regDto(String password) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("bob");
        dto.setPhone("13800000001");
        dto.setPassword(password);
        return dto;
    }

    @Test
    void register_pwdBelow6Rejected() {
        assertThrows(ParamException.class, () -> CommandConverter.registerToCommand(regDto("abc12")));
    }

    @Test
    void register_pwdExactly6Accepted() {
        RegisterCommand cmd = CommandConverter.registerToCommand(regDto("abc123"));
        assertNotNull(cmd);
        assertEquals("abc123", cmd.getPassword());
    }

    @Test
    void register_pwd16Accepted() {
        RegisterCommand cmd = CommandConverter.registerToCommand(regDto("abcdefghijklmnop"));
        assertNotNull(cmd);
    }

    @Test
    void register_pwdOver16Rejected() {
        assertThrows(ParamException.class, () -> CommandConverter.registerToCommand(regDto("abcdefghijklmnopq")));
    }

    @Test
    void register_pwdIllegalCharRejected() {
        assertThrows(ParamException.class, () -> CommandConverter.registerToCommand(regDto("abc12_")));
    }

    @Test
    void changePassword_pwdBelow6Rejected() {
        ChangePasswordDTO dto = new ChangePasswordDTO();
        dto.setPhone("13800000001");
        dto.setOldPassword("abc123");
        dto.setNewPassword("abc12");
        assertThrows(ParamException.class, () -> CommandConverter.changePasswordToCommand(dto));
    }

    @Test
    void changePassword_pwd6Accepted() {
        ChangePasswordDTO dto = new ChangePasswordDTO();
        dto.setPhone("13800000001");
        dto.setOldPassword("abc123");
        dto.setNewPassword("abc123");
        ChangePasswordCommand cmd = CommandConverter.changePasswordToCommand(dto);
        assertNotNull(cmd);
        assertEquals("abc123", cmd.getNewPassword());
    }
}