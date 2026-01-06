package com.smartdoc.fileservice.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.smartdoc.fileservice.mapper")
public class FileServiceConfig {
}

