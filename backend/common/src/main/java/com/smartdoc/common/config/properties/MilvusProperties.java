package com.smartdoc.common.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {
    private String host;
    private Integer port;
    private String collection;
    private Integer timeout = 30000;
}

