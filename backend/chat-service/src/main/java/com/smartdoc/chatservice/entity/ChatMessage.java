package com.smartdoc.chatservice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartdoc.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {
    private Long userId;
    private Long documentId; // 可为空，表示通用对话
    private String question;
    private String answer;
    private String sourceChunks; // JSON格式，存储来源文本块ID
    private Integer isGeneralAnswer; // 0-文档回答, 1-通用回答
}

