package com.smartdoc.fileservice.controller;

import com.smartdoc.common.result.Result;
import com.smartdoc.fileservice.entity.Document;
import com.smartdoc.fileservice.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/file")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    /**
     * 上传文档
     */
    @PostMapping("/upload")
    public Result<Document> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") Long userId) {
        Document document = documentService.uploadDocument(file, userId);
        return Result.success(document);
    }

    /**
     * 获取文档列表
     */
    @GetMapping("/list")
    public Result<List<Document>> getDocumentList(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "user") String userRole) {
        List<Document> documents = documentService.getDocumentList(userId, userRole);
        return Result.success(documents);
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{documentId}")
    public Result<Document> getDocument(
            @PathVariable Long documentId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "user") String userRole) {
        Document document = documentService.getDocumentById(documentId, userId, userRole);
        return Result.success(document);
    }

    /**
     * 设置文档权限
     */
    @PutMapping("/{documentId}/permission")
    public Result<?> setDocumentPermission(
            @PathVariable Long documentId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody Map<String, Object> request) {
        String accessType = (String) request.get("accessType");
        @SuppressWarnings("unchecked")
        List<String> allowedRoles = (List<String>) request.get("allowedRoles");
        
        documentService.setDocumentPermission(documentId, userId, accessType, allowedRoles);
        return Result.success();
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{documentId}")
    public Result<?> deleteDocument(
            @PathVariable Long documentId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "user") String userRole) {
        documentService.deleteDocument(documentId, userId, userRole);
        return Result.success();
    }
}

