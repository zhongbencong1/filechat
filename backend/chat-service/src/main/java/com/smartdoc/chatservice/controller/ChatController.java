package com.smartdoc.chatservice.controller;

import com.smartdoc.chatservice.entity.ChatMessage;
import com.smartdoc.chatservice.service.ChatService;
import com.smartdoc.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 智能问答
     */
    @PostMapping("/ask")
    public Result<ChatMessage> askQuestion(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "user") String userRole,
            @RequestBody Map<String, Object> request) {
        Long documentId = request.get("documentId") != null ? 
                Long.parseLong(request.get("documentId").toString()) : null;
        String question = (String) request.get("question");
        
        ChatMessage chatMessage = chatService.askQuestion(userId, documentId, question, userRole);
        return Result.success(chatMessage);
    }

    /**
     * 获取对话历史
     */
    @GetMapping("/history")
    public Result<List<ChatMessage>> getChatHistory(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) Long documentId) {
        List<ChatMessage> history = chatService.getChatHistoryList(userId, documentId);
        return Result.success(history);
    }
}

