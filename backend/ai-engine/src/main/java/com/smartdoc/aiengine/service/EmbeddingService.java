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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化服务
 * 使用开源Embedding模型或API进行文本向量化
 */
@Slf4j
@Service
public class EmbeddingService {

    @Value("${ai.embedding.api-url:http://localhost:8000/v1/embeddings}")
    private String embeddingApiUrl;

    @Value("${ai.embedding.model:text-embedding-ada-002}")
    private String embeddingModel;

    /**
     * 将文本转换为向量
     * @param text 文本内容
     * @return 向量数组（维度通常为768或1536）
     */
    public List<Float> embedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }

        try {
            // 调用Embedding API
            return callEmbeddingAPI(text);
        } catch (Exception e) {
            log.error("文本向量化失败", e);
            // 降级方案：使用简单的词频向量（仅作示例，实际应使用真实模型）
            return generateSimpleEmbedding(text);
        }
    }

    /**
     * 批量向量化
     */
    public List<List<Float>> embedTexts(List<String> texts) {
        List<List<Float>> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(embedText(text));
        }
        return embeddings;
    }

    /**
     * 调用Embedding API
     */
    private List<Float> callEmbeddingAPI(String text) throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(embeddingApiUrl);
        
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", embeddingModel);
        requestBody.put("input", text);
        
        StringEntity entity = new StringEntity(
            com.alibaba.fastjson2.JSON.toJSONString(requestBody),
            StandardCharsets.UTF_8
        );
        entity.setContentType("application/json");
        httpPost.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            
            if (response.getStatusLine().getStatusCode() == 200) {
                Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(responseBody, Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                if (data != null && !data.isEmpty()) {
                    List<Double> embedding = (List<Double>) data.get(0).get("embedding");
                    // 转换为Float列表
                    List<Float> floatEmbedding = new ArrayList<>();
                    for (Double d : embedding) {
                        floatEmbedding.add(d.floatValue());
                    }
                    return floatEmbedding;
                }
            }
            
            throw new RuntimeException("Embedding API调用失败: " + responseBody);
        } finally {
            httpClient.close();
        }
    }

    /**
     * 简单的向量生成（降级方案，仅作示例）
     * 实际生产环境应使用真实的Embedding模型
     */
    private List<Float> generateSimpleEmbedding(String text) {
        // 这是一个简化的示例，实际应使用真实的Embedding模型
        // 例如：使用sentence-transformers、BERT等
        List<Float> embedding = new ArrayList<>();
        int dimension = 768; // 常见维度
        
        // 简单的哈希向量化（仅作示例）
        for (int i = 0; i < dimension; i++) {
            float value = (text.hashCode() + i) % 1000 / 1000.0f;
            embedding.add(value);
        }
        
        log.warn("使用降级向量化方案，建议配置真实的Embedding模型");
        return embedding;
    }

    /**
     * 获取向量维度
     */
    public int getDimension() {
        return 768; // 根据实际模型调整
    }
}

