package com.wren.agent.api.dto;

import java.util.ArrayList;
import java.util.List;

public class FeedResponse {

    private List<PostResponseItem> posts = new ArrayList<>();

    public FeedResponse() {}

    public FeedResponse(List<PostResponseItem> posts) {
        this.posts = posts != null ? posts : new ArrayList<>();
    }

    public List<PostResponseItem> getPosts() { return posts; }
    public void setPosts(List<PostResponseItem> posts) { this.posts = posts != null ? posts : new ArrayList<>(); }
}
