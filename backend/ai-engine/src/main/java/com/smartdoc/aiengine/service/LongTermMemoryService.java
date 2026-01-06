package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆服务（第二层）
 * 使用向量数据库检索历史对话
 * 当用户提问时，系统将其转换为向量，并检索出语义最相关的历史对话片段
 */
@Slf4j
@Service
public class LongTermMemoryService {

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private EmbeddingService embeddingService;

    @Value("${long-term-memory.collection:chat_history_vectors}")
    private String collectionName;

    @Value("${long-term-memory.enabled:true}")
    private boolean enabled;

    @Value("${long-term-memory.top-k:3}")
    private int topK;

    /**
     * 检索相关历史对话
     * @param userId 用户ID
     * @param query 当前查询
     * @param documentId 文档ID（可为null）
     * @return 相关历史对话片段
     */
    public List<HistoricalConversation> retrieveRelevantHistory(Long userId, String query, Long documentId) {
        if (!enabled || milvusService == null || embeddingService == null) {
            log.debug("长期记忆未启用或服务不可用");
            return new ArrayList<>();
        }

        try {
            // 将查询转换为向量
            List<Float> queryVector = embeddingService.embedText(query);
            
            // 在Milvus中检索相关历史对话
            // 注意：这里使用专门的对话历史集合，需要单独创建
            // 暂时使用基础检索，后续可以扩展支持过滤
            List<MilvusService.SearchResult> results = milvusService.searchSimilar(
                    queryVector, topK, documentId);
            
            // 转换为历史对话对象
            List<HistoricalConversation> conversations = results.stream()
                    .map(result -> {
                        HistoricalConversation conv = new HistoricalConversation();
                        conv.setQuestion(extractQuestion(result.getContent()));
                        conv.setAnswer(extractAnswer(result.getContent()));
                        conv.setScore(result.getScore());
                        conv.setTimestamp(extractTimestamp(result.getChunkId()));
                        return conv;
                    })
                    .collect(Collectors.toList());
            
            log.info("检索到 {} 条相关历史对话: userId={}, documentId={}", 
                    conversations.size(), userId, documentId);
            
            return conversations;
        } catch (Exception e) {
            log.error("检索历史对话失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存对话到长期记忆
     * @param userId 用户ID
     * @param documentId 文档ID（可为null）
     * @param question 问题
     * @param answer 回答
     */
    public void saveToLongTermMemory(Long userId, Long documentId, String question, String answer) {
        if (!enabled || milvusService == null || embeddingService == null) {
            return;
        }

        try {
            // 构建对话内容（包含问题和回答）
            String conversationText = buildConversationText(question, answer);
            
            // 向量化
            List<Float> vector = embeddingService.embedText(conversationText);
            
            // 构建chunkId（包含用户ID、文档ID、时间戳）
            String chunkId = buildChunkId(userId, documentId);
            
            // 构建元数据
            String metadata = buildMetadata(userId, documentId, question, answer);
            
            // 存入Milvus（使用insertVectors方法）
            // 注意：这里需要为对话历史创建单独的集合，暂时使用文档集合
            // 实际部署时需要创建专门的chat_history_vectors集合
            List<String> chunkIds = Collections.singletonList(chunkId);
            List<String> contents = Collections.singletonList(conversationText);
            List<List<Float>> vectors = Collections.singletonList(vector);
            
            // 使用documentId=0表示对话历史（或创建专门集合）
            milvusService.insertVectors(0L, chunkIds, contents, vectors);
            
            log.debug("保存到长期记忆: userId={}, documentId={}, chunkId={}", 
                    userId, documentId, chunkId);
        } catch (Exception e) {
            log.error("保存到长期记忆失败", e);
        }
    }


    /**
     * 构建对话文本
     */
    private String buildConversationText(String question, String answer) {
        return "问题：" + question + "\n回答：" + answer;
    }

    /**
     * 构建chunkId
     */
    private String buildChunkId(Long userId, Long documentId) {
        long timestamp = System.currentTimeMillis();
        if (documentId != null) {
            return "chat_" + userId + "_" + documentId + "_" + timestamp;
        } else {
            return "chat_" + userId + "_general_" + timestamp;
        }
    }

    /**
     * 构建元数据（JSON格式）
     */
    private String buildMetadata(Long userId, Long documentId, String question, String answer) {
        return String.format("{\"user_id\":%d,\"document_id\":%s,\"question\":\"%s\",\"answer\":\"%s\"}",
                userId, documentId != null ? documentId : "null", 
                escapeJson(question), escapeJson(answer));
    }

    /**
     * 从对话文本中提取问题
     */
    private String extractQuestion(String content) {
        if (content.startsWith("问题：")) {
            int answerIndex = content.indexOf("\n回答：");
            if (answerIndex > 0) {
                return content.substring(3, answerIndex);
            }
        }
        return content;
    }

    /**
     * 从对话文本中提取回答
     */
    private String extractAnswer(String content) {
        int answerIndex = content.indexOf("\n回答：");
        if (answerIndex > 0) {
            return content.substring(answerIndex + 4);
        }
        return "";
    }

    /**
     * 从chunkId中提取时间戳
     */
    private Long extractTimestamp(String chunkId) {
        try {
            String[] parts = chunkId.split("_");
            if (parts.length > 0) {
                return Long.parseLong(parts[parts.length - 1]);
            }
        } catch (Exception e) {
            // 忽略
        }
        return System.currentTimeMillis();
    }

    /**
     * JSON转义
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 历史对话实体
     */
    public static class HistoricalConversation {
        private String question;
        private String answer;
        private Float score;
        private Long timestamp;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public Float getScore() {
            return score;
        }

        public void setScore(Float score) {
            this.score = score;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }
}

