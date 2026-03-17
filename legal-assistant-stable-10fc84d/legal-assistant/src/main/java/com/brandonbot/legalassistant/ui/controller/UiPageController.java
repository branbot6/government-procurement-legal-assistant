package com.brandonbot.legalassistant.ui.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiPageController {

    @GetMapping("/")
    public String root() {
        return "forward:/ui/index.html";
    }

    @GetMapping("/index.html")
    public String rootIndex() {
        return "forward:/ui/index.html";
    }

    @GetMapping("/ui")
    public String ui() {
        return "forward:/ui/index.html";
    }
}
