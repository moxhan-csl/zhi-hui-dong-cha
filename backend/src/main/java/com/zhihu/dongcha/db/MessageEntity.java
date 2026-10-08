package com.zhihu.dongcha.db;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * messages：会话消息（assistant 消息携带 citations 明细）
 * <p>conversation_id 是 {@code ConversationEntity.messages} 的外键列、不是本实体的字段，
 * 但每次载入会话都会按 {@code order by ord} 回表取它，所以索引声明在这里（D-2）。
 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_conversation_ord", columnList = "conversation_id,ord")
})
public class MessageEntity {

    @Id
    @Column(name = "id", length = 40)
    public String id;

    /** 消息角色，取值 user / assistant（SynthesisPrompt 组装上下文时按这两类过滤） */
    @Column(name = "role", length = 20)
    public String role;

    /** 会话内顺序 */
    @Column(name = "ord")
    public int ord;

    @Lob
    @Column(name = "content", columnDefinition = "LONGTEXT")
    public String content;

    @Column(name = "created_at")
    public long createdAt;

    /** 该消息的行内引用卡片：EAGER 随消息载入、级联增删、按 idx 升序（assistant 消息才有） */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "message_id", referencedColumnName = "id")
    @OrderBy("idx ASC")
    public List<CitationEntity> citations = new ArrayList<>();
}
