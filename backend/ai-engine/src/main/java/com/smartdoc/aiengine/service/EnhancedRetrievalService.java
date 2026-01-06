package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 增强的检索服务
 * 解决RAG检索不准确的问题：
 * 1. 混合检索（关键词+向量）
 * 2. 重排序
 * 3. 查询扩展
 * 4. 结果去重和合并
 */
@Slf4j
@Service
public class EnhancedRetrievalService {

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 增强检索：结合多种策略提高检索准确性
     */
    public List<MilvusService.SearchResult> enhancedSearch(
            String query, Long documentId, int topK) {
        
        log.info("开始增强检索: query={}, documentId={}, topK={}", query, documentId, topK);
        
        // 1. 查询扩展
        List<String> expandedQueries = expandQuery(query);
        
        // 2. 多路检索
        Set<MilvusService.SearchResult> allResults = new HashSet<>();
        
        // 2.1 原始查询的向量检索
        List<Float> queryVector = embeddingService.embedText(query);
        List<MilvusService.SearchResult> vectorResults = milvusService.searchSimilar(
                queryVector, topK * 2, documentId);
        allResults.addAll(vectorResults);
        
        // 2.2 扩展查询的向量检索
        for (String expandedQuery : expandedQueries) {
            List<Float> expandedVector = embeddingService.embedText(expandedQuery);
            List<MilvusService.SearchResult> expandedResults = milvusService.searchSimilar(
                    expandedVector, topK, documentId);
            allResults.addAll(expandedResults);
        }
        
        // 2.3 关键词检索（基于BM25或简单关键词匹配）
        // 注意：由于Milvus主要支持向量检索，关键词检索通过向量检索+关键词过滤实现
        List<MilvusService.SearchResult> keywordResults = keywordSearch(
                query, documentId, topK);
        if (keywordResults != null) {
            allResults.addAll(keywordResults);
        }
        
        // 3. 结果去重
        List<MilvusService.SearchResult> deduplicatedResults = deduplicateResults(
                new ArrayList<>(allResults));
        
        // 4. 重排序
        List<MilvusService.SearchResult> rerankedResults = rerankResults(
                deduplicatedResults, query);
        
        // 5. 返回Top K
        return rerankedResults.stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 查询扩展：生成相关查询变体
     */
    private List<String> expandQuery(String query) {
        List<String> expandedQueries = new ArrayList<>();
        
        // 提取关键词
        List<String> keywords = extractKeywords(query);
        
        // 为每个关键词生成查询变体
        for (String keyword : keywords) {
            if (keyword.length() > 1) {
                // 添加同义词或相关词（这里简化处理，实际可以使用同义词库）
                expandedQueries.add(keyword);
            }
        }
        
        // 添加完整查询（用于语义检索）
        expandedQueries.add(query);
        
        log.debug("查询扩展: 原始查询={}, 扩展查询数={}", query, expandedQueries.size());
        return expandedQueries;
    }

    /**
     * 提取关键词
     */
    private List<String> extractKeywords(String query) {
        // 简单的中文分词（实际可以使用专业分词工具如IKAnalyzer、HanLP等）
        String[] words = query.split("[\\s，。、；：！？]");
        List<String> keywords = new ArrayList<>();
        
        for (String word : words) {
            word = word.trim();
            if (word.length() >= 2) { // 过滤单字和空字符串
                keywords.add(word);
            }
        }
        
        return keywords;
    }

    /**
     * 关键词检索（基于内容匹配）
     * 注意：这是一个简化实现，实际可以使用Elasticsearch等全文搜索引擎
     */
    private List<MilvusService.SearchResult> keywordSearch(
            String query, Long documentId, int topK) {
        
        // 由于Milvus主要支持向量检索，这里我们通过向量检索获取更多结果
        // 然后基于关键词匹配进行过滤和重排序
        List<Float> queryVector = embeddingService.embedText(query);
        List<MilvusService.SearchResult> results = milvusService.searchSimilar(
                queryVector, topK * 3, documentId);
        
        // 提取查询关键词
        Set<String> queryKeywords = new HashSet<>(extractKeywords(query.toLowerCase()));
        
        // 计算关键词匹配分数
        List<MilvusService.SearchResult> scoredResults = new ArrayList<>();
        for (MilvusService.SearchResult result : results) {
            String content = result.getContent().toLowerCase();
            int matchCount = 0;
            for (String keyword : queryKeywords) {
                if (content.contains(keyword)) {
                    matchCount++;
                }
            }
            
            // 计算关键词匹配分数（0-1之间）
            float keywordScore = queryKeywords.isEmpty() ? 0 : 
                    (float) matchCount / queryKeywords.size();
            
            // 创建新的结果，结合向量分数和关键词分数
            // 注意：result.getScore()是距离（越小越好），需要转换为相似度分数
            float vectorSimilarity = 1.0f / (1.0f + result.getScore());
            float combinedScore = vectorSimilarity * 0.6f + keywordScore * 0.4f;
            // 将相似度分数转换回距离形式（用于统一处理）
            float combinedDistance = 1.0f / combinedScore - 1.0f;
            scoredResults.add(new MilvusService.SearchResult(
                    result.getDocumentId(),
                    result.getChunkId(),
                    result.getContent(),
                    combinedDistance
            ));
        }
        
        // 按综合分数排序
        scoredResults.sort((a, b) -> Float.compare(a.getScore(), b.getScore()));
        
        return scoredResults.stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 结果去重：去除重复和高度相似的结果
     */
    private List<MilvusService.SearchResult> deduplicateResults(
            List<MilvusService.SearchResult> results) {
        
        List<MilvusService.SearchResult> deduplicated = new ArrayList<>();
        Set<String> seenContents = new HashSet<>();
        
        for (MilvusService.SearchResult result : results) {
            String content = normalizeContent(result.getContent());
            
            // 检查是否与已有结果高度相似
            boolean isDuplicate = false;
            for (String seenContent : seenContents) {
                if (calculateSimilarity(content, seenContent) > 0.8) {
                    isDuplicate = true;
                    break;
                }
            }
            
            if (!isDuplicate) {
                deduplicated.add(result);
                seenContents.add(content);
            }
        }
        
        log.debug("去重前: {} 条结果, 去重后: {} 条结果", results.size(), deduplicated.size());
        return deduplicated;
    }

    /**
     * 标准化内容（用于相似度比较）
     */
    private String normalizeContent(String content) {
        return content.toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 计算两个字符串的相似度（使用Jaccard相似度）
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
     * 重排序：基于多种因素对结果重新排序
     */
    private List<MilvusService.SearchResult> rerankResults(
            List<MilvusService.SearchResult> results, String query) {
        
        List<RerankedResult> reranked = new ArrayList<>();
        
        // 提取查询关键词
        Set<String> queryKeywords = new HashSet<>(extractKeywords(query.toLowerCase()));
        
        for (MilvusService.SearchResult result : results) {
            float rerankScore = calculateRerankScore(result, query, queryKeywords);
            reranked.add(new RerankedResult(result, rerankScore));
        }
        
        // 按重排序分数排序
        reranked.sort((a, b) -> Float.compare(b.score, a.score));
        
        // 转换为原始结果格式
        return reranked.stream()
                .map(r -> {
                    // 更新分数为重排序分数
                    return new MilvusService.SearchResult(
                            r.result.getDocumentId(),
                            r.result.getChunkId(),
                            r.result.getContent(),
                            r.score
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * 计算重排序分数
     */
    private float calculateRerankScore(
            MilvusService.SearchResult result, String query, Set<String> queryKeywords) {
        
        float score = 0.0f;
        String content = result.getContent().toLowerCase();
        
        // 1. 向量相似度分数（40%）
        float vectorScore = 1.0f / (1.0f + result.getScore()); // 将距离转换为相似度
        score += vectorScore * 0.4f;
        
        // 2. 关键词匹配分数（30%）
        int keywordMatches = 0;
        for (String keyword : queryKeywords) {
            if (content.contains(keyword)) {
                keywordMatches++;
            }
        }
        float keywordScore = queryKeywords.isEmpty() ? 0 : 
                (float) keywordMatches / queryKeywords.size();
        score += keywordScore * 0.3f;
        
        // 3. 位置分数（10%）：优先选择文档前面的内容
        // 从chunkId中提取索引（格式：docId_chunkIndex）
        try {
            String[] parts = result.getChunkId().split("_");
            if (parts.length >= 2) {
                int chunkIndex = Integer.parseInt(parts[parts.length - 1]);
                float positionScore = 1.0f / (1.0f + chunkIndex * 0.1f); // 前面的块分数更高
                score += positionScore * 0.1f;
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        // 4. 内容长度分数（10%）：适中的长度更好
        int contentLength = content.length();
        float lengthScore = 0.0f;
        if (contentLength >= 200 && contentLength <= 500) {
            lengthScore = 1.0f;
        } else if (contentLength < 200) {
            lengthScore = contentLength / 200.0f;
        } else {
            lengthScore = Math.max(0, 1.0f - (contentLength - 500) / 500.0f);
        }
        score += lengthScore * 0.1f;
        
        // 5. 查询完整性分数（10%）：检查是否包含完整的查询短语
        if (content.contains(query.toLowerCase())) {
            score += 0.1f;
        }
        
        return score;
    }

    /**
     * 重排序结果包装类
     */
    private static class RerankedResult {
        MilvusService.SearchResult result;
        float score;

        RerankedResult(MilvusService.SearchResult result, float score) {
            this.result = result;
            this.score = score;
        }
    }
}

