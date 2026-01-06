# 混合检索系统实现指南

## 概述

本系统实现了基于 **Elasticsearch + Milvus + BGE-Reranker** 的混合检索架构，结合关键词检索和向量检索的优势，并通过交叉编码器模型进行精排。

## 架构设计

### 1. 文档索引构建阶段

#### Elasticsearch索引
- **细粒度分块**：按段落进行分块（200-500字符）
- **字段设计**：
  - `content`: 文本内容（使用IK分词器）
  - `title`: 来源文档标题
  - `entity`: 提取的关键实体（用于增强过滤）
  - `date`: 元数据（用于按时间排序）
  - `chunk_id`: 文本块ID
  - `document_id`: 文档ID
  - `chunk_index`: 块索引

#### Milvus向量索引
- **相同粒度分块**：对相同的文本块进行向量化
- **向量存储**：存储768维向量和对应的文本块ID

### 2. 检索流程

```
用户查询
    ↓
查询路由分析（关键词主导 vs 语义意图主导）
    ↓
并行检索
    ├── Elasticsearch关键词检索
    └── Milvus向量检索
    ↓
结果融合（加权合并）
    ↓
BGE-Reranker重排序
    ↓
去重并选择Top K
    ↓
返回最终结果
```

## 核心组件

### 1. ElasticsearchService
**位置**：`backend/ai-engine/src/main/java/com/smartdoc/aiengine/service/ElasticsearchService.java`

**功能**：
- 索引创建和管理
- 批量索引文档块
- 关键词检索（支持多字段搜索）
- 按文档ID删除索引

**关键方法**：
```java
// 批量索引文档块
void indexChunks(Long documentId, String documentTitle, List<TextChunk> chunks)

// 关键词检索
List<SearchResult> keywordSearch(String query, Long documentId, int topK)
```

### 2. HybridRetrievalService
**位置**：`backend/ai-engine/src/main/java/com/smartdoc/aiengine/service/HybridRetrievalService.java`

**功能**：
- 查询路由分析
- 并行执行ES和向量检索
- 结果融合（加权合并）
- 调用Reranker重排序
- 结果去重

**关键方法**：
```java
// 混合检索
List<MilvusService.SearchResult> hybridSearch(String query, Long documentId, int topK)

// 查询类型分析
QueryType analyzeQueryType(String query)

// 结果融合
List<CandidateResult> mergeResults(...)
```

### 3. RerankerService
**位置**：`backend/ai-engine/src/main/java/com/smartdoc/aiengine/service/RerankerService.java`

**功能**：
- 调用BGE-Reranker API进行精排
- 本地重排序（回退方案）

**关键方法**：
```java
// 重排序
List<CandidateResult> rerank(List<CandidateResult> candidates, String query)
```

## 配置说明

### Elasticsearch配置

在 `application-common.yml` 中配置：

```yaml
elasticsearch:
  host: ${ELASTICSEARCH_HOST:localhost}
  port: ${ELASTICSEARCH_PORT:9200}
  index: ${ELASTICSEARCH_INDEX:document_chunks}
```

### BGE-Reranker配置

```yaml
reranker:
  enabled: ${RERANKER_ENABLED:false}
  api-url: ${RERANKER_API_URL:http://localhost:8001/rerank}
```

## 查询路由策略

系统会根据查询特征自动调整关键词检索和向量检索的权重：

| 查询特征 | 关键词权重 | 向量权重 | 说明 |
|---------|-----------|---------|------|
| 包含"如何"、"什么"等疑问词 + 长度>10 | 0.3 | 0.7 | 语义意图主导 |
| 包含专有名词/术语 + 长度<15 | 0.7 | 0.3 | 关键词主导 |
| 其他 | 0.5 | 0.5 | 平衡 |

## 使用方式

### 自动启用

系统会自动使用混合检索服务（如果可用）。在 `ChatService` 中：

```java
if (hybridRetrievalService != null) {
    // 使用混合检索：ES关键词检索 + Milvus向量检索 + BGE-Reranker重排序
    searchResults = hybridRetrievalService.hybridSearch(question, documentId, 5);
} else if (enhancedRetrievalService != null) {
    // 回退到增强检索
    searchResults = enhancedRetrievalService.enhancedSearch(question, documentId, 5);
} else {
    // 回退到基础向量检索
    searchResults = milvusService.searchSimilar(questionVector, 5, documentId);
}
```

## 部署要求

### 1. Elasticsearch

**安装**：
```bash
# Docker方式
docker run -d --name elasticsearch \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  elasticsearch:7.17.9
```

**IK分词器安装**（中文分词必需）：
```bash
# 下载IK分词器
wget https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v7.17.9/elasticsearch-analysis-ik-7.17.9.zip

# 安装到ES插件目录
unzip elasticsearch-analysis-ik-7.17.9.zip -d /path/to/elasticsearch/plugins/ik/
```

### 2. BGE-Reranker

**方式1：使用API服务**
- 部署BGE-Reranker API服务（如使用FlagEmbedding）
- 配置API URL

**方式2：本地模型**
- 可以集成本地BGE-Reranker模型
- 需要修改 `RerankerService` 的实现

## 性能优化建议

1. **ES索引优化**：
   - 使用合适的分析器（IK分词器）
   - 合理设置分片和副本数
   - 定期优化索引

2. **检索优化**：
   - 限制检索数量（Top K * 2）
   - 使用缓存（Redis）缓存常见查询结果
   - 异步处理重排序

3. **Reranker优化**：
   - 批量处理重排序请求
   - 使用GPU加速（如果可用）
   - 限制重排序的候选数量（如Top 20）

## 测试建议

1. **检索准确性测试**：
   - 对比纯向量检索 vs 混合检索的准确性
   - 测试不同查询类型的路由效果

2. **性能测试**：
   - 测试检索延迟
   - 测试并发性能

3. **A/B测试**：
   - 对比不同权重配置的效果
   - 测试Reranker对最终结果的影响

## 故障处理

### Elasticsearch不可用
- 系统会自动回退到增强检索或基础向量检索
- 不会影响系统正常运行

### BGE-Reranker不可用
- 系统会使用综合分数进行排序
- 仍能正常工作，但排序精度可能降低

## 进一步优化方向

1. **NER实体提取**：使用专业NER模型（如HanLP）提取实体
2. **查询理解**：使用NLP技术进行更精确的查询意图识别
3. **动态权重调整**：基于历史查询效果动态调整权重
4. **多模态检索**：支持图片、表格等多模态内容检索

