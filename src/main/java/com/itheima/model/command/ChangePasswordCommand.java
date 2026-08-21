package com.itheima.model.command;

public class ChangePasswordCommand {

    String phone;
    String oldPassword;
    String newPassword;

    public ChangePasswordCommand() {
    }

    public ChangePasswordCommand(String phone, String oldPassword, String newPassword) {
        this.phone = phone;
        this.oldPassword = oldPassword;
        this.newPassword = newPassword;

    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }


}
