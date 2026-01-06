package com.smartdoc.documentservice.controller;

import com.smartdoc.common.result.Result;
import com.smartdoc.documentservice.service.DocumentParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/document/parse")
public class DocumentParseController {

    @Autowired
    private DocumentParseService documentParseService;

    /**
     * 触发文档解析
     */
    @PostMapping("/{documentId}")
    public Result<?> parseDocument(
            @PathVariable Long documentId,
            @RequestParam String fileType,
            @RequestParam String objectName) {
        documentParseService.parseDocument(documentId, fileType, objectName);
        return Result.success("文档解析任务已启动");
    }
}

