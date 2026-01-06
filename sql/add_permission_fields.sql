-- 为文档表添加权限相关字段
USE `smart_doc_qa`;

-- 添加访问类型字段：public-公开, private-私有, role-角色限制
ALTER TABLE `document` 
ADD COLUMN `access_type` VARCHAR(20) DEFAULT 'private' COMMENT '访问类型：public-公开, private-私有, role-角色限制' AFTER `status`;

-- 添加允许的角色列表（JSON格式，如 ["admin", "user"]）
ALTER TABLE `document` 
ADD COLUMN `allowed_roles` TEXT DEFAULT NULL COMMENT '允许的角色列表（JSON格式）' AFTER `access_type`;

-- 更新现有文档的默认权限为私有
UPDATE `document` SET `access_type` = 'private' WHERE `access_type` IS NULL;

