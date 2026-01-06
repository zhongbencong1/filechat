package com.smartdoc.authservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdoc.authservice.entity.User;
import com.smartdoc.authservice.mapper.UserMapper;
import com.smartdoc.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 权限管理服务
 */
@Slf4j
@Service
public class AuthService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String TOKEN_PREFIX = "token:";
    private static final int TOKEN_EXPIRE_HOURS = 24;

    /**
     * 用户登录
     */
    public String login(String username, String password) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        if (!password.equals(user.getPassword())) { // 实际应使用加密密码
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }

        // 生成token
        String token = UUID.randomUUID().toString().replace("-", "");
        
        // 存储token到Redis
        String tokenKey = TOKEN_PREFIX + token;
        redisTemplate.opsForValue().set(tokenKey, user.getId(), TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
        
        // 存储用户信息到Redis（包含角色）
        String userInfoKey = "user:info:" + token;
        java.util.Map<String, Object> userInfo = new java.util.HashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("role", user.getRole());
        redisTemplate.opsForValue().set(userInfoKey, com.alibaba.fastjson2.JSON.toJSONString(userInfo), 
                TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);

        log.info("用户登录成功: userId={}, username={}, role={}", user.getId(), username, user.getRole());
        return token;
    }

    /**
     * 验证token
     */
    public Long validateToken(String token) {
        String tokenKey = TOKEN_PREFIX + token;
        Object userId = redisTemplate.opsForValue().get(tokenKey);
        if (userId == null) {
            return null;
        }
        return Long.parseLong(userId.toString());
    }

    /**
     * 用户注册
     */
    @Transactional
    public User register(String username, String password, String email) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (userMapper.selectOne(wrapper) != null) {
            throw new BusinessException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(password); // 实际应加密
        user.setEmail(email);
        user.setRole("user");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        userMapper.insert(user);
        log.info("用户注册成功: userId={}, username={}", user.getId(), username);
        return user;
    }

    /**
     * 获取用户信息
     */
    public User getUserById(Long userId) {
        return userMapper.selectById(userId);
    }
}

