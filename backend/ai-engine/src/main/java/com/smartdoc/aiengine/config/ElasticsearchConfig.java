package com.smartdoc.aiengine.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch配置
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private Integer port;

    @Bean
    public RestHighLevelClient elasticsearchClient() {
        try {
            return new RestHighLevelClient(
                    RestClient.builder(new HttpHost(host, port, "http"))
            );
        } catch (Exception e) {
            // 如果Elasticsearch不可用，返回null，系统会使用简化实现
            return null;
        }
    }
}

