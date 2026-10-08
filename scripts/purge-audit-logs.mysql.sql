-- ============================================================================
-- 清空操作审计日志 audit_logs（智汇洞察 / 线上服务器；真实地址不写进本仓库，在 /opt/dongcha/.env 里）
--
-- 在哪执行：**服务器上**，SSH 登录后 cd /opt/dongcha（compose 与 .env 所在目录）。
--   本地 Windows 跑不了：MySQL 只监听宿主机，后端容器和 mysql 客户端都在服务器侧。
--   为什么不能走接口：后端只实现了 GET /api/settings/audit-logs，没有任何删除接口
--   （SettingsController 里没有 @DeleteMapping("/audit-logs")），所以只能落 SQL。
--
-- 执行命令（在 /opt/dongcha 下，会自己从 .env 取账号，不用手输口令）：
--   mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
--     zhihu_dongcha < scripts/purge-audit-logs.mysql.sql
--   ⚠ 但本文件第 3 段默认是注释：第一次跑上面这条只会打印核对结果，不会删任何东西。
--   要真删：先照第 2 段导出备份，再放开第 3 段的注释、单独执行那一句。
--
-- 填错会怎样：
--   库名/账号错 → mysql 直接报 Access denied / Unknown database，不会误伤；
--   口令错 → 同上，报 1045；
--   跑到第 3 段就是真删，audit_logs 没有软删除也没有回收站，删了只能从第 2 段的备份恢复。
--
-- 删完要不要重启后端：**不用**。读路径 DbStore.auditLogs() 是直接 SELECT audit_logs 表
--   （DbStore.java:276-283），进程内那份 store.auditLogs 只用于启动日志里的条数统计，
--   不参与接口返回，所以刷新设置页就能看到列表变空。
-- ============================================================================

-- ---------- 1. 只读：先看要删掉的是什么 ----------
SELECT count(*)                                  AS rows_total,
       from_unixtime(min(at_time)/1000)          AS earliest,
       from_unixtime(max(at_time)/1000)          AS latest
FROM audit_logs;

-- 按动作分类看一遍：确认没有你打算留着的合规取证记录
SELECT action, count(*) AS n
FROM audit_logs
GROUP BY action
ORDER BY n DESC;

-- ---------- 2. 备份：删之前务必先留一份（不是 SQL，在 shell 里跑）----------
--   mysqldump -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
--     zhihu_dongcha audit_logs > "audit_logs-backup-$(date +%F-%H%M).sql"
--   恢复：mysql ... zhihu_dongcha < audit_logs-backup-2026-09-29-1830.sql

-- ---------- 3. 真删：默认注释，看清第 1 段的输出再放开 ----------
-- DELETE FROM audit_logs;

-- 只想删演示期的记录、保留今天这次清理轨迹，就用下面这条替代上一句
-- （2026-09-29 00:00 之后写的 KB_DELETE / DOC_DELETE / 会话删除等是真实操作轨迹，值得留）：
-- DELETE FROM audit_logs WHERE at_time < UNIX_TIMESTAMP('2026-09-29 00:00:00') * 1000;

-- ---------- 4. 验证（期望 rows_left = 0）----------
SELECT count(*) AS rows_left FROM audit_logs;
