-- ============================================================================
-- 清理已过期的评测记录 eval_runs / eval_samples（智汇洞察 / 线上服务器；真实地址不写进本仓库，在 /opt/dongcha/.env 里）
--
-- 为什么删：这 5 条评测（2026-09-23 ~ 09-28）当时是真跑出来的，但它们检索取样的
--   那 21 篇演示语料已经在 2026-09-29 全部删除。分数不代表当前系统，却仍在污染
--   监控中心的告警：「幻觉率 > 10% → 正常 | 最近评测幻觉率 3.7%」。删掉后这条会
--   自动变成如实的「尚无已完成的评测，幻觉率未知（未评测不等于合格）」。
--
-- 在哪执行：**服务器上**，SSH 登录后 cd /opt/dongcha。本机 Windows 跑不了：
--   MySQL 只在宿主机监听，且后端没有评测删除接口（EvalController 只有
--   POST /run 与 GET /runs|/runs/{id}|/trend|/golden-set|/export/{id}，没有 @DeleteMapping）。
--
-- 传文件 + 执行（本机 PowerShell，注意相对路径、别带盘符）：
--   scp scripts\purge-eval-runs.mysql.sql root@127.0.0.1:/opt/dongcha/   # root@127.0.0.1 是占位：换成服务器真实地址，否则只是复制到本机
-- 然后在服务器 /opt/dongcha 下：
--   mysql -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
--     zhihu_dongcha < purge-eval-runs.mysql.sql
--   ⚠ 第 3 段默认是注释：第一次跑只打印核对结果和备份提示，不会删任何东西。
--     要真删：先照第 2 段导出备份，再放开第 3 段注释单独执行。
--
-- 填错会怎样：账号/库名/口令错 → mysql 报 1045 或 Unknown database，不会误伤；
--   跑到第 3 段就是真删，评测明细（含每个样本的 question/answer）删了只能从第 2 段备份恢复。
--
-- 删完**不用重启后端**：读路径 DbStore.evalRunsDesc() 直接 SELECT eval_runs（DbStore.java:195-199），
--   进程里那份 store.evalRuns 只在启动时用于日志条数统计，不参与接口返回。
--   顺带提醒：库里现在 0 篇文档，**别急着重跑评测**，先上传真实语料再评，
--   否则检索全部落空，指标会大面积"未判定"或极难看，那也不是真实质量。
-- ============================================================================

-- ---------- 1. 只读：确认要删的是哪些 ----------
SELECT id, status, sample_count,
       from_unixtime(created_at/1000) AS created
FROM eval_runs
ORDER BY created_at DESC;

-- 逐样本明细表：可能为空（saveEvalRun 主要写 eval_runs.samples_json），有数据就一起备份
SELECT count(*) AS eval_sample_rows FROM eval_samples;

-- ---------- 2. 备份：删之前先导出（在 shell 里跑，不是 SQL）----------
--   mysqldump -h127.0.0.1 -u"$(grep ^MYSQL_USER .env | cut -d= -f2)" -p"$(grep ^MYSQL_PASSWORD .env | cut -d= -f2)" \
--     zhihu_dongcha eval_runs eval_samples > "eval-backup-$(date +%F-%H%M).sql"
--   ls -lh eval-backup-*.sql        # → 非空再看下一步；空文件等于没备份
--   恢复：mysql ... zhihu_dongcha < eval-backup-2026-09-29-1830.sql

-- ---------- 3. 真删：默认注释，看过第 1 段和备份文件大小再放开 ----------
-- DELETE FROM eval_samples;
-- DELETE FROM eval_runs;

-- 只想删演示期那几条、保留将来新跑的：按时间切（今天 0 点之前的都是过期评测）
-- DELETE FROM eval_samples WHERE run_id IN
--   (SELECT id FROM eval_runs WHERE created_at < UNIX_TIMESTAMP('2026-09-29 00:00:00') * 1000);
-- DELETE FROM eval_runs WHERE created_at < UNIX_TIMESTAMP('2026-09-29 00:00:00') * 1000;

-- ---------- 4. 验证（两个都应为 0）----------
SELECT count(*) AS runs_left FROM eval_runs;
SELECT count(*) AS samples_left FROM eval_samples;
