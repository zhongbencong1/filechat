package com.smartdoc.aiengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本预处理与清洗服务
 */
@Slf4j
@Service
public class TextPreprocessService {

    // 去除特殊符号的正则
    private static final Pattern SPECIAL_CHARS = Pattern.compile("[#=*]{3,}|-{3,}|_{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{3,}");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    /**
     * 文本清洗：去重、去空行、去特殊符号、合并短句
     */
    public String cleanText(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return "";
        }

        String cleaned = rawText;
        // 去除页眉页脚常见标记
        cleaned = cleaned.replaceAll("第\\s*\\d+\\s*页", "");
        cleaned = cleaned.replaceAll("共\\s*\\d+\\s*页", "");
        
        // 去除特殊符号
        cleaned = SPECIAL_CHARS.matcher(cleaned).replaceAll("");
        
        // 合并多个空格
        cleaned = MULTI_SPACE.matcher(cleaned).replaceAll(" ");
        
        // 合并多个换行
        cleaned = MULTI_NEWLINE.matcher(cleaned).replaceAll("\n\n");
        
        // 去除首尾空白
        cleaned = cleaned.trim();
        
        return cleaned;
    }

    /**
     * 智能分片：将长文本切分为语义完整的小文本块
     * 分片大小：200~500个字符/块（中文）
     * 分片原则：按段落/章节切分，不割裂语义
     * 改进：添加重叠分块，避免边界信息丢失
     */
    public List<TextChunk> splitText(String text, Long documentId) {
        return splitText(text, documentId, 200, 500, 50);
    }

    /**
     * 智能分片（带参数）
     * @param text 文本内容
     * @param documentId 文档ID
     * @param minChunkSize 最小分块大小
     * @param maxChunkSize 最大分块大小
     * @param overlapSize 重叠大小（用于避免边界信息丢失）
     */
    public List<TextChunk> splitText(String text, Long documentId, 
                                     int minChunkSize, int maxChunkSize, int overlapSize) {
        List<TextChunk> chunks = new ArrayList<>();
        
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        // 按段落分割（双换行符）
        String[] paragraphs = text.split("\\n\\n+");
        
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 1;
        String lastChunkEnd = ""; // 用于重叠
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            
            // 如果当前块加上新段落不超过maxChunkSize，则合并
            if (currentChunk.length() + paragraph.length() <= maxChunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n");
                }
                currentChunk.append(paragraph);
            } else {
                // 保存当前块
                if (currentChunk.length() >= minChunkSize) {
                    String chunkContent = currentChunk.toString();
                    chunks.add(new TextChunk(
                        documentId,
                        chunkIndex++,
                        chunkContent
                    ));
                    
                    // 保存当前块的末尾部分用于重叠
                    if (chunkContent.length() > overlapSize) {
                        lastChunkEnd = chunkContent.substring(
                                chunkContent.length() - overlapSize);
                    } else {
                        lastChunkEnd = chunkContent;
                    }
                    
                    // 新块开始时，添加重叠内容
                    currentChunk = new StringBuilder(lastChunkEnd);
                    if (currentChunk.length() > 0) {
                        currentChunk.append("\n");
                    }
                    currentChunk.append(paragraph);
                } else {
                    // 当前块太小，强制合并（即使超过maxChunkSize）
                    if (currentChunk.length() > 0) {
                        currentChunk.append("\n");
                    }
                    currentChunk.append(paragraph);
                }
            }
            
            // 如果单个段落就超过maxChunkSize，按句号分割
            if (currentChunk.length() > maxChunkSize * 1.5) {
                String[] sentences = currentChunk.toString().split("[。！？]");
                StringBuilder sentenceChunk = new StringBuilder();
                String sentenceOverlap = "";
                
                for (String sentence : sentences) {
                    sentence = sentence.trim();
                    if (sentence.isEmpty()) continue;
                    
                    if (sentenceChunk.length() + sentence.length() <= maxChunkSize) {
                        if (sentenceChunk.length() > 0) {
                            sentenceChunk.append("。");
                        }
                        sentenceChunk.append(sentence);
                    } else {
                        if (sentenceChunk.length() >= minChunkSize) {
                            String chunkContent = sentenceChunk.toString();
                            chunks.add(new TextChunk(
                                documentId,
                                chunkIndex++,
                                chunkContent
                            ));
                            
                            // 保存重叠内容
                            if (chunkContent.length() > overlapSize) {
                                sentenceOverlap = chunkContent.substring(
                                        chunkContent.length() - overlapSize);
                            } else {
                                sentenceOverlap = chunkContent;
                            }
                        }
                        sentenceChunk = new StringBuilder(sentenceOverlap);
                        if (sentenceChunk.length() > 0) {
                            sentenceChunk.append("。");
                        }
                        sentenceChunk.append(sentence);
                    }
                }
                if (sentenceChunk.length() >= minChunkSize) {
                    currentChunk = sentenceChunk;
                } else {
                    currentChunk = new StringBuilder();
                }
            }
        }
        
        // 保存最后一个块
        if (currentChunk.length() >= minChunkSize) {
            chunks.add(new TextChunk(
                documentId,
                chunkIndex,
                currentChunk.toString()
            ));
        }
        
        log.info("文档 {} 分片完成，共 {} 个文本块（重叠大小: {}）", 
                documentId, chunks.size(), overlapSize);
        return chunks;
    }

    /**
     * 文本块实体
     */
    public static class TextChunk {
        private Long documentId;
        private Integer chunkIndex;
        private String content;
        private String chunkId; // 格式：docId_chunkIndex

        public TextChunk(Long documentId, Integer chunkIndex, String content) {
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
            this.content = content;
            this.chunkId = documentId + "_" + chunkIndex;
        }

        public Long getDocumentId() {
            return documentId;
        }

        public Integer getChunkIndex() {
            return chunkIndex;
        }

        public String getContent() {
            return content;
        }

        public String getChunkId() {
            return chunkId;
        }
    }
}

