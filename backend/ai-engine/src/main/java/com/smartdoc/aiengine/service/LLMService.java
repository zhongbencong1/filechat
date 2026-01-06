package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 大语言模型服务
 * 调用DeepSeek API进行对话生成
 */
@Slf4j
@Service
public class LLMService {

    @Value("${ai.llm.api-url:https://api.deepseek.com/v1/chat/completions}")
    private String llmApiUrl;

    @Value("${ai.llm.api-key:}")
    private String apiKey;

    @Value("${ai.llm.model:deepseek-chat}")
    private String model;

    /**
     * 基于文档内容生成回答（RAG模式）- 使用分层上下文
     */
    public String generateAnswerWithContext(String question, List<MilvusService.SearchResult> searchResults, 
                                          List<Map<String, String>> layeredMessages) {
        return generateAnswerWithContext(question, searchResults, layeredMessages, null);
    }

    /**
     * 基于文档内容生成回答（RAG模式）- 兼容旧版本
     */
    @Deprecated
    public String generateAnswerWithContext(String question, List<MilvusService.SearchResult> searchResults, List<Map<String, String>> chatHistory) {
        // 构建上下文
        StringBuilder context = new StringBuilder();
        context.append("基于以下文档内容回答问题，回答必须严格基于文档内容，不要编造信息。\n\n");
        context.append("文档内容：\n");
        
        for (int i = 0; i < searchResults.size(); i++) {
            MilvusService.SearchResult result = searchResults.get(i);
            context.append("【片段").append(i + 1).append("】").append(result.getContent()).append("\n\n");
        }
        
        context.append("用户问题：").append(question).append("\n\n");
        context.append("请基于上述文档内容回答问题，如果文档中没有相关信息，请明确说明。");

        // 构建Prompt
        String systemPrompt = "你是一个专业的文档问答助手。你的任务是基于用户提供的文档内容，准确、清晰地回答用户的问题。\n" +
                "回答要求：\n" +
                "1. 严格基于文档内容，不要编造或推测文档中没有的信息\n" +
                "2. 如果文档中没有相关信息，明确告知用户\n" +
                "3. 回答要简洁明了，逻辑清晰\n" +
                "4. 可以引用文档中的具体内容，但不要直接复制大段文字";

        return callLLMAPI(systemPrompt, context.toString(), layeredMessages);
    }

    /**
     * 基于文档内容生成回答（RAG模式）- 支持分层上下文和关键信息
     */
    public String generateAnswerWithContext(String question, List<MilvusService.SearchResult> searchResults, 
                                          List<Map<String, String>> layeredMessages, Map<String, Object> keyInfo) {
        // 构建上下文
        StringBuilder context = new StringBuilder();
        context.append("基于以下文档内容回答问题，回答必须严格基于文档内容，不要编造信息。\n\n");
        context.append("文档内容：\n");
        
        for (int i = 0; i < searchResults.size(); i++) {
            MilvusService.SearchResult result = searchResults.get(i);
            context.append("【片段").append(i + 1).append("】").append(result.getContent()).append("\n\n");
        }
        
        context.append("用户问题：").append(question).append("\n\n");
        context.append("请基于上述文档内容回答问题，如果文档中没有相关信息，请明确说明。");

        // 构建Prompt（包含关键信息）
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("你是一个专业的文档问答助手。你的任务是基于用户提供的文档内容，准确、清晰地回答用户的问题。\n");
        
        // 添加关键信息到系统提示
        if (keyInfo != null && !keyInfo.isEmpty()) {
            systemPromptBuilder.append("\n当前对话的关键信息：\n");
            for (Map.Entry<String, Object> entry : keyInfo.entrySet()) {
                systemPromptBuilder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            systemPromptBuilder.append("请保持信息的一致性，不要与上述关键信息冲突。\n");
        }
        
        systemPromptBuilder.append("\n回答要求：\n");
        systemPromptBuilder.append("1. 严格基于文档内容，不要编造或推测文档中没有的信息\n");
        systemPromptBuilder.append("2. 如果文档中没有相关信息，明确告知用户\n");
        systemPromptBuilder.append("3. 回答要简洁明了，逻辑清晰\n");
        systemPromptBuilder.append("4. 可以引用文档中的具体内容，但不要直接复制大段文字");
        
        String systemPrompt = systemPromptBuilder.toString();

        return callLLMAPI(systemPrompt, context.toString(), layeredMessages);
    }

    /**
     * 通用问答（无文档上下文）- 支持分层上下文
     */
    public String generateGeneralAnswer(String question, List<Map<String, String>> layeredMessages) {
        // 从分层消息中提取关键信息（如果有）
        Map<String, Object> keyInfo = extractKeyInfoFromMessages(layeredMessages);
        
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("你是一个智能助手，可以回答各种常识性问题。请用简洁、准确的语言回答问题。\n");
        
        // 添加关键信息
        if (keyInfo != null && !keyInfo.isEmpty()) {
            systemPromptBuilder.append("\n当前对话的关键信息：\n");
            for (Map.Entry<String, Object> entry : keyInfo.entrySet()) {
                systemPromptBuilder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            systemPromptBuilder.append("请保持信息的一致性，不要与上述关键信息冲突。");
        }
        
        return callLLMAPI(systemPromptBuilder.toString(), question, layeredMessages);
    }

    /**
     * 从消息中提取关键信息
     */
    private Map<String, Object> extractKeyInfoFromMessages(List<Map<String, String>> messages) {
        // 简化实现：从系统消息中提取关键信息
        // 实际应该从LayeredContext中获取
        return new HashMap<>();
    }

    /**
     * 调用DeepSeek API
     */
    private String callLLMAPI(String systemPrompt, String userMessage, List<Map<String, String>> chatHistory) {
        try {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost(llmApiUrl);
            
            // 设置请求头
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer " + apiKey);

            // 构建消息列表
            java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
            
            // 添加系统提示
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
            
            // 添加历史对话
            if (chatHistory != null) {
                messages.addAll(chatHistory);
            }
            
            // 添加当前问题
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);

            StringEntity entity = new StringEntity(
                com.alibaba.fastjson2.JSON.toJSONString(requestBody),
                StandardCharsets.UTF_8
            );
            httpPost.setEntity(entity);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                
                if (response.getStatusLine().getStatusCode() == 200) {
                    Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(responseBody, Map.class);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return (String) message.get("content");
                    }
                }
                
                log.error("LLM API调用失败: {}", responseBody);
                throw new RuntimeException("LLM API调用失败: " + responseBody);
            } finally {
                httpClient.close();
            }
        } catch (Exception e) {
            log.error("调用LLM API异常", e);
            throw new RuntimeException("LLM API调用异常: " + e.getMessage());
        }
    }

    /**
     * 格式化对话历史
     */
    public static List<Map<String, String>> formatChatHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        
        return history.stream()
                .map(msg -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("role", msg.getRole());
                    map.put("content", msg.getContent());
                    return map;
                })
                .collect(Collectors.toList());
    }

    /**
     * 对话消息实体
     */
    public static class ChatMessage {
        private String role; // user, assistant
        private String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}

