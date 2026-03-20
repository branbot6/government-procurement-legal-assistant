package com.brandonbot.legalassistant.ui.model;

import java.util.ArrayList;
import java.util.List;

public class UiProject {
    public String id;
    public String ownerUserId;
    public String name;
    public String description;
    public long createdAt;
    public long updatedAt;
    public List<UiConversation> conversations = new ArrayList<>();
}
