package com.smartdoc.fileservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdoc.common.exception.BusinessException;
import com.smartdoc.fileservice.entity.Document;
import com.smartdoc.fileservice.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档管理服务
 */
@Slf4j
@Service
public class DocumentService {

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private MinioService minioService;

    @Autowired
    private DocumentPermissionService permissionService;

    // 支持的文件类型
    private static final String[] ALLOWED_TYPES = {"doc", "docx", "txt", "ppt", "pptx", "pdf"};

    /**
     * 上传文档
     */
    @Transactional
    public Document uploadDocument(MultipartFile file, Long userId) {
        // 文件格式校验
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }

        String fileType = getFileType(originalFilename);
        if (!isAllowedType(fileType)) {
            throw new BusinessException("不支持的文件格式，仅支持: doc, docx, txt, ppt, pptx, pdf");
        }

        try {
            // 生成唯一对象名
            String objectName = generateObjectName(userId, originalFilename);
            
            // 上传到MinIO
            minioService.uploadFile(file, objectName);

            // 保存文档元数据
            Document document = new Document();
            document.setFileName(originalFilename);
            document.setFileType(fileType);
            document.setFileSize(file.getSize());
            document.setFilePath(objectName);
            document.setMinioBucket(minioService.getBucketName());
            document.setMinioObject(objectName);
            document.setUserId(userId);
            document.setStatus(0); // 上传中
            document.setAccessType("private"); // 默认私有
            document.setCreateTime(LocalDateTime.now());
            document.setUpdateTime(LocalDateTime.now());

            documentMapper.insert(document);

            log.info("文档上传成功: documentId={}, fileName={}", document.getId(), originalFilename);
            return document;
        } catch (Exception e) {
            log.error("文档上传失败", e);
            throw new BusinessException("文档上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文档列表（包含用户有权限访问的文档）
     */
    public List<Document> getDocumentList(Long userId, String userRole) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        // 获取用户上传的文档或公开的文档
        wrapper.and(w -> w.eq(Document::getUserId, userId)
                .or()
                .eq(Document::getAccessType, "public"));
        wrapper.orderByDesc(Document::getCreateTime);
        List<Document> documents = documentMapper.selectList(wrapper);
        
        // 过滤出用户有权限访问的文档（包括角色限制的文档）
        return documents.stream()
                .filter(doc -> permissionService.hasPermission(doc.getId(), userId, userRole))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 根据ID获取文档（带权限检查）
     */
    public Document getDocumentById(Long documentId, Long userId, String userRole) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        // 权限校验
        permissionService.checkPermission(documentId, userId, userRole);
        return document;
    }

    /**
     * 根据ID获取文档（不检查权限，内部使用）
     */
    public Document getDocumentByIdWithoutPermissionCheck(Long documentId) {
        return documentMapper.selectById(documentId);
    }

    /**
     * 设置文档权限
     */
    @Transactional
    public void setDocumentPermission(Long documentId, Long userId, String accessType, List<String> allowedRoles) {
        Document document = getDocumentByIdWithoutPermissionCheck(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        // 只有文档所有者可以设置权限
        if (!document.getUserId().equals(userId)) {
            throw new BusinessException("只有文档所有者可以设置权限");
        }
        
        document.setAccessType(accessType);
        if (allowedRoles != null && !allowedRoles.isEmpty()) {
            document.setAllowedRoles(com.alibaba.fastjson2.JSON.toJSONString(allowedRoles));
        } else {
            document.setAllowedRoles(null);
        }
        document.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(document);
        
        log.info("文档权限设置成功: documentId={}, accessType={}, allowedRoles={}", 
                documentId, accessType, allowedRoles);
    }

    /**
     * 删除文档
     */
    @Transactional
    public void deleteDocument(Long documentId, Long userId, String userRole) {
        Document document = getDocumentById(documentId, userId, userRole);
        
        try {
            // 删除MinIO中的文件
            minioService.deleteFile(document.getMinioObject());
            
            // 删除数据库记录
            documentMapper.deleteById(documentId);
            
            log.info("文档删除成功: documentId={}", documentId);
        } catch (Exception e) {
            log.error("文档删除失败", e);
            throw new BusinessException("文档删除失败: " + e.getMessage());
        }
    }

    /**
     * 更新文档状态
     */
    public void updateDocumentStatus(Long documentId, Integer status, String errorMessage) {
        Document document = documentMapper.selectById(documentId);
        if (document != null) {
            document.setStatus(status);
            document.setErrorMessage(errorMessage);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    /**
     * 获取文件类型
     */
    private String getFileType(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 检查是否允许的文件类型
     */
    private boolean isAllowedType(String fileType) {
        for (String type : ALLOWED_TYPES) {
            if (type.equals(fileType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成对象名
     */
    private String generateObjectName(Long userId, String originalFilename) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String extension = "";
        int lastDot = originalFilename.lastIndexOf('.');
        if (lastDot > 0) {
            extension = originalFilename.substring(lastDot);
        }
        return userId + "/" + uuid + extension;
    }
}

