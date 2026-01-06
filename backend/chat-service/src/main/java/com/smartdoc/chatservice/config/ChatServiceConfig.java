package com.smartdoc.chatservice.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.smartdoc.chatservice.mapper")
public class ChatServiceConfig {
}

