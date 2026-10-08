package com.zhihu.dongcha.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** conversations 的仓库；只被 DbStore 调用，会话整体随消息 EAGER 载入 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {

    /** 某人的会话列表按更新时间倒序（"我的会话"侧栏），走 (user_id, updated_at) 复合索引 */
    List<ConversationEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 重写以把入参收窄成 String（父类签名是 Serializable），载入单会话及其全部消息时用 */
    @Override
    Optional<ConversationEntity> findById(String id);
}
