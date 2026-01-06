package com.smartdoc.common.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI服务配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AIProperties {
    private Embedding embedding = new Embedding();
    private LLM llm = new LLM();

    @Data
    public static class Embedding {
        private String apiUrl;
        private String model;
        private Integer timeout = 30000;
        private Integer dimension = 768;
    }

    @Data
    public static class LLM {
        private String apiUrl;
        private String apiKey;
        private String model;
        private Integer timeout = 60000;
        private Integer maxTokens = 2000;
        private Double temperature = 0.7;
    }
}

