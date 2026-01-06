package com.smartdoc.chatservice.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdoc.aiengine.service.EmbeddingService;
import com.smartdoc.aiengine.service.LLMService;
import com.smartdoc.aiengine.service.MilvusService;
import com.smartdoc.chatservice.entity.ChatMessage;
import com.smartdoc.chatservice.mapper.ChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 对话管理服务
 */
@Slf4j
@Service
public class ChatService {

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private LLMService llmService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private com.smartdoc.aiengine.service.EnhancedRetrievalService enhancedRetrievalService;

    @Autowired(required = false)
    private com.smartdoc.aiengine.service.HybridRetrievalService hybridRetrievalService;

    @Autowired(required = false)
    private com.smartdoc.aiengine.service.LayeredContextService layeredContextService;

    private static final String CHAT_HISTORY_KEY = "chat:history:";

    @Autowired(required = false)
    private RestTemplate restTemplate;

    /**
     * 智能问答
     */
    @Transactional
    public ChatMessage askQuestion(Long userId, Long documentId, String question, String userRole) {
        log.info("用户提问: userId={}, documentId={}, question={}, userRole={}", userId, documentId, question, userRole);
        
        // 如果指定了文档，检查权限
        if (documentId != null) {
            checkDocumentPermission(documentId, userId, userRole);
        }

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setUserId(userId);
        chatMessage.setDocumentId(documentId);
        chatMessage.setQuestion(question);
        chatMessage.setCreateTime(LocalDateTime.now());

        try {
            // 使用分层上下文管理（如果可用）
            List<Map<String, String>> layeredMessages = null;
            if (layeredContextService != null) {
                // 构建分层上下文
                com.smartdoc.aiengine.service.LayeredContextService.LayeredContext context = 
                        layeredContextService.buildContext(userId, documentId, question);
                
                // 转换为消息列表
                layeredMessages = layeredContextService.convertToMessages(context, question);
                log.info("使用分层上下文管理: 短期记忆={}, 长期记忆={}, 关键信息={}", 
                        context.getShortTermMemory().size(), 
                        context.getLongTermMemory().size(),
                        context.getKeyInfo().size());
            } else {
                // 回退到传统方式
                List<LLMService.ChatMessage> chatHistory = getChatHistory(userId, documentId);
                layeredMessages = LLMService.formatChatHistory(chatHistory);
            }

            String answer;
            List<MilvusService.SearchResult> searchResults;
            boolean isGeneralAnswer = false;

            if (documentId != null) {
                // 文档问答模式
                // 优先使用混合检索（Elasticsearch + Milvus + Reranker）
                if (hybridRetrievalService != null) {
                    // 使用混合检索：ES关键词检索 + Milvus向量检索 + BGE-Reranker重排序
                    searchResults = hybridRetrievalService.hybridSearch(question, documentId, 5);
                    log.info("使用混合检索（ES+向量+Reranker），返回 {} 条结果", searchResults.size());
                } else if (enhancedRetrievalService != null) {
                    // 回退到增强检索：混合检索 + 重排序 + 去重
                    searchResults = enhancedRetrievalService.enhancedSearch(question, documentId, 5);
                    log.info("使用增强检索，返回 {} 条结果", searchResults.size());
                } else {
                    // 回退到基础向量检索
                    List<Float> questionVector = embeddingService.embedText(question);
                    searchResults = milvusService.searchSimilar(questionVector, 5, documentId);
                    log.info("使用基础向量检索，返回 {} 条结果", searchResults.size());
                }

                // 判断检索结果是否相关
                boolean hasRelevantResults = false;
                if (searchResults != null && !searchResults.isEmpty()) {
                    // 使用更智能的相关性判断
                    // 1. 检查分数（向量距离）
                    // 2. 检查关键词匹配
                    MilvusService.SearchResult topResult = searchResults.get(0);
                    
                    // 对于增强检索，分数是重排序分数（越高越好）
                    // 对于基础检索，分数是距离（越小越好）
                    float relevanceScore = enhancedRetrievalService != null ? 
                            topResult.getScore() : 1.0f / (1.0f + topResult.getScore());
                    
                    // 关键词匹配检查
                    String questionLower = question.toLowerCase();
                    String contentLower = topResult.getContent().toLowerCase();
                    boolean hasKeywordMatch = false;
                    String[] questionWords = questionLower.split("[\\s，。、；：！？]");
                    for (String word : questionWords) {
                        if (word.length() >= 2 && contentLower.contains(word)) {
                            hasKeywordMatch = true;
                            break;
                        }
                    }
                    
                    // 综合判断：重排序分数 > 0.3 或 有关键词匹配且距离 < 2.0
                    hasRelevantResults = relevanceScore > 0.3 || 
                            (hasKeywordMatch && (enhancedRetrievalService == null || 
                                    topResult.getScore() < 2.0f));
                }

                if (hasRelevantResults) {
                    // 找到相关文档内容，使用RAG模式（使用分层上下文）
                    com.smartdoc.aiengine.service.LayeredContextService.LayeredContext context = null;
                    if (layeredContextService != null) {
                        context = layeredContextService.buildContext(userId, documentId, question);
                    }
                    answer = llmService.generateAnswerWithContext(question, searchResults, layeredMessages,
                            context != null ? context.getKeyInfo() : null);
                    
                    // 保存来源文本块ID
                    List<String> chunkIds = searchResults.stream()
                            .map(MilvusService.SearchResult::getChunkId)
                            .collect(Collectors.toList());
                    chatMessage.setSourceChunks(JSON.toJSONString(chunkIds));
                } else {
                    // 未找到相关文档内容，使用通用问答（使用分层上下文）
                    log.warn("未找到相关文档内容，使用通用问答: question={}", question);
                    answer = llmService.generateGeneralAnswer(question, layeredMessages);
                    isGeneralAnswer = true;
                }
            } else {
                // 通用问答模式（使用分层上下文）
                answer = llmService.generateGeneralAnswer(question, layeredMessages);
                isGeneralAnswer = true;
            }

            chatMessage.setAnswer(answer);
            chatMessage.setIsGeneralAnswer(isGeneralAnswer ? 1 : 0);
            chatMessage.setUpdateTime(LocalDateTime.now());

            // 保存到数据库
            chatMessageMapper.insert(chatMessage);

            // 更新对话历史（使用分层上下文管理）
            if (layeredContextService != null) {
                layeredContextService.saveConversation(userId, documentId, question, answer);
            } else {
                updateChatHistory(userId, documentId, question, answer);
            }

            log.info("问答完成: messageId={}", chatMessage.getId());
            return chatMessage;

        } catch (Exception e) {
            log.error("问答失败", e);
            chatMessage.setAnswer("抱歉，处理您的问题时出现错误：" + e.getMessage());
            chatMessage.setIsGeneralAnswer(1);
            chatMessage.setUpdateTime(LocalDateTime.now());
            chatMessageMapper.insert(chatMessage);
            return chatMessage;
        }
    }

    /**
     * 获取对话历史
     */
    public List<LLMService.ChatMessage> getChatHistory(Long userId, Long documentId) {
        String key = CHAT_HISTORY_KEY + userId + ":" + (documentId != null ? documentId : "general");
        
        // 先从Redis获取
        List<Object> historyList = redisTemplate.opsForList().range(key, 0, -1);
        if (historyList != null && !historyList.isEmpty()) {
            return historyList.stream()
                    .map(obj -> JSON.parseObject(JSON.toJSONString(obj), LLMService.ChatMessage.class))
                    .collect(Collectors.toList());
        }

        // 从数据库获取最近10条
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getUserId, userId);
        if (documentId != null) {
            wrapper.eq(ChatMessage::getDocumentId, documentId);
        } else {
            wrapper.isNull(ChatMessage::getDocumentId);
        }
        wrapper.orderByDesc(ChatMessage::getCreateTime);
        wrapper.last("LIMIT 10");

        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);
        Collections.reverse(messages); // 按时间正序

        List<LLMService.ChatMessage> chatHistory = new ArrayList<>();
        for (ChatMessage msg : messages) {
            chatHistory.add(new LLMService.ChatMessage("user", msg.getQuestion()));
            chatHistory.add(new LLMService.ChatMessage("assistant", msg.getAnswer()));
        }

        // 存入Redis
        if (!chatHistory.isEmpty()) {
            for (LLMService.ChatMessage msg : chatHistory) {
                redisTemplate.opsForList().rightPush(key, msg);
            }
            redisTemplate.expire(key, 1, TimeUnit.HOURS);
        }

        return chatHistory;
    }

    /**
     * 更新对话历史到Redis
     */
    private void updateChatHistory(Long userId, Long documentId, String question, String answer) {
        String key = CHAT_HISTORY_KEY + userId + ":" + (documentId != null ? documentId : "general");
        
        // 添加新的对话
        redisTemplate.opsForList().rightPush(key, new LLMService.ChatMessage("user", question));
        redisTemplate.opsForList().rightPush(key, new LLMService.ChatMessage("assistant", answer));
        
        // 只保留最近10轮对话（20条消息）
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > 20) {
            for (long i = 0; i < size - 20; i++) {
                redisTemplate.opsForList().leftPop(key);
            }
        }
        
        redisTemplate.expire(key, 1, TimeUnit.HOURS);
    }

    /**
     * 获取用户的对话历史记录
     */
    public List<ChatMessage> getChatHistoryList(Long userId, Long documentId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getUserId, userId);
        if (documentId != null) {
            wrapper.eq(ChatMessage::getDocumentId, documentId);
        }
        wrapper.orderByAsc(ChatMessage::getCreateTime);
        return chatMessageMapper.selectList(wrapper);
    }

    /**
     * 检查文档权限（通过调用文件服务）
     */
    private void checkDocumentPermission(Long documentId, Long userId, String userRole) {
        try {
            if (restTemplate == null) {
                restTemplate = new RestTemplate();
            }
            
            // 调用文件服务检查权限
            String url = "http://localhost:8081/api/file/" + documentId;
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", userId.toString());
            headers.set("X-User-Role", userRole != null ? userRole : "user");
            HttpEntity<?> entity = new HttpEntity<>(headers);
            
            try {
                restTemplate.exchange(url, HttpMethod.GET, entity, 
                        com.smartdoc.common.result.Result.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.FORBIDDEN || 
                    e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    throw new com.smartdoc.common.exception.BusinessException("无权限访问该文档");
                }
                throw e;
            }
        } catch (com.smartdoc.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("检查文档权限失败: documentId={}, userId={}", documentId, userId, e);
            throw new com.smartdoc.common.exception.BusinessException("检查文档权限失败: " + e.getMessage());
        }
    }
}

