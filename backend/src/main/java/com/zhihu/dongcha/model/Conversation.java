package com.zhihu.dongcha.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 一段问答会话（POJO）：归属 userId，knowledgeBaseIds 界定检索范围，messages 按时间顺序累积 */
public class Conversation {
    public String id = UUID.randomUUID().toString();
    public String userId;
    public String title;
    /** 本会话选定的知识库 id 列表（每轮检索只在这些可见库内进行） */
    public List<String> knowledgeBaseIds = new ArrayList<>();
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();
    /** 消息列表，用 Collections.synchronizedList 包装，允许一边生成一边追加 */
    public final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());
}
