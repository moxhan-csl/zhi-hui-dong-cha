-- ============================================================================
-- 【2026-09-29 已作废，仅供参考】线上演示数据最终走接口删的（DELETE /api/documents/{id}
--   逐篇 + DELETE /api/knowledge-bases/{id}），本文件里的写操作一段都没有执行过。
--   删后 /api/health 报 documents:0，四个库不再出现在 /api/knowledge-bases。
--   留着的价值：第 1 段只读核对语句、第 2 段备份命令，今后要清库仍可复用。
-- ----------------------------------------------------------------------------
-- 清理 MySQL 业务库里的种子语料元数据与编造页码
-- 在哪执行：线上那台服务器上（真实地址不写进本仓库，在 /opt/dongcha/.env 里），用 .env 里的 MYSQL_USER/MYSQL_PASSWORD 连 zhihu_dongcha：
--   mysql -h127.0.0.1 -u<MYSQL_USER> -p<MYSQL_PASSWORD> zhihu_dongcha < 本文件   # 只跑到第 2 段
-- 第 3 段默认全是注释；放开前先做第 2 段的备份。
-- 顺序：先跑完本文件的删除，再跑 cleanup-demo-data.pg.sql，最后 docker compose restart backend
--       （后端启动会把 MySQL 里的业务数据装载进进程内镜像，顺序反了会看到已删文档仍在列表里）。
-- ============================================================================

-- ---------- 1. 只读：确认要删的就是这四演示库及其语料 ----------
SELECT k.id, k.name, k.scope,
       (SELECT count(*) FROM documents d WHERE d.kb_id = k.id) AS docs
FROM knowledge_bases k ORDER BY k.name;

SELECT account, role, enabled FROM users ORDER BY account;

-- ---------- 2. 备份（在服务器上执行，不放在本文件里）----------
-- mysqldump -h127.0.0.1 -u<MYSQL_USER> -p zhihu_dongcha \
--   users knowledge_bases documents citations conversations messages eval_runs eval_samples \
--   > mysql-backup-$(Get-Date -Format yyyyMMdd-HHmmss).sql

-- ---------- 3. 写操作（确认后逐条放开）----------

-- 3a. 抹掉编造的页码：历史 citations.page 是入库时用 `i/2+1` 造的，不是 PDF 真实页边界。
--     放开前先确认引用抽屉里"第 N 页"消失是可接受的（新版前端只在 page>0 时显示）。
-- UPDATE citations SET page = 0 WHERE page <> 0;

-- 3b. 删掉种子语料的文档与演示知识库。**不要在生产库上直接跑这四条**——
--     优先用接口 DELETE /api/documents/{id}，它会同时删 PG 向量、落盘文件和 MySQL 元数据；
--     直接 SQL 删除会把 backend/data/uploads 里的原始文件留成无主文件。
--     （uploads 目录里 58 个文件是历史原件的唯一副本，删除前必须人工确认。）
-- START TRANSACTION;
-- DELETE FROM citations WHERE doc_id IN (SELECT id FROM documents WHERE kb_id IN (<四个演示库 id>));
-- DELETE FROM documents WHERE kb_id IN (<四个演示库 id>);
-- DELETE FROM kb_members WHERE kb_id IN (<四个演示库 id>);
-- DELETE FROM conversation_kb_ids WHERE kb_id IN (<四个演示库 id>);
-- DELETE FROM knowledge_bases WHERE id IN (<四个演示库 id>);
-- COMMIT;

-- 3c. 演示账号：当前线上 users 只剩 admin@corp.com 与 km@corp.com（无 demo@corp.com），
--     如果历史库里还留着 demo/emp 账号，按需删除：
-- DELETE FROM users WHERE account IN ('demo@corp.com', 'emp@corp.com');

-- 3d. admin@corp.com 的口令是历史版本写死在源码里的 admin123456，公开可读。
--     不要用 SQL 改（BCrypt 由后端生成），登录后在系统设置里改成强口令即可。
--     改完之后 /api/settings/users 里看到的仍是同一个账号，只是密文换了。

-- ---------- 4. 复核 ----------
SELECT count(*) AS kbs FROM knowledge_bases;
SELECT count(*) AS docs FROM documents;
SELECT count(*) AS citations_with_page FROM citations WHERE page <> 0;
