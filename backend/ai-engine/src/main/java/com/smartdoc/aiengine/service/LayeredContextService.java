package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 分层上下文管理服务
 * 整合三层记忆：短期记忆、长期记忆、关键信息
 */
@Slf4j
@Service
public class LayeredContextService {

    @Autowired
    private ShortTermMemoryService shortTermMemoryService;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Autowired
    private KeyInfoExtractionService keyInfoExtractionService;

    @Value("${context.short-term.window-size:5}")
    private int shortTermWindowSize;

    @Value("${context.long-term.enabled:true}")
    private boolean longTermEnabled;

    @Value("${context.key-info.enabled:true}")
    private boolean keyInfoEnabled;

    /**
     * 构建分层上下文
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param currentQuestion 当前问题
     * @return 分层上下文
     */
    public LayeredContext buildContext(Long userId, Long documentId, String currentQuestion) {
        LayeredContext context = new LayeredContext();
        
        // 第一层：短期记忆（滑动窗口）
        List<LLMService.ChatMessage> shortTermMemory = shortTermMemoryService
                .getShortTermMemory(userId, documentId, shortTermWindowSize);
        context.setShortTermMemory(shortTermMemory);
        log.debug("短期记忆: {} 条消息", shortTermMemory.size());
        
        // 第二层：长期记忆（向量检索）
        if (longTermEnabled) {
            List<LongTermMemoryService.HistoricalConversation> longTermMemory = 
                    longTermMemoryService.retrieveRelevantHistory(userId, currentQuestion, documentId);
            context.setLongTermMemory(longTermMemory);
            log.debug("长期记忆: {} 条相关历史对话", longTermMemory.size());
        }
        
        // 第三层：关键信息
        if (keyInfoEnabled) {
            Map<String, Object> keyInfo = keyInfoExtractionService.getKeyInfo(userId, documentId);
            context.setKeyInfo(keyInfo);
            log.debug("关键信息: {} 个字段", keyInfo.size());
        }
        
        return context;
    }

    /**
     * 保存对话到各层记忆
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param question 问题
     * @param answer 回答
     */
    public void saveConversation(Long userId, Long documentId, String question, String answer) {
        // 保存到短期记忆
        shortTermMemoryService.addToShortTermMemory(userId, documentId, question, answer);
        
        // 保存到长期记忆
        if (longTermEnabled) {
            longTermMemoryService.saveToLongTermMemory(userId, documentId, question, answer);
        }
        
        // 提取并保存关键信息
        if (keyInfoEnabled) {
            keyInfoExtractionService.extractKeyInfo(userId, documentId, question, answer);
        }
    }

    /**
     * 将分层上下文转换为LLM可用的消息列表
     * @param context 分层上下文
     * @param currentQuestion 当前问题
     * @return 消息列表
     */
    public List<Map<String, String>> convertToMessages(LayeredContext context, String currentQuestion) {
        List<Map<String, String>> messages = new ArrayList<>();
        
        // 添加系统提示（包含关键信息）
        String systemPrompt = buildSystemPrompt(context);
        if (!systemPrompt.isEmpty()) {
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }
        
        // 添加长期记忆（作为参考上下文）
        if (context.getLongTermMemory() != null && !context.getLongTermMemory().isEmpty()) {
            String longTermContext = buildLongTermContext(context.getLongTermMemory());
            if (!longTermContext.isEmpty()) {
                Map<String, String> contextMsg = new HashMap<>();
                contextMsg.put("role", "system");
                contextMsg.put("content", "以下是一些相关的历史对话，供参考：\n" + longTermContext);
                messages.add(contextMsg);
            }
        }
        
        // 添加短期记忆（最近的对话）
        if (context.getShortTermMemory() != null) {
            for (LLMService.ChatMessage msg : context.getShortTermMemory()) {
                Map<String, String> message = new HashMap<>();
                message.put("role", msg.getRole());
                message.put("content", msg.getContent());
                messages.add(message);
            }
        }
        
        // 添加当前问题
        Map<String, String> currentMsg = new HashMap<>();
        currentMsg.put("role", "user");
        currentMsg.put("content", currentQuestion);
        messages.add(currentMsg);
        
        return messages;
    }

    /**
     * 构建系统提示（包含关键信息）
     */
    private String buildSystemPrompt(LayeredContext context) {
        StringBuilder prompt = new StringBuilder();
        
        Map<String, Object> keyInfo = context.getKeyInfo();
        if (keyInfo != null && !keyInfo.isEmpty()) {
            prompt.append("当前对话的关键信息：\n");
            for (Map.Entry<String, Object> entry : keyInfo.entrySet()) {
                prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            prompt.append("\n请基于以上关键信息回答问题，保持信息的一致性。\n");
        }
        
        return prompt.toString();
    }

    /**
     * 构建长期记忆上下文
     */
    private String buildLongTermContext(List<LongTermMemoryService.HistoricalConversation> conversations) {
        StringBuilder context = new StringBuilder();
        
        for (int i = 0; i < conversations.size(); i++) {
            LongTermMemoryService.HistoricalConversation conv = conversations.get(i);
            context.append("历史对话").append(i + 1).append("：\n");
            context.append("问题：").append(conv.getQuestion()).append("\n");
            context.append("回答：").append(conv.getAnswer()).append("\n\n");
        }
        
        return context.toString();
    }

    /**
     * 分层上下文实体
     */
    public static class LayeredContext {
        private List<LLMService.ChatMessage> shortTermMemory = new ArrayList<>();
        private List<LongTermMemoryService.HistoricalConversation> longTermMemory = new ArrayList<>();
        private Map<String, Object> keyInfo = new HashMap<>();

        public List<LLMService.ChatMessage> getShortTermMemory() {
            return shortTermMemory;
        }

        public void setShortTermMemory(List<LLMService.ChatMessage> shortTermMemory) {
            this.shortTermMemory = shortTermMemory;
        }

        public List<LongTermMemoryService.HistoricalConversation> getLongTermMemory() {
            return longTermMemory;
        }

        public void setLongTermMemory(List<LongTermMemoryService.HistoricalConversation> longTermMemory) {
            this.longTermMemory = longTermMemory;
        }

        public Map<String, Object> getKeyInfo() {
            return keyInfo;
        }

        public void setKeyInfo(Map<String, Object> keyInfo) {
            this.keyInfo = keyInfo;
        }
    }
}

