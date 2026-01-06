package com.smartdoc.gateway.filter;

import com.smartdoc.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关认证过滤器
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private static final String TOKEN_PREFIX = "token:";
    private static final String[] WHITE_LIST = {"/api/auth/login", "/api/auth/register"};

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        final ServerHttpRequest request = exchange.getRequest();
        final String path = request.getURI().getPath();

        // 白名单路径直接放行
        for (String whitePath : WHITE_LIST) {
            if (path.equals(whitePath) || path.startsWith(whitePath + "/")) {
                log.debug("白名单路径，直接放行: {}", path);
                return chain.filter(exchange);
            }
        }

        // 获取token
        String authHeader = request.getHeaders().getFirst("Authorization");
        final String token;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            token = request.getQueryParams().getFirst("token");
        }

        if (token == null || token.isEmpty()) {
            log.warn("未提供认证token: path={}", path);
            return unauthorized(exchange.getResponse(), "未提供认证token");
        }

        // 验证token（使用阻塞式 RedisTemplate，在响应式环境中需要包装）
        if (redisTemplate == null) {
            log.error("RedisTemplate 未初始化，无法验证token");
            return unauthorized(exchange.getResponse(), "服务配置错误");
        }

        final String tokenKey = TOKEN_PREFIX + token;
        final String userInfoKey = "user:info:" + token;
        final ServerWebExchange finalExchange = exchange;
        final GatewayFilterChain finalChain = chain;
        return Mono.fromCallable(() -> {
            Object userId = redisTemplate.opsForValue().get(tokenKey);
            Object userInfo = redisTemplate.opsForValue().get(userInfoKey);
            return new Object[]{userId, userInfo};
        })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(result -> {
                    Object userId = ((Object[]) result)[0];
                    Object userInfo = ((Object[]) result)[1];
                    
                    if (userId == null) {
                        log.warn("Token无效或已过期: token={}", token);
                        return unauthorized(finalExchange.getResponse(), "Token无效或已过期");
                    }

                    // 将userId添加到请求头
                    ServerHttpRequest.Builder requestBuilder = request.mutate()
                            .header("X-User-Id", userId.toString());
                    
                    // 如果有用户信息（包含角色），也添加到请求头
                    if (userInfo != null) {
                        try {
                            String userInfoStr = userInfo.toString();
                            com.alibaba.fastjson2.JSONObject userInfoObj = com.alibaba.fastjson2.JSON.parseObject(userInfoStr);
                            String role = userInfoObj.getString("role");
                            if (role != null) {
                                requestBuilder.header("X-User-Role", role);
                            }
                        } catch (Exception e) {
                            log.debug("解析用户信息失败，使用默认角色", e);
                            requestBuilder.header("X-User-Role", "user");
                        }
                    } else {
                        requestBuilder.header("X-User-Role", "user");
                    }
                    
                    ServerHttpRequest modifiedRequest = requestBuilder.build();

                    log.debug("Token验证成功: userId={}, path={}", userId, path);
                    return finalChain.filter(finalExchange.mutate().request(modifiedRequest).build());
                })
                .onErrorResume(e -> {
                    log.error("Token验证异常", e);
                    return unauthorized(finalExchange.getResponse(), "Token验证失败");
                });
    }

    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        Result<?> result = Result.error(401, message);
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(com.alibaba.fastjson2.JSON.toJSONString(result).getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}

