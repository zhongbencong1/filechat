# 分层上下文管理架构指南

## 概述

为了解决大模型在处理过长上下文时出现的"前言不搭后语"问题，系统实现了分层、动态的上下文管理架构，包含三层记忆机制。

## 架构设计

### 第一层：短期记忆（对话流连贯性）

**实现**：`ShortTermMemoryService`

**特点**：
- 使用滑动窗口，只保留最近3-5轮对话
- 存储在Redis中，过期时间24小时
- 保证模型理解最近的对话流程和指代关系

**作用**：
- 确保对话自然连贯
- 支持指代关系理解（如"上面说的那个问题"）
- 避免上下文过长导致的注意力分散

### 第二层：长期记忆（用户与历史相关性问题）

**实现**：`LongTermMemoryService`

**特点**：
- 使用向量数据库（Milvus）检索历史对话
- 当用户提问时，将查询转换为向量
- 检索出语义最相关的历史对话片段

**作用**：
- 像客服查阅历史工单一样，快速检索相关历史
- 检索出的信息作为参考插入Prompt，而不是传递全部历史
- 避免位置编码失效问题

### 第三层：关键信息提取（状态与效率）

**实现**：`KeyInfoExtractionService`

**特点**：
- 实时提取并结构化存储关键实体信息
- 使用正则表达式和模式匹配提取
- 存储在Redis中，过期时间30天

**提取的关键信息**：
- 订单号（order_id）
- 手机号（phone）
- 邮箱（email）
- 问题分类（category）
- 状态（status）
- 金额（amount）
- 日期（date）
- 用户意图（intent）

**作用**：
- 自动提取并跟踪关键信息
- 结构化信息单独作为系统状态
- 极大减少需要模型"记住"的杂乱信息

## 核心服务

### LayeredContextService

**功能**：整合三层记忆，构建分层上下文

**主要方法**：
```java
// 构建分层上下文
LayeredContext buildContext(Long userId, Long documentId, String currentQuestion)

// 保存对话到各层记忆
void saveConversation(Long userId, Long documentId, String question, String answer)

// 转换为LLM可用的消息列表
List<Map<String, String>> convertToMessages(LayeredContext context, String currentQuestion)
```

## 工作流程

### 1. 用户提问时

```
用户提问
    ↓
构建分层上下文（LayeredContextService.buildContext）
    ├── 第一层：获取短期记忆（最近3-5轮对话）
    ├── 第二层：检索长期记忆（向量检索相关历史）
    └── 第三层：获取关键信息（结构化信息）
    ↓
转换为LLM消息列表
    ├── 系统提示（包含关键信息）
    ├── 长期记忆上下文（相关历史对话）
    ├── 短期记忆（最近对话）
    └── 当前问题
    ↓
调用LLM生成回答
```

### 2. 保存对话时

```
对话完成
    ↓
保存到各层记忆
    ├── 第一层：添加到短期记忆（滑动窗口）
    ├── 第二层：保存到长期记忆（向量化存储）
    └── 第三层：提取关键信息（结构化存储）
```

## 配置说明

在 `application-common.yml` 中配置：

```yaml
context:
  # 短期记忆配置
  short-term:
    window-size: 5  # 滑动窗口大小（轮数）
  
  # 长期记忆配置
  long-term:
    enabled: true
    collection: chat_history_vectors  # Milvus集合名
    top-k: 3  # 检索Top K条历史对话
  
  # 关键信息配置
  key-info:
    enabled: true
```

## 优势

### 1. 解决上下文过长问题
- **短期记忆**：只保留最近对话，避免上下文过长
- **长期记忆**：按需检索，不传递全部历史
- **关键信息**：结构化存储，减少冗余信息

### 2. 提高回答质量
- **连贯性**：短期记忆保证对话连贯
- **相关性**：长期记忆提供相关历史参考
- **一致性**：关键信息确保信息一致性

### 3. 提升效率
- **减少Token消耗**：只传递必要的上下文
- **提高响应速度**：减少需要处理的信息量
- **降低成本**：减少API调用成本

## 使用方式

### 自动启用

系统会自动使用分层上下文管理（如果可用）。在 `ChatService` 中：

```java
if (layeredContextService != null) {
    // 使用分层上下文管理
    LayeredContext context = layeredContextService.buildContext(userId, documentId, question);
    List<Map<String, String>> messages = layeredContextService.convertToMessages(context, question);
} else {
    // 回退到传统方式
    List<LLMService.ChatMessage> chatHistory = getChatHistory(userId, documentId);
}
```

### 手动控制

可以通过配置项控制各层的启用状态：

```yaml
context:
  long-term:
    enabled: false  # 禁用长期记忆
  key-info:
    enabled: false  # 禁用关键信息提取
```

## 扩展建议

### 1. 改进关键信息提取
- 使用专业NER模型（如HanLP、spaCy）
- 支持自定义实体类型
- 提高提取准确率

### 2. 优化长期记忆
- 创建专门的对话历史集合
- 支持更复杂的过滤条件
- 实现对话总结和压缩

### 3. 增强短期记忆
- 动态调整窗口大小
- 基于重要性选择保留的对话
- 实现对话压缩

### 4. 添加记忆管理
- 定期清理过期记忆
- 实现记忆重要性评分
- 支持用户手动管理记忆

## 注意事项

1. **Redis依赖**：短期记忆和关键信息需要Redis支持
2. **Milvus依赖**：长期记忆需要Milvus支持
3. **性能考虑**：长期记忆检索会增加延迟，建议异步处理
4. **存储成本**：长期记忆会占用Milvus存储空间

## 故障处理

### Redis不可用
- 短期记忆和关键信息功能不可用
- 系统回退到传统对话历史管理

### Milvus不可用
- 长期记忆功能不可用
- 系统只使用短期记忆和关键信息

### 服务不可用
- 系统自动回退到传统方式
- 不影响基本问答功能

