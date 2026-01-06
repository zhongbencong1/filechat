package com.smartdoc.documentservice.service;

import com.smartdoc.aiengine.service.EmbeddingService;
import com.smartdoc.aiengine.service.MilvusService;
import com.smartdoc.aiengine.service.TextPreprocessService;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * 文档解析服务
 * 支持 doc/docx, txt, ppt/pptx, pdf 格式
 */
@Slf4j
@Service
public class DocumentParseService {

    @Autowired
    private MinioService minioService;

    @Autowired
    private TextPreprocessService textPreprocessService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    @Value("${minio.access-key:minioadmin}")
    private String minioAccessKey;

    @Value("${minio.secret-key:minioadmin}")
    private String minioSecretKey;

    @Value("${minio.bucket:documents}")
    private String minioBucket;

    private MinioClient minioClient;

    @Autowired
    public void initMinioClient() {
        minioClient = MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
    }

    /**
     * 异步解析文档
     */
    @Async
    public void parseDocument(Long documentId, String fileType, String objectName) {
        log.info("开始解析文档: documentId={}, fileType={}", documentId, fileType);
        
        try {
            // 从MinIO下载文件
            InputStream fileStream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectName)
                    .build());

            // 根据文件类型解析
            String rawText = "";
            switch (fileType.toLowerCase()) {
                case "txt":
                    rawText = parseTxt(fileStream);
                    break;
                case "doc":
                    rawText = parseDoc(fileStream);
                    break;
                case "docx":
                    rawText = parseDocx(fileStream);
                    break;
                case "ppt":
                    rawText = parsePpt(fileStream);
                    break;
                case "pptx":
                    rawText = parsePptx(fileStream);
                    break;
                case "pdf":
                    rawText = parsePdf(fileStream);
                    break;
                default:
                    throw new IllegalArgumentException("不支持的文件格式: " + fileType);
            }

            fileStream.close();

            // 文本清洗
            String cleanedText = textPreprocessService.cleanText(rawText);
            if (cleanedText.trim().isEmpty()) {
                throw new RuntimeException("文档内容为空");
            }

            // 智能分片
            List<TextPreprocessService.TextChunk> chunks = textPreprocessService.splitText(cleanedText, documentId);
            if (chunks.isEmpty()) {
                throw new RuntimeException("文档分片失败");
            }

            // 提取文本块内容
            List<String> chunkContents = chunks.stream()
                    .map(TextPreprocessService.TextChunk::getContent)
                    .collect(Collectors.toList());
            List<String> chunkIds = chunks.stream()
                    .map(TextPreprocessService.TextChunk::getChunkId)
                    .collect(Collectors.toList());

            // 向量化
            List<List<Float>> vectors = embeddingService.embedTexts(chunkContents);

            // 存入Milvus
            milvusService.insertVectors(documentId, chunkIds, chunkContents, vectors);

            log.info("文档解析完成: documentId={}, 分片数={}", documentId, chunks.size());
            
            log.info("文档解析成功: documentId={}", documentId);
            
        } catch (Exception e) {
            log.error("文档解析失败: documentId={}", documentId, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析TXT文件
     */
    private String parseTxt(InputStream inputStream) throws Exception {
        Scanner scanner = new Scanner(inputStream, "UTF-8");
        StringBuilder text = new StringBuilder();
        while (scanner.hasNextLine()) {
            text.append(scanner.nextLine()).append("\n");
        }
        scanner.close();
        return text.toString();
    }

    /**
     * 解析DOC文件
     */
    private String parseDoc(InputStream inputStream) throws Exception {
        HWPFDocument document = new HWPFDocument(inputStream);
        WordExtractor extractor = new WordExtractor(document);
        String text = extractor.getText();
        extractor.close();
        document.close();
        return text;
    }

    /**
     * 解析DOCX文件
     */
    private String parseDocx(InputStream inputStream) throws Exception {
        XWPFDocument document = new XWPFDocument(inputStream);
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            text.append(paragraph.getText()).append("\n");
        }
        document.close();
        return text.toString();
    }

    /**
     * 解析PPT文件
     */
    private String parsePpt(InputStream inputStream) throws Exception {
        HSLFSlideShow slideShow = new HSLFSlideShow(inputStream);
        StringBuilder text = new StringBuilder();
        for (HSLFSlide slide : slideShow.getSlides()) {
            for (org.apache.poi.hslf.usermodel.HSLFShape shape : slide.getShapes()) {
                if (shape instanceof HSLFTextShape) {
                    HSLFTextShape textShape = (HSLFTextShape) shape;
                    text.append(textShape.getText()).append("\n");
                }
            }
        }
        slideShow.close();
        return text.toString();
    }

    /**
     * 解析PPTX文件
     */
    private String parsePptx(InputStream inputStream) throws Exception {
        XMLSlideShow slideShow = new XMLSlideShow(inputStream);
        StringBuilder text = new StringBuilder();
        for (XSLFSlide slide : slideShow.getSlides()) {
            for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                if (shape instanceof XSLFTextShape) {
                    XSLFTextShape textShape = (XSLFTextShape) shape;
                    text.append(textShape.getText()).append("\n");
                }
            }
        }
        slideShow.close();
        return text.toString();
    }

    /**
     * 解析PDF文件
     */
    private String parsePdf(InputStream inputStream) throws Exception {
        PDDocument document = PDDocument.load(inputStream);
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(document);
        document.close();
        return text;
    }
}

