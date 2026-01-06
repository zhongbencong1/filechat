package com.smartdoc.authservice.controller;

import com.smartdoc.authservice.entity.User;
import com.smartdoc.authservice.service.AuthService;
import com.smartdoc.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        
        String token = authService.login(username, password);
        User user = authService.getUserById(authService.validateToken(token));
        
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", user);
        
        return Result.success(result);
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<User> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        String email = request.get("email");
        
        User user = authService.register(username, password, email);
        return Result.success(user);
    }

    /**
     * 验证token
     */
    @GetMapping("/validate")
    public Result<Long> validateToken(@RequestHeader("Authorization") String token) {
        Long userId = authService.validateToken(token.replace("Bearer ", ""));
        if (userId == null) {
            return Result.error(401, "Token无效");
        }
        return Result.success(userId);
    }
}

