package com.smartdoc.authservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartdoc.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user")
public class User extends BaseEntity {
    private String username;
    private String password;
    private String email;
    private String role; // admin, user
    private Integer status; // 0-禁用, 1-启用
}

