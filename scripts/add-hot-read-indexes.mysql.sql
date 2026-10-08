-- ============================================================================
-- 给热点读补索引（第九批 D-2）：12 条 CREATE INDEX
--
-- 在哪执行：**服务器上**，SSH 登录后 cd /opt/dongcha（compose 与 .env 所在目录）。
--   本机 Windows 跑不了：MySQL 只监听宿主机，mysql 客户端也在服务器侧。
--   线上主机地址不在仓库里（2026-10-08 起仓库只写 127.0.0.1 占位），用你自己的那台。
--
-- 为什么建议手动跑这一段、而不是让应用启动时自己建：
--   后端是 spring.jpa.hibernate.ddl-auto=update，实体上声明的索引会在新 jar 第一次启动时
--   自动对**共享生产库**执行。表接近空的时候两种做法都一样快，但"启动那一刻顺手改了库结构"
--   和"发版前你自己看过这 12 条语句、点头之后再改"是两种可控程度。
--   先跑本文件，启动时 Hibernate 按**索引名**检查存在性（与下面同名），就会跳过、不会重复建。
--
-- 执行命令（在 /opt/dongcha 下，自己从 .env 取账号，不用手输口令）：
--   mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
--     zhihu_dongcha < scripts/add-hot-read-indexes.mysql.sql
--
-- 填错会怎样：
--   * 报 ERROR 1045 Access denied / 1049 Unknown database —— .env 里的账号或库名不对，
--     此时一条 DDL 都没执行，改正再跑；
--   * 报 ERROR 1061 Duplicate key name —— 该索引已经建过了（重复跑本文件必现），
--     忽略它继续下一条即可，不是失败；
--   * 报 ERROR 1170 BLOB/TEXT column used in key specification without a key length ——
--     说明有人把列名改成了 TEXT 列：本文件只碰 VARCHAR/BIGINT 列，出现这条就是抄错了行；
--   * 报 ERROR 1146 Table doesn't exist —— 库还没被应用建过表（新环境首次启动会自己建，
--     那就别跑本文件，让启动自己来）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第 1 段：执行前核对（只读）。先看清"表多空、有没有同名索引"
-- ---------------------------------------------------------------------------
SELECT table_name, table_rows FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('documents','conversations','messages','citations',
                     'audit_logs','eval_runs','golden_questions');

SELECT table_name, index_name, column_name, seq_in_index FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name LIKE 'idx_%'
 ORDER BY table_name, index_name, seq_in_index;

-- ---------------------------------------------------------------------------
-- 第 2 段：建索引。每条后面的注释是它服务的查询（都来自代码里真实存在的读路，不是"以防万一"）
-- ---------------------------------------------------------------------------

-- documents：DocumentRepository.findByKbId / existsByKbId（库内文档清单、删库前的存在性检查）、
--             findByStatusIn（启动对账"上次停在解析中的文档"）、namesInKb（上传同名判重）
CREATE INDEX idx_documents_kb_id ON documents (kb_id);
CREATE INDEX idx_documents_status ON documents (status);
-- DocumentRepository.findAllByOrderByUpdatedAtDesc：GET /api/documents 每请求直读
CREATE INDEX idx_documents_updated_at ON documents (updated_at);

-- conversations：findByUserIdOrderByUpdatedAtDesc（会话列表＝按人筛 + 按更新时间倒序），
--                单列索引只能满足一半，所以是复合
CREATE INDEX idx_conversations_user_updated ON conversations (user_id, updated_at);

-- messages：conversation_id 是 ConversationEntity.messages 关联贡献的外键列（实体里没有这个字段），
--           每次载入会话都按 order by ord 回表取消息
CREATE INDEX idx_messages_conversation_ord ON messages (conversation_id, ord);

-- citations：message_id 同上（MessageEntity.citations 的外键列），引用卡片随消息 EAGER 载入并按 idx 排序
CREATE INDEX idx_citations_message_idx ON citations (message_id, idx);

-- audit_logs：全项目唯一只追加、无保留期的表。D-30 删掉内存镜像后，
--             order by at_time desc limit n 成了唯一读路（GET /api/settings/audit-logs）
CREATE INDEX idx_audit_logs_at ON audit_logs (at_time);

-- eval_runs：findAllByOrderByCreatedAtDesc 与 runHeadsDesc 都按 created_at 倒序；
--            启动对账 findByStatusOrderByCreatedAtDesc('running') 需要 (status, created_at)
CREATE INDEX idx_eval_runs_created ON eval_runs (created_at);
CREATE INDEX idx_eval_runs_status_created ON eval_runs (status, created_at);

-- golden_questions：列表按 updated_at / created_at 排序，"只列已复核题"按 status 筛后再倒序
CREATE INDEX idx_golden_questions_updated ON golden_questions (updated_at);
CREATE INDEX idx_golden_questions_created ON golden_questions (created_at);
CREATE INDEX idx_golden_questions_status_updated ON golden_questions (status, updated_at);

-- 刻意没有的三条，别"顺手补上"：
--   * knowledge_bases.created_at：行数由管理员控制（几十量级），唯一读路是 findAll 全量取，
--     全量扫小表时优化器本来就不会用二级索引，加了只多付一次写成本；
--   * users 的任何新索引：登录查的是 findByAccountIgnoreCase / findByEmpNoIgnoreCase，
--     生成的是 lower(列)=lower(?)，函数包住列之后索引就用不上了；users 是全站最小的表；
--   * kb_members.kb_id 与 conversation_kb_ids.conversation_id：它们是集合表的外键列，
--     InnoDB 建外键约束时会自动为外键列建索引（第 3 段就是去核对这件事），
--     再按名字声明一份只会多出一个同列重复索引。
-- 同样刻意没有 UNIQUE 约束：这套库是共享生产实例，存量行里可能已经有重名/撞号，
-- 约束一上去写入就直接失败。业务唯一性改由写入侧代码拒绝（见设计文档 §4.7、§5.3、§5.4）。

-- ---------------------------------------------------------------------------
-- 第 3 段：执行后核对（只读）。期望结果：
--   上面 12 个 idx_ 名字全在；kb_members 与 conversation_kb_ids 各有一个非 PRIMARY 的索引
--   （名字通常是 FK 约束名，不是 idx_ 开头，所以不会出现在第 1 段的 LIKE 'idx_%' 里）。
-- ---------------------------------------------------------------------------
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name IN ('documents','conversations','messages','citations','audit_logs',
                      'eval_runs','golden_questions','kb_members','conversation_kb_ids')
 GROUP BY table_name, index_name
 ORDER BY table_name, index_name;
