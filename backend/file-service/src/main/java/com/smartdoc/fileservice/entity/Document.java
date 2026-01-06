package com.smartdoc.fileservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartdoc.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document")
public class Document extends BaseEntity {
    private String fileName;
    private String fileType; // doc, docx, txt, ppt, pptx, pdf
    private Long fileSize;
    private String filePath; // MinIO中的路径
    private String minioBucket;
    private String minioObject;
    private Long userId;
    private Integer status; // 0-上传中, 1-解析中, 2-解析完成, 3-解析失败
    private String errorMessage;
    private String accessType; // public-公开, private-私有, role-角色限制
    private String allowedRoles; // JSON格式的角色列表，如 ["admin", "user"]
}

