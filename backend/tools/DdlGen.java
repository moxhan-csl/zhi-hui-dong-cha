import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * 离线生成建表 DDL：不连任何数据库，把实体当前声明的表、列、索引写成一份 SQL 文本。
 * <p>为什么要它：{@code ddl-auto: update} 会在应用启动时对着共享生产库直接执行这些 DDL，
 * 而"注解里写了索引"和"Hibernate 真会发出 CREATE INDEX"是两件事——尤其
 * {@code messages.conversation_id} 与 {@code citations.message_id} 是关联映射贡献的外键列、
 * 实体里没有对应字段，索引能不能落到它们身上必须看生成的语句。
 * <p>它同样只证明"语句长这样"，不证明生产库上的执行结果：执行后仍要 SHOW INDEX 核对。
 * <p>跑法见 {@code docs/DEPLOY.md} 10.1 末尾的 D-2 发版块（"发版前看一眼 Hibernate 会生成哪些 DDL"）：类路径取自
 * {@code mvn -o -X compile} 打印的 compilePath 一行（本机离线仓库里没有 maven-dependency-plugin，
 * 用不了 build-classpath）。
 */
public class DdlGen {

    private static final String[] ENTITIES = {
            "com.zhihu.dongcha.db.UserEntity",
            "com.zhihu.dongcha.db.KnowledgeBaseEntity",
            "com.zhihu.dongcha.db.DocumentEntity",
            "com.zhihu.dongcha.db.ConversationEntity",
            "com.zhihu.dongcha.db.MessageEntity",
            "com.zhihu.dongcha.db.CitationEntity",
            "com.zhihu.dongcha.db.EvalRunEntity",
            "com.zhihu.dongcha.db.GoldenQuestionEntity",
            "com.zhihu.dongcha.db.AuditLogEntity",
            "com.zhihu.dongcha.db.SettingModelEntity",
            "com.zhihu.dongcha.db.SettingRagEntity",
    };

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "target/ddl-verify.sql";
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        // 两条都要：只要 Hibernate 去问数据库版本，它就会试图取连接、在这里失败
        settings.put("hibernate.temp.use_jdbc_metadata_defaults", false);
        settings.put("hibernate.boot.allow_jdbc_metadata_access", false);
        settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
        settings.put("jakarta.persistence.schema-generation.scripts.create-target", out);
        MetadataSources sources = new MetadataSources(new StandardServiceRegistryBuilder()
                .applySettings(settings).build());
        for (String cn : ENTITIES) sources.addAnnotatedClass(Class.forName(cn));
        try (var sf = sources.buildMetadata().buildSessionFactory()) {
            System.out.println("DDL generated without touching any database: " + out);
        }
    }
}
