<template>
  <nav class="side-nav" aria-label="主导航">
    <div class="side-nav__brand">
      <span class="side-nav__logo" aria-hidden="true">智</span>
      <span class="side-nav__title">智汇洞察</span>
    </div>
    <el-menu
      class="side-nav__menu"
      :default-active="activePath"
      router
      :collapse="false"
    >
      <el-menu-item v-for="item in visibleItems" :key="item.path" :index="item.path" @click="emit('navigate')">
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </el-menu-item>
    </el-menu>
    <div class="side-nav__footer muted">v1.0 · MVP</div>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import type { Role } from '@/types/api'
import { useUserStore } from '@/stores/user'

const emit = defineEmits<{ navigate: [] }>()
const route = useRoute()
const store = useUserStore()

const items: { path: string; label: string; icon: string; minRole: Role }[] = [
  { path: '/chat', label: '智能问答', icon: 'ChatDotRound', minRole: 'EMPLOYEE' },
  { path: '/knowledge', label: '知识库', icon: 'Collection', minRole: 'EMPLOYEE' },
  { path: '/documents', label: '文档管理', icon: 'Document', minRole: 'EMPLOYEE' },
  { path: '/eval', label: '评测中心', icon: 'DataAnalysis', minRole: 'KM_ADMIN' },
  { path: '/eval/questions', label: '评测题库', icon: 'EditPen', minRole: 'KM_ADMIN' },
  { path: '/monitor', label: '监控中心', icon: 'Odometer', minRole: 'SYS_ADMIN' },
  { path: '/settings', label: '系统设置', icon: 'Setting', minRole: 'SYS_ADMIN' },
]

const visibleItems = computed(() => items.filter((i) => store.atLeast(i.minRole)))
const activePath = computed(() => route.path)
</script>

<style scoped>
.side-nav {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg);
  border-right: 1px solid var(--border);
}
.side-nav__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--topbar-height);
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
}
.side-nav__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--brand);
  color: #fff;
  font-weight: 700;
}
.side-nav__title {
  font-size: 17px;
  font-weight: 600;
  color: var(--text);
  letter-spacing: 1px;
}
.side-nav__menu {
  flex: 1;
  border-right: none;
}
.side-nav__menu :deep(.el-menu-item.is-active) {
  background: var(--brand-soft);
}
.side-nav__footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
}
</style>

<style>
.nav-drawer .el-drawer__body {
  padding: 0 !important;
}
</style>
