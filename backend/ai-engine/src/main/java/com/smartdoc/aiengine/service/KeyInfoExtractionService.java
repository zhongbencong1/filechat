package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键信息提取服务（第三层）
 * 在对话中实时提取并结构化存储关键实体信息
 * 如：用户ID、订单号、问题分类、解决状态等
 */
@Slf4j
@Service
public class KeyInfoExtractionService {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_INFO_KEY = "chat:key_info:";
    private static final long EXPIRE_DAYS = 30; // 关键信息保存30天

    // 预定义的关键信息模式
    private static final Map<String, Pattern> PATTERNS = new HashMap<>();

    static {
        // 订单号模式（数字、字母组合，通常6-20位）
        PATTERNS.put("order_id", Pattern.compile("(?:订单[号|ID]|order[\\s_-]?id)[:：]?\\s*([A-Z0-9]{6,20})", Pattern.CASE_INSENSITIVE));
        
        // 手机号模式
        PATTERNS.put("phone", Pattern.compile("(?:手机[号|号码]|电话|phone)[:：]?\\s*(1[3-9]\\d{9})", Pattern.CASE_INSENSITIVE));
        
        // 邮箱模式
        PATTERNS.put("email", Pattern.compile("(?:邮箱|email)[:：]?\\s*([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})", Pattern.CASE_INSENSITIVE));
        
        // 问题分类模式
        PATTERNS.put("category", Pattern.compile("(?:问题[类型|分类]|category)[:：]?\\s*([^，。\\n]{2,20})", Pattern.CASE_INSENSITIVE));
        
        // 状态模式
        PATTERNS.put("status", Pattern.compile("(?:状态|status)[:：]?\\s*(已解决|未解决|处理中|待处理|已完成|进行中)", Pattern.CASE_INSENSITIVE));
        
        // 金额模式
        PATTERNS.put("amount", Pattern.compile("(?:金额|价格|费用|amount)[:：]?\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE));
        
        // 日期模式
        PATTERNS.put("date", Pattern.compile("(?:日期|时间|date)[:：]?\\s*(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})", Pattern.CASE_INSENSITIVE));
    }

    /**
     * 从对话中提取关键信息
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param question 问题
     * @param answer 回答
     * @return 提取的关键信息
     */
    public Map<String, Object> extractKeyInfo(Long userId, Long documentId, String question, String answer) {
        Map<String, Object> keyInfo = new HashMap<>();
        
        // 合并问题和回答进行提取
        String fullText = question + "\n" + answer;
        
        // 使用预定义模式提取
        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            String key = entry.getKey();
            Pattern pattern = entry.getValue();
            Matcher matcher = pattern.matcher(fullText);
            
            if (matcher.find()) {
                String value = matcher.group(1);
                keyInfo.put(key, value);
                log.debug("提取关键信息: {} = {}", key, value);
            }
        }
        
        // 提取用户意图（简化实现）
        String intent = extractIntent(question);
        if (intent != null) {
            keyInfo.put("intent", intent);
        }
        
        // 保存到Redis
        if (redisTemplate != null && !keyInfo.isEmpty()) {
            saveKeyInfo(userId, documentId, keyInfo);
        }
        
        return keyInfo;
    }

    /**
     * 获取用户的关键信息
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @return 关键信息
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getKeyInfo(Long userId, Long documentId) {
        if (redisTemplate == null) {
            return new HashMap<>();
        }

        String key = buildKey(userId, documentId);
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        } catch (Exception e) {
            log.error("获取关键信息失败", e);
        }
        
        return new HashMap<>();
    }

    /**
     * 更新关键信息
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param keyInfo 关键信息
     */
    public void updateKeyInfo(Long userId, Long documentId, Map<String, Object> keyInfo) {
        if (redisTemplate == null || keyInfo == null || keyInfo.isEmpty()) {
            return;
        }

        String key = buildKey(userId, documentId);
        try {
            // 获取现有信息并合并
            Map<String, Object> existing = getKeyInfo(userId, documentId);
            existing.putAll(keyInfo);
            
            // 保存
            redisTemplate.opsForValue().set(key, existing, EXPIRE_DAYS, TimeUnit.DAYS);
            
            log.debug("更新关键信息: userId={}, documentId={}, info={}", 
                    userId, documentId, keyInfo);
        } catch (Exception e) {
            log.error("更新关键信息失败", e);
        }
    }

    /**
     * 保存关键信息
     */
    private void saveKeyInfo(Long userId, Long documentId, Map<String, Object> keyInfo) {
        String key = buildKey(userId, documentId);
        try {
            // 获取现有信息并合并
            Map<String, Object> existing = getKeyInfo(userId, documentId);
            existing.putAll(keyInfo);
            
            // 保存
            redisTemplate.opsForValue().set(key, existing, EXPIRE_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("保存关键信息失败", e);
        }
    }

    /**
     * 提取用户意图（简化实现）
     */
    private String extractIntent(String question) {
        if (question == null || question.isEmpty()) {
            return null;
        }
        
        String lowerQuestion = question.toLowerCase();
        
        // 意图分类
        if (lowerQuestion.contains("如何") || lowerQuestion.contains("怎么") || lowerQuestion.contains("怎样")) {
            return "how_to";
        } else if (lowerQuestion.contains("什么") || lowerQuestion.contains("哪些") || lowerQuestion.contains("是什么")) {
            return "what";
        } else if (lowerQuestion.contains("为什么") || lowerQuestion.contains("为何")) {
            return "why";
        } else if (lowerQuestion.contains("什么时候") || lowerQuestion.contains("何时")) {
            return "when";
        } else if (lowerQuestion.contains("哪里") || lowerQuestion.contains("在哪")) {
            return "where";
        } else if (lowerQuestion.contains("谁") || lowerQuestion.contains("哪个")) {
            return "who";
        } else if (lowerQuestion.contains("查询") || lowerQuestion.contains("查看") || lowerQuestion.contains("搜索")) {
            return "query";
        } else if (lowerQuestion.contains("问题") || lowerQuestion.contains("错误") || lowerQuestion.contains("异常")) {
            return "problem";
        }
        
        return "general";
    }

    /**
     * 构建Redis key
     */
    private String buildKey(Long userId, Long documentId) {
        if (documentId != null) {
            return KEY_INFO_KEY + userId + ":" + documentId;
        } else {
            return KEY_INFO_KEY + userId + ":general";
        }
    }
}

