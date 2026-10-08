package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/** settings_rag：RAG 策略键值表（热生效） */
@Entity
@Table(name = "settings_rag")
public class SettingRagEntity {

    /** 配置键，如 queryRewrite / multiQueryCount / topK / scoreThreshold（由 DbStore.saveRag 写死这几个） */
    @Id
    @Column(name = "k", length = 60)
    public String k;

    /** 配置值：数值与布尔都先转成字符串存，DbStore.loadRag 读回时再解析 */
    @Column(name = "v", length = 500)
    public String v;

    /** JPA/Hibernate 实例化实体用的无参构造 */
    public SettingRagEntity() {
    }

    /** 直接给键值构造一行，DbStore.saveRag 写穿时用（saveAll 按主键 k 覆盖同名键） */
    public SettingRagEntity(String k, String v) {
        this.k = k;
        this.v = v;
    }
}
