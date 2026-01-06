package com.smartdoc.aiengine.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;

/**
 * Milvus向量数据库服务
 */
@Slf4j
@Service
public class MilvusService {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private Integer port;

    @Value("${milvus.collection:document_vectors}")
    private String collectionName;

    private MilvusServiceClient milvusClient;
    private static final int VECTOR_DIMENSION = 768; // 向量维度

    @PostConstruct
    public void init() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        
        milvusClient = new MilvusServiceClient(connectParam);
        
        // 确保集合存在
        createCollectionIfNotExists();
        
        log.info("Milvus连接成功");
    }

    @PreDestroy
    public void destroy() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    /**
     * 创建集合（如果不存在）
     */
    private void createCollectionIfNotExists() {
        // 检查集合是否存在
        R<Boolean> hasCollection = milvusClient.hasCollection(
            HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build()
        );

        if (!hasCollection.getData()) {
            // 定义字段
            FieldType documentIdField = FieldType.newBuilder()
                    .withName("document_id")
                    .withDataType(DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();

            FieldType chunkIdField = FieldType.newBuilder()
                    .withName("chunk_id")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(100)
                    .build();

            FieldType contentField = FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(2000)
                    .build();

            FieldType vectorField = FieldType.newBuilder()
                    .withName("vector")
                    .withDataType(DataType.FloatVector)
                    .withDimension(VECTOR_DIMENSION)
                    .build();

            // 创建集合
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("文档向量存储集合")
                    .withShardsNum(2)
                    .addFieldType(documentIdField)
                    .addFieldType(chunkIdField)
                    .addFieldType(contentField)
                    .addFieldType(vectorField)
                    .build();

            R<RpcStatus> createResult = milvusClient.createCollection(createParam);
            if (createResult.getStatus() == R.Status.Success.getCode()) {
                log.info("集合 {} 创建成功", collectionName);
                
                // 创建索引
                createIndex();
            } else {
                log.error("集合创建失败: {}", createResult.getMessage());
            }
        } else {
            log.info("集合 {} 已存在", collectionName);
        }
    }

    /**
     * 创建向量索引
     */
    private void createIndex() {
        IndexType indexType = IndexType.IVF_FLAT;
        String indexParam = "{\"nlist\":1024}";

        CreateIndexParam indexParamBuilder = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .withIndexType(indexType)
                .withMetricType(MetricType.L2)
                .withExtraParam(indexParam)
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> indexResult = milvusClient.createIndex(indexParamBuilder);
        if (indexResult.getStatus() == R.Status.Success.getCode()) {
            log.info("向量索引创建成功");
        } else {
            log.error("向量索引创建失败: {}", indexResult.getMessage());
        }
    }

    /**
     * 插入向量数据
     */
    public void insertVectors(Long documentId, List<String> chunkIds, List<String> contents, List<List<Float>> vectors) {
        if (chunkIds.size() != contents.size() || chunkIds.size() != vectors.size()) {
            throw new IllegalArgumentException("数据长度不一致");
        }

        List<Long> documentIds = new ArrayList<>();
        for (int i = 0; i < chunkIds.size(); i++) {
            documentIds.add(documentId);
        }

        List<List<Float>> vectorList = new ArrayList<>(vectors);

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("document_id", documentIds));
        fields.add(new InsertParam.Field("chunk_id", chunkIds));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("vector", vectorList));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        R<MutationResult> insertResult = milvusClient.insert(insertParam);
        if (insertResult.getStatus() == R.Status.Success.getCode()) {
            log.info("成功插入 {} 条向量数据", chunkIds.size());
        } else {
            log.error("向量插入失败: {}", insertResult.getMessage());
            throw new RuntimeException("向量插入失败: " + insertResult.getMessage());
        }
    }

    /**
     * 向量相似度搜索
     * @param queryVector 查询向量
     * @param topK 返回Top K个结果
     * @return 相似文本块列表
     */
    public List<SearchResult> searchSimilar(List<Float> queryVector, int topK) {
        return searchSimilar(queryVector, topK, null);
    }

    /**
     * 向量相似度搜索（带文档ID过滤）
     */
    public List<SearchResult> searchSimilar(List<Float> queryVector, int topK, Long documentId) {
        // 加载集合
        R<RpcStatus> loadResult = milvusClient.loadCollection(
            LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build()
        );

        if (loadResult.getStatus() != R.Status.Success.getCode()) {
            log.error("集合加载失败: {}", loadResult.getMessage());
            return Collections.emptyList();
        }

        // 构建搜索参数
        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.L2)
                .withOutFields(Arrays.asList("chunk_id", "content", "document_id"))
                .withTopK(topK)
                .withVectors(Collections.singletonList(queryVector))
                .withVectorFieldName("vector")
                .withParams("{\"nprobe\":10}");

        // 如果指定了文档ID，添加过滤条件
        if (documentId != null) {
            searchBuilder.withExpr("document_id == " + documentId);
        }

        R<SearchResults> searchResult = milvusClient.search(searchBuilder.build());

        if (searchResult.getStatus() != R.Status.Success.getCode()) {
            log.error("向量搜索失败: {}", searchResult.getMessage());
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();
        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResult.getData().getResults());

        for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
            String chunkId = wrapper.getFieldWrapper("chunk_id").getFieldData().get(i).toString();
            String content = wrapper.getFieldWrapper("content").getFieldData().get(i).toString();
            Long docId = Long.parseLong(wrapper.getFieldWrapper("document_id").getFieldData().get(i).toString());
            float score = wrapper.getIDScore(0).get(i).getScore();

            results.add(new SearchResult(docId, chunkId, content, score));
        }

        return results;
    }

    /**
     * 删除文档的所有向量
     */
    public void deleteByDocumentId(Long documentId) {
        String expr = "document_id == " + documentId;
        
        R<MutationResult> deleteResult = milvusClient.delete(
            DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build()
        );

        if (deleteResult.getStatus() == R.Status.Success.getCode()) {
            log.info("成功删除文档 {} 的所有向量", documentId);
        } else {
            log.error("删除向量失败: {}", deleteResult.getMessage());
        }
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

