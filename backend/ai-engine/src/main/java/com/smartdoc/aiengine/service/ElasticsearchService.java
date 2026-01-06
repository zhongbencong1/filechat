package com.smartdoc.aiengine.service;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Elasticsearch服务
 * 用于关键词/全文检索
 */
@Slf4j
@Service
public class ElasticsearchService {

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private Integer port;

    @Value("${elasticsearch.index:document_chunks}")
    private String indexName;

    @Autowired(required = false)
    private RestHighLevelClient elasticsearchClient;

    private static final String MAPPING = "{\n" +
            "  \"mappings\": {\n" +
            "    \"properties\": {\n" +
            "      \"chunk_id\": {\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"document_id\": {\n" +
            "        \"type\": \"long\"\n" +
            "      },\n" +
            "      \"content\": {\n" +
            "        \"type\": \"text\",\n" +
            "        \"analyzer\": \"ik_max_word\",\n" +
            "        \"search_analyzer\": \"ik_smart\"\n" +
            "      },\n" +
            "      \"title\": {\n" +
            "        \"type\": \"text\",\n" +
            "        \"analyzer\": \"ik_max_word\"\n" +
            "      },\n" +
            "      \"entity\": {\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"date\": {\n" +
            "        \"type\": \"date\",\n" +
            "        \"format\": \"yyyy-MM-dd HH:mm:ss\"\n" +
            "      },\n" +
            "      \"chunk_index\": {\n" +
            "        \"type\": \"integer\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    @PostConstruct
    public void init() {
        if (elasticsearchClient == null) {
            log.warn("Elasticsearch客户端未配置，将使用简化实现");
            return;
        }
        createIndexIfNotExists();
    }

    @PreDestroy
    public void destroy() {
        if (elasticsearchClient != null) {
            try {
                elasticsearchClient.close();
            } catch (IOException e) {
                log.error("关闭Elasticsearch客户端失败", e);
            }
        }
    }

    /**
     * 创建索引（如果不存在）
     */
    private void createIndexIfNotExists() {
        if (elasticsearchClient == null) {
            return;
        }
        
        try {
            GetIndexRequest request = new GetIndexRequest(indexName);
            boolean exists = elasticsearchClient.indices().exists(request, RequestOptions.DEFAULT);
            
            if (!exists) {
                CreateIndexRequest createRequest = new CreateIndexRequest(indexName);
                createRequest.source(MAPPING, XContentType.JSON);
                CreateIndexResponse response = elasticsearchClient.indices()
                        .create(createRequest, RequestOptions.DEFAULT);
                
                if (response.isAcknowledged()) {
                    log.info("Elasticsearch索引 {} 创建成功", indexName);
                } else {
                    log.warn("Elasticsearch索引 {} 创建未确认", indexName);
                }
            } else {
                log.info("Elasticsearch索引 {} 已存在", indexName);
            }
        } catch (Exception e) {
            log.error("创建Elasticsearch索引失败", e);
        }
    }

    /**
     * 批量索引文档块
     */
    public void indexChunks(Long documentId, String documentTitle, 
                           List<TextPreprocessService.TextChunk> chunks) {
        if (elasticsearchClient == null) {
            log.warn("Elasticsearch客户端未配置，跳过索引构建");
            return;
        }

        try {
            BulkRequest bulkRequest = new BulkRequest();
            String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            for (TextPreprocessService.TextChunk chunk : chunks) {
                Map<String, Object> source = new HashMap<>();
                source.put("chunk_id", chunk.getChunkId());
                source.put("document_id", documentId);
                source.put("content", chunk.getContent());
                source.put("title", documentTitle);
                source.put("chunk_index", chunk.getChunkIndex());
                source.put("date", currentDate);
                
                // 提取关键实体（简化实现，实际可以使用NER模型）
                List<String> entities = extractEntities(chunk.getContent());
                source.put("entity", entities);
                
                IndexRequest indexRequest = new IndexRequest(indexName)
                        .id(chunk.getChunkId())
                        .source(source);
                bulkRequest.add(indexRequest);
            }
            
            BulkResponse bulkResponse = elasticsearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
            if (bulkResponse.hasFailures()) {
                log.error("批量索引失败: {}", bulkResponse.buildFailureMessage());
            } else {
                log.info("成功索引 {} 个文档块到Elasticsearch", chunks.size());
            }
        } catch (Exception e) {
            log.error("索引文档块到Elasticsearch失败", e);
        }
    }

    /**
     * 关键词检索
     */
    public List<SearchResult> keywordSearch(String query, Long documentId, int topK) {
        if (elasticsearchClient == null) {
            log.warn("Elasticsearch客户端未配置，返回空结果");
            return Collections.emptyList();
        }

        try {
            SearchRequest searchRequest = new SearchRequest(indexName);
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            
            // 构建查询
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            
            // 关键词查询（使用multi_match，支持多字段）
            boolQuery.must(QueryBuilders.multiMatchQuery(query, "content", "title")
                    .type(org.elasticsearch.index.query.MultiMatchQueryBuilder.Type.BEST_FIELDS)
                    .boost(2.0f));
            
            // 如果指定了文档ID，添加过滤条件
            if (documentId != null) {
                boolQuery.filter(QueryBuilders.termQuery("document_id", documentId));
            }
            
            sourceBuilder.query(boolQuery);
            sourceBuilder.size(topK);
            sourceBuilder.sort("_score", SortOrder.DESC);
            sourceBuilder.sort("date", SortOrder.DESC); // 按时间排序
            
            searchRequest.source(sourceBuilder);
            
            SearchResponse searchResponse = elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);
            
            List<SearchResult> results = new ArrayList<>();
            for (SearchHit hit : searchResponse.getHits().getHits()) {
                Map<String, Object> source = hit.getSourceAsMap();
                String chunkId = (String) source.get("chunk_id");
                String content = (String) source.get("content");
                Long docId = ((Number) source.get("document_id")).longValue();
                float score = hit.getScore();
                
                results.add(new SearchResult(docId, chunkId, content, score));
            }
            
            log.info("Elasticsearch关键词检索返回 {} 条结果", results.size());
            return results;
        } catch (Exception e) {
            log.error("Elasticsearch关键词检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除文档的所有索引
     */
    public void deleteByDocumentId(Long documentId) {
        if (elasticsearchClient == null) {
            return;
        }

        try {
            SearchRequest searchRequest = new SearchRequest(indexName);
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.termQuery("document_id", documentId));
            sourceBuilder.size(10000); // 获取所有匹配的文档
            
            searchRequest.source(sourceBuilder);
            SearchResponse searchResponse = elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);
            
            BulkRequest bulkRequest = new BulkRequest();
            for (SearchHit hit : searchResponse.getHits().getHits()) {
                DeleteRequest deleteRequest = new DeleteRequest(indexName, hit.getId());
                bulkRequest.add(deleteRequest);
            }
            
            if (bulkRequest.numberOfActions() > 0) {
                BulkResponse bulkResponse = elasticsearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
                if (bulkResponse.hasFailures()) {
                    log.error("删除Elasticsearch索引失败: {}", bulkResponse.buildFailureMessage());
                } else {
                    log.info("成功删除文档 {} 的 {} 个Elasticsearch索引", documentId, bulkRequest.numberOfActions());
                }
            }
        } catch (Exception e) {
            log.error("删除Elasticsearch索引失败", e);
        }
    }

    /**
     * 提取关键实体（简化实现）
     * 实际可以使用NER模型如HanLP、spaCy等
     */
    private List<String> extractEntities(String content) {
        List<String> entities = new ArrayList<>();
        
        // 简单实现：提取可能的关键词（长度>=2的中文词）
        String[] words = content.split("[\\s，。、；：！？\n]");
        for (String word : words) {
            word = word.trim();
            if (word.length() >= 2 && word.length() <= 10) {
                // 简单过滤：只保留看起来像实体的词
                if (word.matches("[\\u4e00-\\u9fa5]+")) {
                    entities.add(word);
                    if (entities.size() >= 10) { // 最多提取10个实体
                        break;
                    }
                }
            }
        }
        
        return entities;
    }

    /**
     * 搜索结果实体
     */
    public static class SearchResult {
        private Long documentId;
        private String chunkId;
        private String content;
        private Float score;

        public SearchResult(Long documentId, String chunkId, String content, Float score) {
            this.documentId = documentId;
            this.chunkId = chunkId;
            this.content = content;
            this.score = score;
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

        public Float getScore() {
            return score;
        }
    }
}

