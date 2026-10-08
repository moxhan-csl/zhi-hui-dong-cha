package com.zhihu.dongcha.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 会话中的一条聊天消息（POJO）：落库与 SSE 回放共用，citations 可为空 */
public class ChatMessage {
    public String id = UUID.randomUUID().toString();
    public String role; // user / assistant
    public String content;
    /** assistant 轮的检索引用（命中片段来源）；为 null 时随全局 NON_NULL 从 JSON 中整体缺席 */
    public List<Map<String, Object>> citations;
    public long createdAt = System.currentTimeMillis();

    public ChatMessage() {
    }

    /** 按 role+content 建一条消息；id 与 createdAt 用字段默认值即时生成 */
    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
