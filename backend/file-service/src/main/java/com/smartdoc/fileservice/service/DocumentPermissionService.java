package com.smartdoc.fileservice.service;

import com.alibaba.fastjson2.JSON;
import com.smartdoc.common.exception.BusinessException;
import com.smartdoc.fileservice.entity.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档权限服务
 */
@Slf4j
@Service
public class DocumentPermissionService {

    @Autowired
    private DocumentService documentService;

    /**
     * 检查用户是否有权限访问文档
     * @param documentId 文档ID
     * @param userId 用户ID
     * @param userRole 用户角色
     * @return 是否有权限
     */
    public boolean hasPermission(Long documentId, Long userId, String userRole) {
        Document document = documentService.getDocumentByIdWithoutPermissionCheck(documentId);
        
        if (document == null) {
            return false;
        }

        String accessType = document.getAccessType();
        if (accessType == null || "private".equals(accessType)) {
            // 私有文档：只有上传者可以访问
            return document.getUserId().equals(userId);
        } else if ("public".equals(accessType)) {
            // 公开文档：所有人可以访问
            return true;
        } else if ("role".equals(accessType)) {
            // 角色限制：检查用户角色是否在允许列表中
            String allowedRolesJson = document.getAllowedRoles();
            if (allowedRolesJson == null || allowedRolesJson.isEmpty()) {
                // 如果没有配置允许的角色，则只有上传者可以访问
                return document.getUserId().equals(userId);
            }
            
            try {
                List<String> allowedRoles = JSON.parseArray(allowedRolesJson, String.class);
                return allowedRoles != null && allowedRoles.contains(userRole);
            } catch (Exception e) {
                log.error("解析允许角色列表失败: documentId={}, allowedRoles={}", documentId, allowedRolesJson, e);
                // 解析失败时，只有上传者可以访问
                return document.getUserId().equals(userId);
            }
        }
        
        // 默认只有上传者可以访问
        return document.getUserId().equals(userId);
    }

    /**
     * 检查用户是否有权限访问文档（带异常抛出）
     * @param documentId 文档ID
     * @param userId 用户ID
     * @param userRole 用户角色
     * @throws BusinessException 无权限时抛出异常
     */
    public void checkPermission(Long documentId, Long userId, String userRole) {
        if (!hasPermission(documentId, userId, userRole)) {
            throw new BusinessException("无权限访问该文档");
        }
    }
}

