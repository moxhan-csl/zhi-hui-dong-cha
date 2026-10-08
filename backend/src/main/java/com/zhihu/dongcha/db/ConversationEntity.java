package com.zhihu.dongcha.db;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * conversations：会话（消息见 messages 表）
 * <p>复合索引按 {@link ConversationRepository#findByUserIdOrderByUpdatedAtDesc} 的形态声明（D-2）：
 * 会话列表是"按人筛 + 按更新时间倒序"，单列索引只能满足其中一半。
 */
@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conversations_user_updated", columnList = "user_id,updated_at")
})
public class ConversationEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    @Column(name = "user_id", length = 40)
    public String userId;

    @Column(name = "title", length = 255)
    public String title;

    @Column(name = "created_at")
    public long createdAt;

    @Column(name = "updated_at")
    public long updatedAt;

    /** 本会话限定的知识库 id：@ElementCollection 单独落到 conversation_kb_ids 表，随会话 EAGER 载入 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "conversation_kb_ids", joinColumns = @JoinColumn(name = "conversation_id"))
    @Column(name = "kb_id", length = 40)
    public List<String> knowledgeBaseIds = new ArrayList<>();

    /** 会话消息：级联增删 + orphanRemoval（删会话即删消息），按 ord 升序 EAGER 载入 */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "conversation_id", referencedColumnName = "id")
    @OrderBy("ord ASC")
    public List<MessageEntity> messages = new ArrayList<>();
}
