package com.zhihu.dongcha.seed;

import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.model.*;
import com.zhihu.dongcha.rag.ChunkStore;
import com.zhihu.dongcha.security.Passwords;
import com.zhihu.dongcha.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;

/**
 * 启动装配：
 * <ol>
 *   <li>{@link DbStore#loadAll()} 载入 users 鉴权镜像与运行期配置（D-30 后不再整表灌业务数据）；</li>
 *   <li>users 表为空时只播种<b>系统管理员</b>一个账号（口令取 {@code ADMIN_INIT_PASSWORD}，
 *       未配置则随机生成并打印一次），知识库与文档一律由管理员在页面上创建；</li>
 *   <li>上次进程退出时停在 PARSING/PENDING 的文档收敛成 FAILED，可在文档页重试；</li>
 *   <li>上次进程退出时仍在 running 的评测收敛成 failed（D-18）；</li>
 *   <li>存量明文密码升级为 BCrypt；</li>
 *   <li>向量库对账：清掉文档已不在 MySQL、向量却还留在库里的孤儿 chunk。</li>
 * </ol>
 * 不再播种演示语料/演示知识库/演示用户，也不再伪造监控延迟基线样本。
 * 播种在启动完成后（ApplicationRunner）执行，不阻塞端口就绪。
 */
@Component
public class SeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedInitializer.class);

    private final Store store;
    private final DbStore db;
    private final ChunkStore chunks;

    /** 注入 Store（users 鉴权镜像）、DbStore（MySQL 读写）、ChunkStore（向量库对账） */
    public SeedInitializer(Store store, DbStore db, ChunkStore chunks) {
        this.store = store;
        this.db = db;
        this.chunks = chunks;
    }

    /** 启动装配主流程：载入 MySQL→空库才播种管理员→三项启动对账→明文密码升级；MySQL 载入失败时跳过会删数据的对账 */
    @Override
    public void run(ApplicationArguments args) {
        boolean seeded = false;
        boolean mysqlLoaded = true;
        try {
            db.loadAll();
        } catch (Exception e) {
            mysqlLoaded = false;
            log.error("从 MySQL 载入失败：users 镜像为空、运行期配置为默认值，三项启动对账跳过"
                    + "（业务表本就每请求直读 MySQL，所以此后接口会逐个报错而不是返回空数据。请检查 spring.datasource 配置）", e);
        }
        if (store.users.isEmpty()) {
            seedAdminAccount();
            seeded = true;
        }
        // D-30 之后这几项各用自己的一条定向查询，不再遍历内存镜像；
        // 载入失败时全部跳过——拿读不到的清单去删数据，比不删危险得多。
        if (mysqlLoaded) {
            reconcileInterruptedIngest();
            reconcileInterruptedEvalRuns();
            reconcileOrphanVectors();
        } else {
            log.warn("MySQL 未载入，跳过三项启动对账（中断解析 / 中断评测 / 向量孤儿）");
        }
        migratePlaintextPasswords();
        if (!seeded) log.info("业务数据: users={} kbs={} docs={} chunks={}",
                store.users.size(), db.countKbs(), db.countDocs(), chunks.countAll());
    }

    /**
     * 清掉孤儿向量：文档行已经不在了，chunk 还留在向量库里。
     * 正常删除链路会一起清（{@code DocumentController.delete} 同时清元数据、向量与落盘文件），但历史上有过
     * 「向量库降级期删文档」和「直接清 MySQL 表」的窗口，残留下来的向量永远不会被检索命中
     * （search 按可见 kb_id 过滤），却会算进总览的分块数，让统计说谎。
     * <p>基准清单是这一条：{@code SELECT id FROM documents}（D-30 之前是内存镜像的 keySet）。
     * 读不到它就整体跳过——这条循环会删向量，半截基准等于把整库向量删光。
     */
    private void reconcileOrphanVectors() {
        Set<String> aliveDocIds;
        try {
            aliveDocIds = db.allDocIds();
        } catch (Exception e) {
            log.warn("文档 id 基准清单读取失败，跳过向量库对账: {}", e.toString());
            return;
        }
        for (Map.Entry<String, String> e : chunks.distinctDocs().entrySet()) {
            String docId = e.getKey();
            if (aliveDocIds.contains(docId)) continue;
            long before = chunks.countByDoc(docId);
            try {
                chunks.deleteByDoc(docId);
                log.warn("向量库对账: 文档 {}（{}）已不存在，清掉其遗留的 {} 个向量分块", docId, e.getValue(), before);
            } catch (RuntimeException ex) {
                log.warn("清理孤儿向量 {} 失败: {}", docId, ex.toString());
            }
        }
    }

    /**
     * 把上次进程退出时仍在解析的文档收敛成 FAILED。解析跑在进程内的守护线程池上，
     * 容器重启（升级、OOM、宿主重启）会把跑到一半的解析掐断，元数据却停在 PARSING：
     * 前端进度条永远转圈，而 retry 只接受 FAILED 或向量缺失的文档，于是这篇再也点不动。
     * 标成 FAILED 就能在文档页一键重跑；不自动重新提交，以免重启风暴时把 embedding 配额打满。
     * <p>只捞停在解析态的行（D-30）：以前是遍历整张 documents 镜像，行数越多启动越慢。
     */
    private void reconcileInterruptedIngest() {
        for (DocRecord doc : db.docsInStatus("PARSING", "PENDING")) {
            doc.status = "FAILED";
            doc.progress = 0;
            doc.stage = "failed";
            doc.failReason = "服务重启时解析尚未完成，请重试";
            doc.updatedAt = System.currentTimeMillis();
            try {
                db.saveDoc(doc);
                log.info("中断的解析已置为 FAILED，可在文档页重试: doc={} name={}", doc.id, doc.name);
            } catch (Exception e) {
                log.warn("回写中断文档 {} 失败: {}", doc.id, e.toString());
            }
        }
    }

    /**
     * 把上次进程退出时仍在 running 的评测收敛成 failed（D-18）。评测跑在进程内的 eval-worker 线程上，
     * 重启会掐断执行，run 行却停在 running、progress 冻在半路：列表里它一直显示"进行中"，
     * 已算出的那部分样本还容易被当成完整结果读。已写入的样本明细保留，只改状态。
     * <p>澄清一处早前的判断：running 行<b>不会</b>挡住新评测——{@code EvalService.evalRunning}
     * 是进程内标志，重启即复位。这条修的是读数失真，不是死锁。
     */
    private void reconcileInterruptedEvalRuns() {
        try {
            db.interruptRunningEvalRuns();
        } catch (Exception e) {
            log.warn("中断评测对账失败: {}", e.toString());
        }
    }

    /** 存量明文密码升级为 BCrypt 密文（幂等：已是 $2 开头的行跳过） */
    private void migratePlaintextPasswords() {
        int upgraded = 0;
        for (User u : db.allUsers()) {
            if (!Passwords.isHashed(u.password)) {
                u.password = Passwords.hash(u.password);
                db.saveUser(u);
                upgraded++;
            }
        }
        if (upgraded > 0) log.info("password migration: {} 个账号的明文密码已升级为 BCrypt 密文", upgraded);
    }

    // ==================== 管理员账号（空库时唯一的播种对象） ====================

    /**
     * 口令优先取环境变量 ADMIN_INIT_PASSWORD；没配则随机生成并只在日志里打印一次。
     * 历史版本把演示口令 admin123456 写死在源码里，线上库沿用至今，需要管理员首次登录后改掉。
     */
    private void seedAdminAccount() {
        String fromEnv = System.getenv("ADMIN_INIT_PASSWORD");
        boolean generated = fromEnv == null || fromEnv.isBlank();
        String password = generated ? randomPassword() : fromEnv;
        User admin = new User("系统管理员", "admin@corp.com", "EMP90001",
                Passwords.hash(password), "SYS_ADMIN", "信息技术部");
        try {
            db.saveUser(admin);
            if (generated) {
                log.warn("已创建系统管理员 admin@corp.com，一次性随机口令：{}（日志只显示这一次，请登录后立即修改）", password);
            } else {
                log.info("已创建系统管理员 admin@corp.com（口令来自 ADMIN_INIT_PASSWORD）");
            }
        } catch (Exception e) {
            log.error("创建系统管理员失败", e);
        }
    }

    /** 生成 14 位随机口令：字母数字去易混字符、含少量符号，用 SecureRandom 取值 */
    private static String randomPassword() {
        final String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789@#$%";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
