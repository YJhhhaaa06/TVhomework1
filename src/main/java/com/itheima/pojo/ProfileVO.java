package com.itheima.pojo;

import com.itheima.DTO.PageResult;

public class ProfileVO {
    private long userId;
    private String username;
    private int followerCount;
    private int followCount;
    private Boolean isFollowed;
    private PageResult<ContentVO> contentPage;

    public ProfileVO() {
    }

    public ProfileVO(long userId, String username, int followerCount, int followCount,
                     Boolean isFollowed, PageResult<ContentVO> contentPage) {
        this.userId = userId;
        this.username = username;
        this.followerCount = followerCount;
        this.followCount = followCount;
        this.isFollowed = isFollowed;
        this.contentPage = contentPage;
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public int getFollowerCount() { return followerCount; }
    public void setFollowerCount(int followerCount) { this.followerCount = followerCount; }

    public int getFollowCount() { return followCount; }
    public void setFollowCount(int followCount) { this.followCount = followCount; }

    public Boolean getIsFollowed() { return isFollowed; }
    public void setIsFollowed(Boolean isFollowed) { this.isFollowed = isFollowed; }

    public PageResult<ContentVO> getContentPage() { return contentPage; }
    public void setContentPage(PageResult<ContentVO> contentPage) { this.contentPage = contentPage; }
}