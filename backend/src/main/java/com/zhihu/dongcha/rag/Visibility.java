package com.zhihu.dongcha.rag;

import com.zhihu.dongcha.db.DbStore;
import com.zhihu.dongcha.model.KnowledgeBase;
import com.zhihu.dongcha.model.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * scope 四档可见性：EMPLOYEE 仅 public 与被共享库；KM_ADMIN 除机密外全部；SYS_ADMIN 全部。
 * <p>D-30：库清单改为每次判定直读 MySQL，进程内不留全量镜像。两条后果都是想要的：
 * 一是内存不再随知识库数量增长；二是<b>共享与取消共享立即生效</b>，
 * 不像 users 镜像那样要等一个 TTL 周期——可见性是权限判定，晚 60 秒放行就是多放行 60 秒。
 * <p>代价是每个请求多一次 knowledge_bases 全表读（含 members 集合展开）。
 * 因此本类的方法是阻塞调用，只能在 {@code Schedulers.boundedElastic()} 上跑，
 * 不能留在 Netty 事件循环上（调用点见 ChatService.stream 与 DocumentController.upload，两处都已挪进 deferred 链）。
 */
@Component
public class Visibility {

    private final DbStore db;

    public Visibility(DbStore db) {
        this.db = db;
    }

    /** 可见库，按创建时间升序（排序来自 SQL，不在内存里再排一遍） */
    public List<KnowledgeBase> visibleKbs(User user) {
        List<KnowledgeBase> out = new ArrayList<>();
        for (KnowledgeBase kb : db.allKbs()) {
            if (canSee(user, kb)) out.add(kb);
        }
        return out;
    }

    /** visibleKbs 的 id 投影：每次调用同样会把 knowledge_bases 全表读一遍，别放进循环里重复调 */
    public Set<String> visibleKbIds(User user) {
        Set<String> ids = new HashSet<>();
        for (KnowledgeBase kb : visibleKbs(user)) ids.add(kb.id);
        return ids;
    }

    /**
     * 本次检索实际生效的知识库范围 = 请求范围 ∩ 可见范围（D-34：全站唯一定义处）。
     * requested 为空表示"不限定"，即当前用户全部可见库；结果去重并排序，
     * 便于作为回答缓存 key 的组成部分。
     */
    public List<String> scopeFor(User user, Collection<String> requested) {
        Set<String> visible = visibleKbIds(user);
        Set<String> scopes = new LinkedHashSet<>();
        if (requested == null || requested.isEmpty()) {
            scopes.addAll(visible);
        } else {
            for (String id : requested) if (visible.contains(id)) scopes.add(id);
        }
        List<String> out = new ArrayList<>(scopes);
        Collections.sort(out);
        return out;
    }

    /** 逐条短路判定；scope 只认 public 与 confidential 两个字面值，internal/dept 对 EMPLOYEE 等同于不公开 */
    public boolean canSee(User user, KnowledgeBase kb) {
        if ("SYS_ADMIN".equals(user.role)) return true;
        if ("KM_ADMIN".equals(user.role) && !"confidential".equals(kb.scope)) return true;
        if ("public".equals(kb.scope)) return true;
        if (kb.owner != null && kb.owner.equals(user.id)) return true;
        return sharedWith(user, kb);
    }

    /** members 是混合口径的 List<String>：可能存用户 id、工号、账号或 dept:<部门>，任一命中即算被共享 */
    private boolean sharedWith(User user, KnowledgeBase kb) {
        return kb.members.contains(user.id)
                || (user.empNo != null && kb.members.contains(user.empNo))
                || (user.account != null && kb.members.contains(user.account))
                || (user.dept != null && kb.members.contains("dept:" + user.dept));
    }
}
