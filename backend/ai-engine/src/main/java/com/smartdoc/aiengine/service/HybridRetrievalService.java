package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 * 结合Elasticsearch关键词检索和Milvus向量检索
 */
@Slf4j
@Service
public class HybridRetrievalService {

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired(required = false)
    private RerankerService rerankerService;

    /**
     * 混合检索：结合关键词检索和向量检索
     */
    public List<MilvusService.SearchResult> hybridSearch(
            String query, Long documentId, int topK) {
        
        log.info("开始混合检索: query={}, documentId={}, topK={}", query, documentId, topK);
        
        // 1. 查询路由：判断查询类型
        QueryType queryType = analyzeQueryType(query);
        log.info("查询类型分析: type={}, keywordWeight={}, vectorWeight={}", 
                queryType, queryType.getKeywordWeight(), queryType.getVectorWeight());
        
        // 2. 并行执行两种检索
        List<ElasticsearchService.SearchResult> keywordResults = 
                elasticsearchService.keywordSearch(query, documentId, topK * 2);
        
        List<Float> queryVector = embeddingService.embedText(query);
        List<MilvusService.SearchResult> vectorResults = 
                milvusService.searchSimilar(queryVector, topK * 2, documentId);
        
        // 3. 结果融合
        List<CandidateResult> candidates = mergeResults(
                keywordResults, vectorResults, queryType);
        
        // 4. 重排序（使用BGE-Reranker）
        List<CandidateResult> rerankedCandidates = rerankResults(candidates, query);
        
        // 5. 去重并返回Top K
        List<MilvusService.SearchResult> finalResults = deduplicateAndSelectTopK(
                rerankedCandidates, topK);
        
        log.info("混合检索完成，返回 {} 条结果", finalResults.size());
        return finalResults;
    }

    /**
     * 分析查询类型：关键词主导 vs 语义意图主导
     */
    private QueryType analyzeQueryType(String query) {
        // 简单启发式规则
        // 1. 包含具体关键词（如"如何"、"什么"、"为什么"）-> 语义意图主导
        // 2. 包含专有名词、术语 -> 关键词主导
        // 3. 查询长度 -> 长查询更可能是语义意图
        
        boolean hasQuestionWords = query.matches(".*[如何什么为什么怎样].*");
        boolean hasSpecificTerms = query.matches(".*[\\u4e00-\\u9fa5]{2,4}.*"); // 包含2-4字的中文词
        int queryLength = query.length();
        
        float keywordWeight;
        float vectorWeight;
        
        if (hasQuestionWords && queryLength > 10) {
            // 语义意图主导
            keywordWeight = 0.3f;
            vectorWeight = 0.7f;
        } else if (hasSpecificTerms && queryLength < 15) {
            // 关键词主导
            keywordWeight = 0.7f;
            vectorWeight = 0.3f;
        } else {
            // 平衡
            keywordWeight = 0.5f;
            vectorWeight = 0.5f;
        }
        
        return new QueryType(keywordWeight, vectorWeight);
    }

    /**
     * 融合关键词检索和向量检索结果
     */
    private List<CandidateResult> mergeResults(
            List<ElasticsearchService.SearchResult> keywordResults,
            List<MilvusService.SearchResult> vectorResults,
            QueryType queryType) {
        
        Map<String, CandidateResult> candidateMap = new HashMap<>();
        
        // 处理关键词检索结果
        for (ElasticsearchService.SearchResult result : keywordResults) {
            String chunkId = result.getChunkId();
            CandidateResult candidate = candidateMap.getOrDefault(chunkId, 
                    new CandidateResult(result.getDocumentId(), chunkId, result.getContent()));
            
            // 归一化ES分数（0-1范围）
            float normalizedScore = normalizeESScore(result.getScore());
            candidate.setKeywordScore(normalizedScore);
            candidate.setCombinedScore(candidate.getCombinedScore() + 
                    normalizedScore * queryType.getKeywordWeight());
            
            candidateMap.put(chunkId, candidate);
        }
        
        // 处理向量检索结果
        for (MilvusService.SearchResult result : vectorResults) {
            String chunkId = result.getChunkId();
            CandidateResult candidate = candidateMap.getOrDefault(chunkId,
                    new CandidateResult(result.getDocumentId(), chunkId, result.getContent()));
            
            // 归一化向量距离为相似度分数（0-1范围）
            float normalizedScore = 1.0f / (1.0f + result.getScore());
            candidate.setVectorScore(normalizedScore);
            candidate.setCombinedScore(candidate.getCombinedScore() + 
                    normalizedScore * queryType.getVectorWeight());
            
            candidateMap.put(chunkId, candidate);
        }
        
        return new ArrayList<>(candidateMap.values());
    }

    /**
     * 归一化Elasticsearch分数
     */
    private float normalizeESScore(float score) {
        // ES的分数范围不固定，使用简单的归一化
        // 假设分数在0-100之间，实际可以根据统计调整
        return Math.min(1.0f, score / 100.0f);
    }

    /**
     * 重排序：使用BGE-Reranker进行精排
     */
    private List<CandidateResult> rerankResults(
            List<CandidateResult> candidates, String query) {
        
        if (rerankerService != null) {
            // 使用BGE-Reranker重排序
            return rerankerService.rerank(candidates, query);
        } else {
            // 回退到基于综合分数的排序
            candidates.sort((a, b) -> Float.compare(b.getCombinedScore(), a.getCombinedScore()));
            return candidates;
        }
    }

    /**
     * 去重并选择Top K
     */
    private List<MilvusService.SearchResult> deduplicateAndSelectTopK(
            List<CandidateResult> candidates, int topK) {
        
        List<MilvusService.SearchResult> results = new ArrayList<>();
        Set<String> seenContents = new HashSet<>();
        
        for (CandidateResult candidate : candidates) {
            String normalizedContent = normalizeContent(candidate.getContent());
            
            // 检查是否与已有结果高度相似
            boolean isDuplicate = false;
            for (String seenContent : seenContents) {
                if (calculateSimilarity(normalizedContent, seenContent) > 0.8) {
                    isDuplicate = true;
                    break;
                }
            }
            
            if (!isDuplicate) {
                results.add(new MilvusService.SearchResult(
                        candidate.getDocumentId(),
                        candidate.getChunkId(),
                        candidate.getContent(),
                        candidate.getCombinedScore()
                ));
                seenContents.add(normalizedContent);
                
                if (results.size() >= topK) {
                    break;
                }
            }
        }
        
        return results;
    }

    /**
     * 标准化内容
     */
    private String normalizeContent(String content) {
        return content.toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 计算相似度（Jaccard相似度）
     */
    private double calculateSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null || str1.isEmpty() || str2.isEmpty()) {
            return 0.0;
        }
        
        Set<String> set1 = new HashSet<>(Arrays.asList(str1.split("")));
        Set<String> set2 = new HashSet<>(Arrays.asList(str2.split("")));
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        if (union.isEmpty()) {
            return 0.0;
        }
        
        return (double) intersection.size() / union.size();
    }

    /**
     * 查询类型
     */
    private static class QueryType {
        private float keywordWeight;
        private float vectorWeight;

        public QueryType(float keywordWeight, float vectorWeight) {
            this.keywordWeight = keywordWeight;
            this.vectorWeight = vectorWeight;
        }

        public float getKeywordWeight() {
            return keywordWeight;
        }

        public float getVectorWeight() {
            return vectorWeight;
        }
    }

    /**
     * 候选结果
     */
    public static class CandidateResult {
        private Long documentId;
        private String chunkId;
        private String content;
        private float keywordScore = 0.0f;
        private float vectorScore = 0.0f;
        private float combinedScore = 0.0f;
        private float rerankScore = 0.0f;

        public CandidateResult(Long documentId, String chunkId, String content) {
            this.documentId = documentId;
            this.chunkId = chunkId;
            this.content = content;
        }

        public Long getDocumentId() {
            return documentId;
        }

        public String getChunkId() {
            return chunkId;
        }

        public String getContent() {
            return content;
        }

        public float getKeywordScore() {
            return keywordScore;
        }

        public void setKeywordScore(float keywordScore) {
            this.keywordScore = keywordScore;
        }

        public float getVectorScore() {
            return vectorScore;
        }

        public void setVectorScore(float vectorScore) {
            this.vectorScore = vectorScore;
        }

        public float getCombinedScore() {
            return combinedScore;
        }

        public void setCombinedScore(float combinedScore) {
            this.combinedScore = combinedScore;
        }

        public float getRerankScore() {
            return rerankScore;
        }

        public void setRerankScore(float rerankScore) {
            this.rerankScore = rerankScore;
        }
    }
}

