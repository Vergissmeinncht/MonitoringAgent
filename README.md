# MonitoringAgent

MonitoringAgent 是一个基于 Java 17、Spring Boot、Spring AI Alibaba、Milvus、Lucene 和 MCP 构建的智能运维助手。它能够接收运维文档并建立索引，通过混合 RAG 检索知识，结合 Prometheus 指标与腾讯云 CLS 日志分析告警，并由多个 Agent 协作生成排障报告。

## 核心能力

- 多格式文档入库：支持 `txt`、`md`、`markdown`、`pdf`、`doc`、`docx`。
- 混合 RAG：并行执行 Milvus 向量检索和 Lucene BM25 检索，再使用 `gte-rerank-v2` 重排。
- 智能诊断：识别错误码、异常、组件、版本和环境信息，为回答加入诊断反思约束。
- 对话与工具调用：基于 `ReactAgent` 调用内部文档、Prometheus、日志和时间等工具。
- AIOps 多 Agent 协作：Supervisor 协调 Planner 与 Executor，完成规划、执行和再规划。
- 会话记忆：MongoDB 持久化会话，自动压缩长上下文，并维护用户级长期记忆。
- RAG 评测：支持测试集生成、批量评测以及 JSON/CSV 导出。
- Web 页面：内置聊天页面和 RAG 评测页面，无需单独部署前端。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2.6、Spring AI 1.1.0 |
| Agent | Spring AI Alibaba 1.1.0.0-RC2、DashScope |
| 向量检索 | Milvus 2.5、`text-embedding-v4` |
| 关键词检索 | Apache Lucene 9.11.1、BM25 |
| 重排与生成 | `gte-rerank-v2`、`qwen3-max` |
| 持久化 | MongoDB 7 |
| 文档解析 | Apache Tika 2.9.2 |
| 工具协议 | MCP、SSE |
| 基础设施 | Docker Compose、etcd、MinIO、Attu |

## 工作流程

```text
文档上传
  -> Tika/Markdown/Text 解析
  -> 标题与段落切片
  -> DashScope Embedding
  -> Milvus 向量索引 + Lucene BM25 索引

用户提问
  -> 诊断信息解析
  -> 向量召回 + BM25 召回
  -> 去重与 ReRank
  -> Agent 工具调用 / RAG Prompt
  -> 流式生成回答或 AIOps 报告
```

## 项目结构

```text
MonitoringAgent/
├─ aiops-docs/                         # 示例运维知识文档
├─ src/main/java/com/example/monitoringagent/
│  ├─ agent/                           # Supervisor、Planner、Executor 与工具
│  ├─ client/                          # Milvus 客户端
│  ├─ config/                          # 应用配置属性
│  ├─ controller/                      # REST/SSE 接口
│  ├─ memory/                          # 会话持久化与长期记忆
│  ├─ rag/                             # 混合检索、诊断解析与重排
│  ├─ service/                         # 业务编排与文档处理
│  └─ MonitoringAgentApplication.java  # 应用入口
├─ src/main/resources/static/          # 聊天与 RAG 评测页面
├─ src/test/                           # 单元测试和集成测试
├─ vector-database.yml                 # Milvus、MongoDB 等依赖
├─ pom.xml
└─ mvnw / mvnw.cmd
```

## 环境要求

- JDK 17
- Docker Desktop 与 Docker Compose
- 可用的 DashScope API Key
- 如需真实 CLS 日志查询：Node.js 及可用的 MCP SSE 服务

## 快速开始

### 1. 配置环境变量

PowerShell 当前会话：

```powershell
$env:DASH_SCOPE_API_KEY = "你的 DashScope API Key"
$env:MONGODB_URI = "mongodb://localhost:27017/monitoring_agent"
```

`MONGODB_URI` 可省略，以上地址是默认值。不要把真实密钥写入 `application.yml`、README 或提交到 Git。

### 2. 启动基础设施

```powershell
docker-compose -f vector-database.yml up -d
```

默认服务地址：

| 服务 | 地址 |
| --- | --- |
| Milvus | `localhost:19530` |
| MongoDB | `localhost:27017` |
| Attu | `http://localhost:8000` |
| MinIO API | `http://localhost:9000` |
| MinIO Console | `http://localhost:9001` |

### 3. 启动应用

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
./mvnw spring-boot:run
```

应用默认监听 `http://localhost:9900`。

### 4. 访问页面

- 聊天页面：`http://localhost:9900/`
- RAG 评测：`http://localhost:9900/rag-test.html`
- Attu 管理页面：`http://localhost:8000`

### 5. 上传知识文档

```powershell
curl.exe -X POST "http://localhost:9900/api/upload" `
  -H "Accept: application/json" `
  -F "file=@aiops-docs/cpu_high_usage.md"
```

同名文件再次上传时，系统会删除该来源的旧 Milvus/BM25 记录并重新建立索引。

## MCP 日志工具

项目当前使用的 MCP SSE 地址为：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        sse:
          connections:
            tencent-cls:
              url: http://localhost:3300
              sse-endpoint: /sse
```

启动 Spring Boot 前，应确保对应 SSE 服务已监听 `3300` 端口。如果只使用 CLS Mock，可按实际运行方式关闭 MCP Client；`cls.mock-enabled` 控制项目内日志工具的 Mock 行为。

## 主要 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/upload` | 上传并索引文档，参数名为 `file` |
| `POST` | `/api/chat` | 普通 Agent 对话 |
| `POST` | `/api/chat_stream` | SSE 流式 Agent 对话 |
| `POST` | `/api/ai_ops` | SSE 流式 AIOps 多 Agent 分析 |
| `POST` | `/api/chat/clear` | 清空指定会话 |
| `GET` | `/api/chat/session/{sessionId}` | 查询会话状态 |
| `GET` | `/api/memory/users/{userId}` | 查询用户长期记忆 |
| `DELETE` | `/api/memory/users/{userId}/{memoryId}` | 删除一条长期记忆 |
| `DELETE` | `/api/memory/users/{userId}` | 清空用户长期记忆 |
| `POST` | `/api/rag/bm25/rebuild` | 重建 BM25 索引 |
| `POST` | `/api/rag-test/generate` | 生成 RAG 测试集 |
| `POST` | `/api/rag-test/run` | 执行 RAG 评测 |

对话请求示例：

```json
{
  "id": "session-001",
  "userId": "user-001",
  "question": "生产环境 CPU 使用率持续超过 90%，应该如何排查？"
}
```

## 常用配置

配置文件位于 `src/main/resources/application.yml`。

| 配置项 | 作用 | 默认值 |
| --- | --- | --- |
| `server.port` | 应用端口 | `9900` |
| `document.chunk.max-size` | 文档切片最大长度 | `800` |
| `document.chunk.overlap` | 切片重叠长度 | `100` |
| `rag.hybrid.vector-top-k` | 向量召回数量 | `10` |
| `rag.hybrid.bm25-top-k` | BM25 召回数量 | `10` |
| `rag.rerank.top-n` | 重排后保留数量 | `3` |
| `rag.bm25.index-path` | BM25 本地索引目录 | `./volumes/bm25-index` |
| `prometheus.mock-enabled` | Prometheus Mock 开关 | `false` |
| `cls.mock-enabled` | CLS Mock 开关 | `true` |
| `memory.persistence.enabled` | 会话持久化开关 | `true` |
| `memory.long-term.enabled` | 长期记忆开关 | `true` |

## 测试

运行全部测试：

```powershell
.\mvnw.cmd test
```

只运行长期记忆单元测试：

```powershell
.\mvnw.cmd -Dtest=LongTermMemoryServiceTest test
```

`MongoConversationStoreTest` 需要本地 MongoDB；`MonitoringAgentApplicationTests` 加载完整 Spring Context，需要 Milvus、MongoDB 以及相关配置可用。建议先启动 `vector-database.yml` 中的容器再运行完整测试。

## 停止服务

停止 Spring Boot 后执行：

```powershell
docker-compose -f vector-database.yml down
```

## 注意事项

- Makefile 当前使用不存在的 `/milvus/health` 做健康检查，可能误判服务状态；请以 Spring Boot 日志和实际端口为准。
- `/api/chat/session/{sessionId}` 是会话查询接口，不是无状态健康检查。
- Milvus 的 `biz` 与 `memory` Collection 使用 1024 维向量、COSINE 距离和 HNSW 索引。
- 文档入库以来源路径和切片索引生成稳定 ID，修改切片规则后应重建索引。
- BM25 写入失败不会回滚已经成功写入的 Milvus 数据，请关注应用日志。

## License

当前仓库尚未声明开源许可证。如需公开分发或允许他人复用，请补充合适的 `LICENSE` 文件。
