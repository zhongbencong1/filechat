package com.smartdoc.aiengine.service;

import com.smartdoc.aiengine.service.LLMService.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 短期记忆服务（第一层）
 * 使用滑动窗口，只保留最近3-5轮对话
 * 保证模型理解最近的对话流程和指代关系
 */
@Slf4j
@Service
public class ShortTermMemoryService {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private static final String SHORT_TERM_MEMORY_KEY = "chat:short_term:";
    private static final int DEFAULT_WINDOW_SIZE = 5; // 默认保留最近5轮对话
    private static final long EXPIRE_HOURS = 24; // 短期记忆过期时间24小时

    /**
     * 获取短期记忆（滑动窗口）
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param windowSize 窗口大小（默认5轮）
     * @return 最近N轮对话
     */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getShortTermMemory(Long userId, Long documentId, int windowSize) {
        if (redisTemplate == null) {
            log.warn("Redis未配置，返回空短期记忆");
            return new ArrayList<>();
        }

        String key = buildKey(userId, documentId);
        try {
            List<Object> rawMessages = redisTemplate.opsForList().range(key, 0, -1);
            if (rawMessages == null || rawMessages.isEmpty()) {
                return new ArrayList<>();
            }

            // 转换为ChatMessage列表
            List<ChatMessage> messages = new ArrayList<>();
            for (Object raw : rawMessages) {
                if (raw instanceof ChatMessage) {
                    messages.add((ChatMessage) raw);
                }
            }

            // 滑动窗口：只返回最近N轮对话
            int startIndex = Math.max(0, messages.size() - windowSize);
            List<ChatMessage> windowMessages = messages.subList(startIndex, messages.size());
            
            log.debug("获取短期记忆: userId={}, documentId={}, 总数={}, 窗口大小={}", 
                    userId, documentId, messages.size(), windowMessages.size());
            
            return new ArrayList<>(windowMessages);
        } catch (Exception e) {
            log.error("获取短期记忆失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 添加对话到短期记忆
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param question 问题
     * @param answer 回答
     */
    public void addToShortTermMemory(Long userId, Long documentId, String question, String answer) {
        if (redisTemplate == null) {
            return;
        }

        String key = buildKey(userId, documentId);
        try {
            // 添加用户问题
            ChatMessage userMessage = new ChatMessage("user", question);
            redisTemplate.opsForList().rightPush(key, userMessage);
            
            // 添加助手回答
            ChatMessage assistantMessage = new ChatMessage("assistant", answer);
            redisTemplate.opsForList().rightPush(key, assistantMessage);
            
            // 设置过期时间
            redisTemplate.expire(key, EXPIRE_HOURS, TimeUnit.HOURS);
            
            // 限制列表长度（防止无限增长）
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > DEFAULT_WINDOW_SIZE * 2) {
                // 只保留最近N轮对话（每轮2条消息：问题+回答）
                long trimSize = DEFAULT_WINDOW_SIZE * 2;
                redisTemplate.opsForList().trim(key, size - trimSize, -1);
            }
            
            log.debug("添加到短期记忆: userId={}, documentId={}, 当前大小={}", 
                    userId, documentId, size);
        } catch (Exception e) {
            log.error("添加到短期记忆失败", e);
        }
    }

    /**
     * 清空短期记忆
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     */
    public void clearShortTermMemory(Long userId, Long documentId) {
        if (redisTemplate == null) {
            return;
        }

        String key = buildKey(userId, documentId);
        try {
            redisTemplate.delete(key);
            log.info("清空短期记忆: userId={}, documentId={}", userId, documentId);
        } catch (Exception e) {
            log.error("清空短期记忆失败", e);
        }
    }

    /**
     * 构建Redis key
     */
    private String buildKey(Long userId, Long documentId) {
        if (documentId != null) {
            return SHORT_TERM_MEMORY_KEY + userId + ":" + documentId;
        } else {
            return SHORT_TERM_MEMORY_KEY + userId + ":general";
        }
    }
}

