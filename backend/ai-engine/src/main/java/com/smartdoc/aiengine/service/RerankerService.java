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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序服务
 * 使用BGE-Reranker模型进行精排
 */
@Slf4j
@Service
public class RerankerService {

    @Value("${reranker.api-url:}")
    private String rerankerApiUrl;

    @Value("${reranker.enabled:false}")
    private boolean enabled;

    /**
     * 使用BGE-Reranker对候选结果进行重排序
     */
    public List<HybridRetrievalService.CandidateResult> rerank(
            List<HybridRetrievalService.CandidateResult> candidates, String query) {
        
        if (!enabled || rerankerApiUrl == null || rerankerApiUrl.isEmpty()) {
            log.debug("Reranker未启用，使用综合分数排序");
            // 按综合分数排序
            candidates.sort((a, b) -> Float.compare(b.getCombinedScore(), a.getCombinedScore()));
            return candidates;
        }

        try {
            // 调用BGE-Reranker API
            List<Float> rerankScores = callRerankerAPI(query, candidates);
            
            // 更新重排序分数
            for (int i = 0; i < candidates.size() && i < rerankScores.size(); i++) {
                candidates.get(i).setRerankScore(rerankScores.get(i));
            }
            
            // 按重排序分数排序（如果rerankScore为0，则使用combinedScore）
            candidates.sort((a, b) -> {
                float scoreA = a.getRerankScore() > 0 ? a.getRerankScore() : a.getCombinedScore();
                float scoreB = b.getRerankScore() > 0 ? b.getRerankScore() : b.getCombinedScore();
                return Float.compare(scoreB, scoreA);
            });
            
            log.info("BGE-Reranker重排序完成，处理了 {} 个候选结果", candidates.size());
            return candidates;
        } catch (Exception e) {
            log.error("Reranker调用失败，使用综合分数排序", e);
            // 回退到综合分数排序
            candidates.sort((a, b) -> Float.compare(b.getCombinedScore(), a.getCombinedScore()));
            return candidates;
        }
    }

    /**
     * 调用BGE-Reranker API
     * 注意：这里假设使用HTTP API，实际可能需要根据部署方式调整
     */
    private List<Float> callRerankerAPI(String query, 
                                       List<com.smartdoc.aiengine.service.HybridRetrievalService.CandidateResult> candidates) 
            throws Exception {
        
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(rerankerApiUrl);
        
        httpPost.setHeader("Content-Type", "application/json");
        
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        
        List<Map<String, Object>> documents = candidates.stream()
                .map(c -> {
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("id", c.getChunkId());
                    doc.put("text", c.getContent());
                    return doc;
                })
                .collect(Collectors.toList());
        requestBody.put("documents", documents);
        
        StringEntity entity = new StringEntity(
                com.alibaba.fastjson2.JSON.toJSONString(requestBody),
                StandardCharsets.UTF_8
        );
        httpPost.setEntity(entity);
        
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            
            if (response.getStatusLine().getStatusCode() == 200) {
                Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(responseBody, Map.class);
                
                // 解析返回的分数列表
                List<Object> scores = (List<Object>) result.get("scores");
                if (scores != null) {
                    return scores.stream()
                            .map(s -> Float.parseFloat(s.toString()))
                            .collect(Collectors.toList());
                }
            }
            
            log.error("Reranker API调用失败: {}", responseBody);
            throw new RuntimeException("Reranker API调用失败");
        } finally {
            httpClient.close();
        }
    }

    /**
     * 本地重排序（如果BGE-Reranker不可用）
     * 使用简单的交叉编码器逻辑
     */
    private List<Float> localRerank(String query, 
                                    List<com.smartdoc.aiengine.service.HybridRetrievalService.CandidateResult> candidates) {
        // 简化实现：基于关键词匹配和长度计算分数
        List<Float> scores = new ArrayList<>();
        String queryLower = query.toLowerCase();
        Set<String> queryWords = new HashSet<>(Arrays.asList(queryLower.split("[\\s，。、；：！？]")));
        
        for (HybridRetrievalService.CandidateResult candidate : candidates) {
            String contentLower = candidate.getContent().toLowerCase();
            
            // 计算关键词匹配分数
            int matchCount = 0;
            for (String word : queryWords) {
                if (word.length() >= 2 && contentLower.contains(word)) {
                    matchCount++;
                }
            }
            float keywordMatchScore = queryWords.isEmpty() ? 0 : 
                    (float) matchCount / queryWords.size();
            
            // 计算内容长度分数（适中的长度更好）
            int contentLength = contentLower.length();
            float lengthScore = 0.0f;
            if (contentLength >= 200 && contentLength <= 500) {
                lengthScore = 1.0f;
            } else if (contentLength < 200) {
                lengthScore = contentLength / 200.0f;
            } else {
                lengthScore = Math.max(0, 1.0f - (contentLength - 500) / 500.0f);
            }
            
            // 综合分数：关键词匹配70% + 长度30% + 原始综合分数
            float rerankScore = keywordMatchScore * 0.7f + lengthScore * 0.3f + 
                    candidate.getCombinedScore() * 0.2f;
            scores.add(rerankScore);
        }
        
        return scores;
    }
}

