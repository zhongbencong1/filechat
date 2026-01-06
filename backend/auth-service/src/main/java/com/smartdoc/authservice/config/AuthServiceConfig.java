package com.smartdoc.authservice.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.smartdoc.authservice.mapper")
public class AuthServiceConfig {
}

