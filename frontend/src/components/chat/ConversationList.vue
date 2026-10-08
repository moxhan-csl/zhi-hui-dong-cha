<template>
  <div class="conv-list">
    <el-button type="primary" class="conv-list__new" :icon="Plus" block @click="emit('create')">新建对话</el-button>
    <ul class="conv-list__ul" aria-label="历史对话列表">
      <li
        v-for="c in items"
        :key="c.id"
        class="conv-list__item"
        :class="{ 'is-active': c.id === activeId }"
        tabindex="0"
        role="button"
        @click="emit('select', c.id)"
        @keydown.enter="emit('select', c.id)"
      >
        <div class="conv-list__row">
          <span class="conv-list__title">{{ c.title || '未命名会话' }}</span>
          <el-popconfirm title="删除该会话？" @confirm="emit('remove', c.id)">
            <template #reference>
              <button class="conv-list__del" aria-label="删除会话" @click.stop>
                <el-icon :size="14"><Delete /></el-icon>
              </button>
            </template>
          </el-popconfirm>
        </div>
        <span class="conv-list__time muted">{{ fmt(c.updatedAt) }}</span>
      </li>
      <li v-if="!items.length" class="muted conv-list__empty">暂无历史对话</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import type { Conversation } from '@/types/api'

defineProps<{ items: Conversation[]; activeId: string | null }>()
const emit = defineEmits<{
  select: [id: string]
  create: []
  remove: [id: string]
}>()

function fmt(iso: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.conv-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.conv-list__new {
  margin-bottom: 10px;
}
.conv-list__ul {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}
.conv-list__item {
  padding: 8px 10px;
  border-radius: var(--radius-control);
  cursor: pointer;
  margin-bottom: 2px;
}
.conv-list__item:hover { background: #f2f6f4; }
.conv-list__item.is-active { background: var(--brand-soft); }
.conv-list__item:focus-visible { outline: 2px solid var(--brand); }
.conv-list__row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.conv-list__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-body);
}
.conv-list__del {
  border: none;
  background: none;
  color: #9aa6a0;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  display: inline-flex;
}
.conv-list__del:hover { color: var(--danger); }
.conv-list__time { display: block; }
.conv-list__empty { padding: 10px; text-align: center; }
</style>
