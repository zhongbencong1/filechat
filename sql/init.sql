-- 智能文档问答系统数据库初始化脚本
-- 数据库：smart_doc_qa
-- 字符集：utf8mb4

CREATE DATABASE IF NOT EXISTS `smart_doc_qa` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `smart_doc_qa`;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `role` VARCHAR(20) DEFAULT 'user' COMMENT '角色：admin-管理员, user-普通用户',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态：0-禁用, 1-启用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 文档表
CREATE TABLE IF NOT EXISTS `document` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    `file_name` VARCHAR(255) NOT NULL COMMENT '文件名',
    `file_type` VARCHAR(20) NOT NULL COMMENT '文件类型：doc, docx, txt, ppt, pptx, pdf',
    `file_size` BIGINT(20) DEFAULT NULL COMMENT '文件大小（字节）',
    `file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件路径',
    `minio_bucket` VARCHAR(100) DEFAULT NULL COMMENT 'MinIO桶名',
    `minio_object` VARCHAR(500) DEFAULT NULL COMMENT 'MinIO对象名',
    `user_id` BIGINT(20) NOT NULL COMMENT '上传用户ID',
    `status` TINYINT(1) DEFAULT 0 COMMENT '状态：0-上传中, 1-解析中, 2-解析完成, 3-解析失败',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

-- 对话消息表
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `document_id` BIGINT(20) DEFAULT NULL COMMENT '文档ID（为空表示通用对话）',
    `question` TEXT NOT NULL COMMENT '用户问题',
    `answer` TEXT NOT NULL COMMENT 'AI回答',
    `source_chunks` TEXT DEFAULT NULL COMMENT '来源文本块ID（JSON格式）',
    `is_general_answer` TINYINT(1) DEFAULT 0 COMMENT '是否通用回答：0-文档回答, 1-通用回答',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';

-- 初始化管理员用户
INSERT INTO `user` (`username`, `password`, `email`, `role`, `status`) VALUES
('admin', 'admin123', 'admin@example.com', 'admin', 1);

-- 初始化测试用户
INSERT INTO `user` (`username`, `password`, `email`, `role`, `status`) VALUES
('test', 'test123', 'test@example.com', 'user', 1);

