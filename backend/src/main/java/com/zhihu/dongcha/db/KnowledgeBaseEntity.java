package com.zhihu.dongcha.db;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * knowledge_bases + kb_members：知识库与共享成员
 * <p>D-2 有意不给这张表建索引：行数由管理员控制（几十量级），唯一的读路是
 * {@code findAllByOrderByCreatedAtAsc} 全量取——全量扫小表时优化器本来就不会用二级索引，
 * 加了只多付一次写成本。重名在代码层拒（见 {@code KnowledgeBaseController.requireUniqueName}），
 * 共享生产库不加 UNIQUE 约束。
 * <p>kb_members 的 kb_id 也不单独声明索引：它是集合表的外键列，InnoDB 建外键约束时会自动为它建索引，
 * 再按名字声明一份只会多出一个同列重复索引。发版后用 {@code SHOW INDEX FROM kb_members;} 核对，
 * 别把"我的注解没生成"当成"库里没有索引"。
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBaseEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Column(name = "name", length = 120)
    public String name;

    /** 库的敏感度/可见范围标识；Visibility 里 confidential 会收紧权限（KM_ADMIN 对机密库不自动可见） */
    @Column(name = "scope", length = 20)
    public String scope;

    @Column(name = "owner", length = 40)
    public String owner;

    @Column(name = "owner_name", length = 60)
    public String ownerName;

    @Column(name = "created_at")
    public long createdAt;

    /**
     * 共享成员。EAGER 是既有口径（可见性判定每库都要用），D-30 之后这张表每请求直读一次，
     * 所以补了批取：库清单 + 一批 members 查询，而不是"每个库一条 members"。
     */
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "kb_members", joinColumns = @JoinColumn(name = "kb_id"))
    @Column(name = "member", length = 80)
    public List<String> members = new ArrayList<>();
}
