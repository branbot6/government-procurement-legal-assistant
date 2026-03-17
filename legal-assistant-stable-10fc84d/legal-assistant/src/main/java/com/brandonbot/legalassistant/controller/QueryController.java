package com.brandonbot.legalassistant.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.brandonbot.legalassistant.dto.AskRequest;
import com.brandonbot.legalassistant.dto.AskResponse;
import com.brandonbot.legalassistant.model.Evidence;
import com.brandonbot.legalassistant.service.QueryService;

@RestController
@RequestMapping("/api/v1/chat")
public class QueryController {

    private static final String MASKED_SOURCE_PATH = "已隐藏";

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        QueryService.QueryResult result = queryService.ask(request.question(), request.mode());

        List<AskResponse.EvidenceDto> evidence = result.evidences().stream()
                .map(this::toDto)
                .toList();

        return new AskResponse(result.answer(), evidence);
    }

    private AskResponse.EvidenceDto toDto(Evidence e) {
        return new AskResponse.EvidenceDto(e.title(), e.snippet(), MASKED_SOURCE_PATH, e.score());
    }
}
