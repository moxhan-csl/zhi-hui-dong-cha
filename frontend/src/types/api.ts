/* ===== 角色（API.md §0） ===== */
export type Role = 'EMPLOYEE' | 'KM_ADMIN' | 'SYS_ADMIN'

export interface UserInfo {
  id: string
  name: string
  account: string
  role: Role
  dept: string
}

export interface LoginResult {
  token: string
  refreshToken: string
  expiresAt: string
  user: UserInfo
}

/* ===== 会话 / 问答（§2） ===== */
export interface Conversation {
  id: string
  title: string
  updatedAt: string
  knowledgeBaseIds: string[]
}

export interface Citation {
  id: number
  doc: string
  docId: string
  /** 页码，1 起。PDF 逐页抽取才有值；无页边界概念的格式为 null（序列化后字段直接缺席） */
  page: number | null
  /** 文档内分块序号，从 0 开始；页码未知时用它定位 */
  chunkIndex: number
  snippet: string
  score: number
}

export interface ChatMessageRecord {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations: Citation[] | null
  createdAt: string
}

export interface ChatStreamRequest {
  conversationId: string | null
  question: string
  /** 不带历史：上下文由服务端从会话记录里取（D-11），客户端传的历史不会被采信 */
  knowledgeBaseIds: string[]
}

export type AgentStep = 'intent' | 'retrieval' | 'synthesis' | 'validation'
export type AgentStatus = 'pending' | 'running' | 'done' | 'failed' | 'skipped'

export interface AgentUpdate {
  step: AgentStep
  status: AgentStatus
  detail?: string
}

/* ===== 知识库（§3） ===== */
export type KbScope = 'public' | 'internal' | 'dept' | 'confidential'
export type KbStatus = 'syncing' | 'connected' | 'disconnected'

export interface KnowledgeBase {
  id: string
  name: string
  scope: KbScope
  members: string[]
  chunkCount: number
  vectorizedCount: number
  docCount: number
  status: KbStatus
  owner: string
}

export interface KbOverview {
  totalChunks: number
  kbCount: number
  /** 已入库文档 / 可见文档总数；null = 当前没有文档，无比率可言 */
  vectorRate: number | null
  totalDocs: number
  /** 当日 LLM+embedding token 累计（Redis），不是"缓存命中量"；无数据时为 0 */
  dailyLlmTokens: number
  vectorStore: string
  bases: KnowledgeBase[]
}

/* ===== 文档（§4） ===== */
export type DocStatus = 'PENDING' | 'PARSING' | 'READY' | 'FAILED'

export interface DocumentItem {
  id: string
  name: string
  type: string
  kbId: string
  kbName: string
  chunkCount: number
  status: DocStatus
  progress: number
  /** 当前解析阶段：queued/extracting/chunking/embedding/writing/done/failed；重启后为空 */
  stage?: string | null
  failReason: string | null
  updatedAt: string
}

export interface DocPage {
  total: number
  items: DocumentItem[]
}

export interface DocChunk {
  index: number
  text: string
  charCount: number
  /** 页码，1 起。没有页边界概念（txt/md/docx/xlsx）或 PDF 逐页抽取失败时，后端不下发该字段 */
  page?: number | null
}

/* ===== 评测（§5） ===== */
/** 运行时的检索环境快照：换了发起人、或向量库退回 memory，同一套题的分数就不是同一个测量 */
export interface EvalEnv {
  initiator?: string
  initiatorRole?: string
  /** pgvector / memory */
  vectorStore?: string
  /** openai-embedding:<model> / hash-fallback */
  embedMode?: string
  chatModel?: string
  ragTopK?: number
  ragScoreThreshold?: number
  ragQueryRewrite?: boolean
  judgeConcurrency?: number
  sampleCountRequested?: number
  degraded?: boolean
}

export interface EvalMetrics {
  retrievalPrecision: number
  /** -1 = 本次没有样本被 LLM Judge 判定（未判定，不是 0 分） */
  relevance: number
  faithfulness: number
  hallucinationRate: number
  citationCompleteness: number
  sampleCount: number
  judgedByLlm: number
  /** 检索链路失败的样本数：它们仍计入分母（拖低分数），但不出答案 */
  retrievalFailedCount?: number
  progress: number
}

export interface EvalSample {
  index: number
  /** 对应题库里的题目 id */
  questionId?: string
  question: string
  golden: string
  answer: string
  expectedDoc: string
  retrievedDocs: string[]
  scores: Record<string, number>
  /** 判定方式：模型名，rule-fallback（规则常量，不进汇总），或 retrieval-failed（链路断） */
  judge: string
  /** 本题检索失败（embedding/向量库不可用），答案为空、不参与 LLM 判定 */
  retrievalFailed?: boolean
  passed: boolean
}

export interface EvalRun {
  id: string
  createdAt: string
  status: 'running' | 'done' | 'failed'
  metrics: EvalMetrics | null
  samples?: EvalSample[]
  /** 题集指纹：同指纹的两次运行才可比，题目集合一变趋势就该断开 */
  questionSignature?: string
  questionCount?: number
  env?: EvalEnv
  failReason?: string
}

export interface EvalTrendPoint {
  createdAt: string
  metrics: EvalMetrics
  questionSignature?: string
  questionCount?: number
  env?: EvalEnv
}

/** 题库实况：一律来自 MySQL golden_questions 表，不是打包在 jar 里的写死文件 */
export interface GoldenSetInfo {
  total: number
  draft: number
  reviewed: number
  /** reviewed 且 enabled —— 真正参与评测的条数 */
  usable: number
  storage: string
  effectiveSampleCount: number
  sampleLimit: number | null
}

export interface GoldenQuestion {
  id: string
  question: string
  golden: string
  expectedDoc: string
  source: 'manual' | 'imported'
  status: 'draft' | 'reviewed'
  enabled: boolean
  note?: string
  createdBy?: string
  /** 未复核时后端省略该字段（Jackson non_null） */
  reviewedBy?: string
  createdAt: number
  updatedAt: number
  reviewedAt: number
}

export interface GoldenQuestionInput {
  question: string
  golden: string
  expectedDoc?: string
  note?: string
  enabled?: boolean
}

export interface GoldenImportResult {
  created: number
  skippedDuplicate: number
  skippedInvalid: number
  allAsDraft: boolean
}

/* ===== 监控（§6） ===== */
export interface MonitorMetrics {
  /** 无样本时后端不下发这两个字段（"未测得"），不要用 0 表示 */
  p50Ms?: number
  p95Ms?: number
  /** 延迟样本条数：0 表示本进程还没测到东西 */
  latencySamples: number
  cost: {
    /** 按配置单价估算的金额，不是账单 */
    today: number
    threshold: number
    thresholdSource: string
    tokensToday: number
    /** 服务未回 usage、按字符数估算的那部分 tokens */
    tokensEstimated: number
    amountIsEstimate: boolean
    trend: { date: string; amount: number }[]
  }
  /** 近 30 分钟逐分钟真实请求计数 */
  requestTrend: { time: string; count: number }[]
  sources: { cost: string; latency: string }
  alerts: {
    level: 'info' | 'warn' | 'critical'
    rule: string
    message: string
    active: boolean
  }[]
}

export interface HealthInfo {
  status: 'UP' | 'DEGRADED'
  /** openai-compatible = 真实模型；mock = 已回退到本地模板与哈希向量 */
  llm: 'openai-compatible' | 'mock'
  /** pgvector = PostgreSQL 向量库；memory = 进程内回退 */
  vectorStore: string
  pendingVectorOps: number
  redis: 'redis' | 'in-memory'
  users: number
  documents: number
}

/* ===== 设置（§7） */
export interface ModelsSetting {
  chat: { baseUrl: string; model: string; temperature: number }
  embedding: { model: string }
}

export interface RagSetting {
  queryRewrite: boolean
  multiQueryCount: number
  topK: number
  scoreThreshold: number
}

export interface ManagedUser {
  id: string
  name: string
  account: string
  empNo?: string
  password?: string
  role: Role
  dept: string
  enabled: boolean
}

export interface AuditLog {
  id: string
  actor: string
  action: string
  target: string
  detail: string
  at: string
}

/* ===== 错误体 ===== */
export interface ApiErrorBody {
  code: string
  message: string
  retryAfter?: number
}
