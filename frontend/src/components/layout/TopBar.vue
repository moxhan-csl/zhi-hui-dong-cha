<template>
  <header class="top-bar">
    <el-button v-if="mobile" class="top-bar__hamburger" text aria-label="打开导航菜单" @click="emit('toggle-nav')">
      <el-icon :size="20"><Fold /></el-icon>
    </el-button>

    <div class="top-bar__search" role="search">
      <el-input
        ref="searchRef"
        v-model="keyword"
        placeholder="全局搜索文档 / 知识"
        clearable
        aria-label="全局搜索"
        @keyup.enter="doSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #suffix><kbd class="top-bar__kbd">{{ kbdHint }}K</kbd></template>
      </el-input>
    </div>

    <div class="top-bar__actions">
      <el-dropdown trigger="click" @command="onCommand">
        <span class="top-bar__user">
          <el-avatar :size="30" class="top-bar__avatar">{{ user?.name?.[0] || 'U' }}</el-avatar>
          <span v-if="!mobile" class="top-bar__username">{{ user?.name }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>{{ roleLabel }}{{ user?.dept ? ` · ${user.dept}` : '' }}</el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon> 退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Search, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessageBox } from 'element-plus'
import type { Role } from '@/types/api'

const props = defineProps<{ mobile: boolean }>()
const emit = defineEmits<{ 'toggle-nav': [] }>()
const router = useRouter()
const store = useUserStore()
const user = computed(() => store.user)

const roleLabels: Record<Role, string> = { EMPLOYEE: '普通员工', KM_ADMIN: '知识管理员', SYS_ADMIN: '系统管理员' }
const roleLabel = computed(() => (user.value ? roleLabels[user.value.role] : ''))

const keyword = ref('')
const searchRef = ref<{ focus: () => void } | null>(null)
const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
const kbdHint = isMac ? '⌘' : 'Ctrl+'

function onKey(e: KeyboardEvent) {
  if ((isMac ? e.metaKey : e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    searchRef.value?.focus()
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) return
  router.push({ path: '/documents', query: { keyword: kw } })
}

async function onCommand(cmd: string) {
  if (cmd === 'logout') {
    await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
    store.logout()
    router.push('/login')
  }
}
defineExpose({ doSearch })
void computed
void props
</script>

<style scoped>
.top-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: var(--topbar-height);
  flex: 0 0 var(--topbar-height);
  padding: 0 16px;
  background: var(--bg);
  border-bottom: 1px solid var(--border);
}
.top-bar__search {
  flex: 1;
  max-width: 420px;
}
.top-bar__kbd {
  font-size: 11px;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  color: #7a857f;
  background: #f6f8f7;
}
.top-bar__actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.top-bar__user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline-offset: 2px;
}
.top-bar__user:focus-visible {
  outline: 2px solid var(--brand);
}
.top-bar__avatar {
  background: var(--brand);
  color: #fff;
}
.top-bar__username {
  font-size: var(--font-body);
}
@media (max-width: 767px) {
  .top-bar__search {
    display: none;
  }
}
</style>
