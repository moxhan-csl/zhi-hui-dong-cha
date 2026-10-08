package com.zhihu.dongcha.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一个知识库（POJO）：scope 界定默认可见范围，members 叠加可见的用户/部门，检索按可见库过滤 */
public class KnowledgeBase {
    public String id = UUID.randomUUID().toString();
    public String name;
    public String scope;            // public / internal / dept / confidential
    public List<String> members = new ArrayList<>(); // 用户id / 工号 / "dept:xxx"
    public String owner;            // userId
    public String ownerName;
    public long createdAt = System.currentTimeMillis();

    public KnowledgeBase() {
    }
}
