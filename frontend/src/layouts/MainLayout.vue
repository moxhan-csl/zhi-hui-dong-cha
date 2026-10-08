<template>
  <div class="main-layout">
    <!-- 桌面/平板：固定左导航 -->
    <SideNav v-if="!mobile" class="main-layout__nav" />
    <!-- 手机：汉堡抽屉 -->
    <el-drawer v-else v-model="navOpen" direction="ltr" :size="260" :with-header="false" class="nav-drawer">
      <SideNav @navigate="navOpen = false" />
    </el-drawer>

    <div class="main-layout__main">
      <TopBar :mobile="mobile" @toggle-nav="navOpen = !navOpen" />
      <main class="main-layout__content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import SideNav from '@/components/layout/SideNav.vue'
import TopBar from '@/components/layout/TopBar.vue'
import { useMedia } from '@/composables/useMedia'

const { width } = useMedia()
const mobile = computed(() => width.value < 768)
const navOpen = ref(false)
</script>

<style scoped>
.main-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
}
.main-layout__nav {
  flex: 0 0 var(--nav-width);
  width: var(--nav-width);
}
.main-layout__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.main-layout__content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--bg-page);
}
.nav-drawer :deep(.el-drawer__body) {
  padding: 0;
}
</style>
