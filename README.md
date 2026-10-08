# 智汇洞察（ZhiHui DongCha）

面向企业员工的 AI 智能知识助手，按《功能设计手册》v2.0 实现：自然语言提问 → 多智能体编排（意图识别→检索规划→知识合成→质量验证）→ SSE 流式回答 + 引用溯源。

## 快速开始

```powershell
# 后端（需 JDK 17 + Maven）
cd backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"   # 必须先设，否则 mvn 用 JDK 8，报一大片假语法错误
$env:LLM_API_KEY = "sk-你自己的key"                # 仓库内不再保留默认 key，见下方说明
mvn -q -DskipTests package
& "$env:JAVA_HOME\bin\java" -Dfile.encoding=UTF-8 -jar target\zhihu-dongcha-backend.jar --server.port=8080

# 前端（需 Node 18+；本机 Node 不在 PATH 时先 $env:Path = "D:\nodejs;$env:Path"）
cd ..\frontend
npm install
npm run dev        # http://localhost:5173，/api 已代理到 8080
npm run build      # 产物在 dist\
```

`LLM_API_KEY` 不设也能起来，但会退回 mock provider：问答变本地模板，向量退化为 1024 维哈希假向量
（维度与真实向量一致，入库不报错，检索质量静默变差）。启动日志会打 ERROR，`/api/health` 的 `llm` 字段会显示 `mock`。

账号：`users` 表为空时后端只创建系统管理员 `admin@corp.com`，口令取环境变量 `ADMIN_INIT_PASSWORD`，
没配则随机生成并只在启动日志打印一次（不再有写死的演示口令，登录页也没有一键填充）。
KM_ADMIN、EMPLOYEE 由管理员在「系统设置 · 用户与权限」里创建。没有内置语料——知识库需自己建、文档需自己上传。

## 模块

- **登录认证**：JWT(8h)+刷新令牌、失败 5 次冷却 60s（SSO 回调端点已删除：它不校验 code，等于免密登录入口）
- **智能问答**：`POST /api/chat/stream` SSE（start/agent-update/token/citation/warning/done/error），会话管理，生成中可打断
- **知识库**：四档可见范围（public/internal/dept/confidential）、统计卡与覆盖率、机密库二次确认
- **文档管理**：上传→解析（Tika：pdf/docx/xlsx/txt/md）→分块→向量化，四态流转、失败重试、分块预览
- **评测中心**：golden set 打包在 jar 内（条数由 `GET /api/eval/golden-set` 如实返回），5 项质量指标 + 趋势 + 逐样本明细 + JSONL 导出
- **监控中心**：P50/P95 实时延迟（本进程环形缓冲）、请求量真实计数、LLM 成本估算（阈值为 `MONITOR_COST_THRESHOLD` 配置项）、三条真实告警规则
- **系统设置**：模型/RAG 参数热生效、用户与角色管理、审计日志

数据口径：凡是本进程测不到或分母为 0 的量一律返回 `null`（前端显示"—"），评测里模型未判定返回 `-1`（显示"未判定"），
不折算成 0% 或 100%，也不用定时器伪造进度或流式。

接口契约见 [docs/API.md](docs/API.md)，手册原文提取见 [docs/功能设计手册-提取文本.txt](docs/功能设计手册-提取文本.txt)。

## 技术栈与环境

前端：Vue 3 + TypeScript + Vite + Element Plus + Pinia + echarts。
后端：Spring Boot 3.3 (WebFlux) + jjwt + Apache Tika + Spring Data JPA + spring-data-redis。

已接入真实外部服务（配置见 `backend/src/main/resources/application.yml`，均支持 `${ENV_VAR:默认值}` 环境变量覆盖）：

| 手册设计 | 本实现（已落地） |
|---|---|
| PostgreSQL + pgvector | ✅ 真实 pgvector，`vector(1024)` + HNSW 余弦索引，`embedding <=> ?::vector` 检索；不可达时自动降级内存实现 |
| 真实 LLM（Qwen 等） | ✅ 通义千问 DashScope OpenAI 兼容接口：`qwen-plus` 对话 + `text-embedding-v3`(1024 维) 向量化 + LLM-as-Judge 评测 |
| MySQL 持久化 | ✅ Spring Data JPA，用户/知识库/文档/会话/消息/引用/评测/设置/审计全部落库，重启不丢数据 |
| Redis 缓存与限流 | ✅ spring-data-redis(Lettuce)：问答语义缓存、登录失败冷却、Token 用量统计 |
| MinIO + RabbitMQ | 本地磁盘 `backend/data/uploads` + 内存解析队列（保持替代，接口不变） |
| Micrometer/Prometheus/Grafana | 内存环形缓冲统计延迟，`/api/monitor/metrics` 直出（保持替代） |

Provider 全部采用降级(fallback)设计：LLM 在 `openai ↔ mock` 间切换，向量库在 `pgvector ↔ memory` 间切换；外部服务不可达时自动兜底，不中断问答。启动日志 `StartupHealth` 行会打印当前实际生效的依赖：`llm=openai-compatible, vectors=pgvector, redis=redis | mysql=ok | vector-store=pgvector | redis=online`。

已知妥协：MinIO/RabbitMQ、Prometheus/Grafana 仍以本地内存实现替代；Element Plus 全量引入致主包偏大。

## 部署（Docker Compose）

> 已在 2026-09-28 按此路线真实跑通（服务器 `/opt/dongcha`，两容器 healthy，浏览器经 80 访问）。

只有应用进容器，MySQL / Redis / pgvector 复用服务器上已部署好的实例：

```powershell
# 本机（PowerShell）：编译产物并打部署包（不含源码）
cd F:\moxhan\ai-project\zhi-hui-dong-cha\backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"    # 不设会用 JDK 8，报一大片假语法错误
mvn -DskipTests package
cd ..\frontend
npm ci
npm run build
cd ..
tar -czf dongcha-deploy.tar.gz docker-compose.yml .env.example backend/Dockerfile.prebuilt backend/target/zhihu-dongcha-backend.jar frontend/Dockerfile.prebuilt frontend/nginx.conf frontend/dist
scp .\dongcha-deploy.tar.gz root@127.0.0.1:/opt/    # 127.0.0.1 是占位（仓库不存真实地址）：换成你服务器的地址，否则复制到你自己的开发机
```

```bash
# 服务器：解包、填 .env、纯打包启动（不装 JDK/Node、不落源码）
mkdir -p /opt/dongcha && tar -xzf /opt/dongcha-deploy.tar.gz -C /opt/dongcha && cd /opt/dongcha
cp .env.example .env && vi .env      # 必填：LLM_API_KEY、三套服务口令、JWT_SECRET
docker compose build && docker compose up -d
curl -s http://127.0.0.1/api/health  # 期望 llm=openai-compatible, vectorStore=pgvector
```

服务器上唯一需要的构建工具就是 Docker：`Dockerfile.prebuilt` 只把本机打好的 jar / dist 塞进基础镜像。
日常发版不用打整包，前端和后端可以各更各的（`docker compose build backend && docker compose up -d backend`
只重建后端容器，前端不动），也不需要事先备份镜像——回滚就是把上一版产物重新传一遍，见第 10 节。
完整方案（每步在哪台机器执行、`.env` 逐项说明、基础镜像加速、预检查、验证清单、备份恢复、故障排查表）见 [docs/DEPLOY.md](docs/DEPLOY.md)。要点：默认接的就是开发机在连的那套库，
**线上与开发共用同一份数据**（要隔离就单独建库建账号）；`LLM_API_KEY` 必须在首次启动前填好，
否则入库的是 1024 维哈希假向量。

## 验收测试

按《功能设计手册》第六章 AC-1~AC-6 逐项判定，脚本在 `scripts/acceptance/`（纯 Node，无第三方依赖）：

```powershell
npm run accept              # AC-1~AC-5（会真实调用通义千问，产生少量费用）
npm run accept:eval         # 追加 AC-6 全量 golden set 评测（条数见 GET /api/eval/golden-set）
npm run accept -- --only=AC-3,AC-4
npm run accept -- --bootstrap   # km/emp 测试账号缺失时由 admin 现场创建（已无种子账号）
npm run accept -- --keep        # 保留测试期建的知识库/文档/会话（默认自动清理）
```

前提：后端已在 `--base`（默认 `http://localhost:8080/api`）上运行。可用 `ACCEPT_ADMIN` / `ACCEPT_KM` / `ACCEPT_EMP` 等环境变量覆盖账号。退出码 0=全部通过、1=有未达项、2=执行异常。

夹具（PDF/DOCX/XLSX）由 `scripts/acceptance/fixtures.mjs` 在内存中生成，不依赖仓库内文件。注意 PDF 夹具正文只能 ASCII——Helvetica 标准字体无 CJK 字形，中文会抽不出来。

### 当前实测状态（2026-09-26）

- **AC-1 全链路 / AC-2 引用溯源 / AC-4 权限隔离**：通过。AC-4 用差分测试（同一财务问题，管理员答出"4.82 亿元"、员工"未检索到"，并逐条校验引用所属知识库是否在可见范围内）。
- **AC-3 文档解析**：4/4 通过——6 种格式 6/6 入库且正文真实抽取、失败可重试、全库分块预览与声明块数一致。曾长期遗留的 `功能设计手册.docx`（声明 24 块 / 预览 0 块）已修复：它的向量分块是旧版粘性降级期的化石，且 `filePath` 记的是数据目录搬家前的绝对路径，`retry` 又只允许 `FAILED` 文档，三重叠加导致它无从修复；现已放宽为"失败**或向量缺失**均可重试"并在重试时纠正失效路径（docId 不变，历史引用不受影响）。另：`DELETE /documents/{id}` 现在会同时删除 `data/uploads` 下的落盘文件（此前只删元数据与向量，目录只增不减；修复前累积的 36 个无主文件仍留在目录里，需要人工确认后再清）。
- **AC-5 响应性能：未达标。** 首 Token 实测 3.8–7.2s（手册要求 <1s）；且所有 token 在 1–17ms 内一次性到达，"流式打字机"实际是末尾批量下发。`/api/monitor/metrics` 报的 p95≈865ms 不能用来判定——`MetricsWebFilter` 把登录/列表等轻量请求与问答流混在同一环形缓冲里算百分位。
- **AC-6 评测：引用完整率 70.3（≥90）、幻觉率 6.7（≤5）未达标**，检索准确率 96.7、相关度 90.8、忠实度 93.7 达标。根因：30 条中 17 条回答只标注 1 处行内引用，而**当时**的 `EvalService.citationCompleteness()` 按 `markers / min(citations,2)` 折算，单标记恒得 50 分（`17×50+12×100+1×60 = 70.3`）。历史四次运行 71.7/71.7/70.3/70.3，稳定复现。

> 2026-09-29 口径变更，以上两组数字均已作废、需重测：`citationCompleteness()` 改为 `标记数 / 答案引用的文献数`（封顶 100，不再有单标记恒 50 的折算）；
> 相关度/忠实度/幻觉率现在只汇总 LLM Judge 判定成功的样本，规则兜底样本不进汇总，一条都没判定时返回 -1（前端显示"未判定"）。
> 另外 jar 内的 20 篇种子语料已下线，golden set 中指向它的条目在没有对应真实文档时检索类指标会真实地偏低——这是去掉假数据后的应有结果，不是回归。

### 向量库降级：已可自愈

pgvector 连接被远端掐断一次，`DelegatingChunkStore.degrade()` 就会把 `availability.pg` 置 false。降级期间内存副本里没有 pg 中的存量向量，全站问答会静默退化为"未检索到相关内容"，而前端当时仍显示"全部知识库已连接"、`/documents/{id}` 的 `chunkCount` 也照常返回。更糟的是降级窗口内入库的文档只落到内存，切回 pgvector 后永远查不到——`功能设计手册.docx`（声明 24 块、分块预览 0 块）就是这类遗留。
（"前端仍显示已连接"这半句已随假数据清理失效：知识库 `status` 现在由 `ChunkStore.mode()` 推导，降级即显示 `disconnected`。）

现已三层修复：

- `pg-vector-pool` 主动保活并把连接寿命收敛到远端空闲阈值内（`keepalive-ms` < `idle-timeout-ms` < `max-lifetime-ms`），避免失效连接被直接交给请求线程；
- `PgStoreRecovery` 在降级期每 `app.pg.reprobe-interval-ms`（默认 30s）重探，探通即自动切回 pgvector，不再需要重启；启动时 PG 不可达也保留实例等自愈；
- 降级窗口内的写操作按序排队，恢复时回放到 pg，两侧不再分叉；曾发生丢弃会打 ERROR 日志。`/api/monitor/alerts` 新增"向量库未降级"规则，降级不再只在日志里。

可用 `PG_KEEPALIVE_MS` / `PG_IDLE_TIMEOUT_MS` / `PG_MAX_LIFETIME_MS` / `PG_REPROBE_MS` 调节。

仍有边界：内存副本只含本进程入库的内容，所以降级那几十秒内历史语料依然查不到——自愈缩短了窗口，但没有把内存副本做成全量镜像（那需要按语料规模权衡内存占用）。验收脚本保留 `ENV` 前置检查与收尾复查，用来区分"环境降级"与"产品缺陷"。

