package com.itheima.model.command;

public class UploadCommand {
    String title;
    String description;
    long userId;
    int categoryId;
    int type;



    private UploadCommand(String title, String description, long userId, int type,int categoryId) {
        this.title = title;
        this.description = description;
        this.userId = userId;
        this.type= type;
        this.categoryId=categoryId;
    }

    public static UploadCommand asVideo(String title, String description, long userId,int categoryId ){
        return new UploadCommand(title,description,userId,ContentType.VIDEO.getTypeNumber(),categoryId);
    }
    public static UploadCommand asPost(String title, String description, long userId,int categoryId ){
        return new UploadCommand(title,description,userId,ContentType.POST.getTypeNumber(),categoryId);
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public long getUserId() {
        return userId;
    }

    public int getType() {
        return type;
    }

    public int getCategoryId() {
        return categoryId;
    }
}
