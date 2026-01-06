# 智能文档问答系统

基于RAG（检索增强生成）技术的智能文档问答系统，支持多格式文档上传和精准智能问答。

## 技术栈

### 后端
- Java 8
- Spring Boot 2.7.x
- Spring Cloud Gateway（网关）
- MySQL 8.0（内存型数据库）
- Redis（缓存）
- MinIO（文件存储）
- Milvus（向量数据库）
- Elasticsearch（全文搜索引擎）

### 前端
- Vue 3
- Element Plus
- Axios

### AI引擎
- DeepSeek API（大语言模型）
- 开源Embedding模型（文本向量化）

## 系统架构

```
前端应用层 (Vue)
    ↓
网关层 (Spring Cloud Gateway)
    ↓
核心业务服务层
    ├── 文件管理服务
    ├── 文档解析服务
    ├── 对话管理服务
    └── 权限管理服务
    ↓
核心AI引擎层
    ├── 文本预处理与清洗模块
    ├── 文本分片与结构化模块
    ├── 文本向量化模块
    ├── 混合检索模块
    │   ├── Elasticsearch关键词检索
    │   ├── Milvus向量检索
    │   ├── 查询路由分析
    │   ├── 结果融合
    │   └── BGE-Reranker重排序
    └── 大模型对话生成模块
    ↓
数据存储层
    ├── MySQL（元数据）
    ├── Redis（缓存）
    ├── MinIO（文件存储）
    ├── Milvus（向量存储）
    └── Elasticsearch（关键词索引）
```

## 项目结构

```
smart-doc-qa/
├── backend/                    # 后端服务
│   ├── gateway/               # 网关服务
│   ├── file-service/         # 文件管理服务
│   ├── document-service/      # 文档解析服务
│   ├── chat-service/          # 对话管理服务
│   ├── auth-service/          # 权限管理服务
│   └── ai-engine/             # AI引擎模块
├── frontend/                  # 前端应用
└── sql/                       # 数据库脚本
```

## 快速开始

### 1. 环境准备
- JDK 8+
- Maven 3.6+
- Node.js 16+
- MySQL 8.0
- Redis 6.0+
- MinIO
- Milvus
- Elasticsearch 7.17.9（可选，建议安装）

### 2. 数据库初始化
执行 `sql/init.sql` 初始化数据库。

### 3. 配置修改
修改各服务的 `application.yml` 配置文件。

### 4. 启动服务
```bash
# 启动后端服务
cd backend
mvn clean install
# 依次启动：gateway -> file-service -> document-service -> chat-service -> auth-service

# 启动前端
cd frontend
npm install
npm run dev
```

## 核心功能

1. **多格式文档上传**：支持 doc/docx、txt、ppt/pptx、pdf
2. **智能文档解析**：自动提取文档内容并构建双重索引（向量索引+关键词索引）
3. **混合检索系统**：结合Elasticsearch关键词检索和Milvus向量检索
4. **智能查询路由**：根据查询特征自动调整检索权重
5. **BGE-Reranker重排序**：使用交叉编码器模型进行精排
6. **精准智能问答**：基于文档内容的RAG问答
7. **常识问答兜底**：无文档相关内容时使用AI通用能力
8. **多轮上下文对话**：支持连续对话
9. **回答溯源**：回答内容可追溯到原文

## 许可证

MIT License

