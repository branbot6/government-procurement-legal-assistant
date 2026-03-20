package com.brandonbot.legalassistant.ui.model;

import java.util.ArrayList;
import java.util.List;

public class UiConversation {
    public String id;
    public String projectId;
    public String title;
    public long createdAt;
    public long updatedAt;
    public List<UiMessage> messages = new ArrayList<>();
}
