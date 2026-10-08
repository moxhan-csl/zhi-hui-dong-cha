package com.zhihu.dongcha.db;

import jakarta.persistence.*;

/** settings_models：大模型服务配置键值表 */
@Entity
@Table(name = "settings_models")
public class SettingModelEntity {

    /** 配置键，如 chat.baseUrl / chat.model / chat.temperature / embedding.model（由 DbStore.saveModels 写死这几个） */
    @Id
    @Column(name = "k", length = 60)
    public String k;

    /** 配置值：数值与布尔都先转成字符串存，DbStore.loadModels 读回时再解析 */
    @Column(name = "v", length = 500)
    public String v;

    /** JPA/Hibernate 实例化实体用的无参构造 */
    public SettingModelEntity() {
    }

    /** 直接给键值构造一行，DbStore.saveModels 写穿时用（saveAll 按主键 k upsert） */
    public SettingModelEntity(String k, String v) {
        this.k = k;
        this.v = v;
    }
}
