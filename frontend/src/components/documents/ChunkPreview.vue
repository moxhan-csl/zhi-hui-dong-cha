<template>
  <div class="chunk-preview" v-loading="loading">
    <ul v-if="chunks.length">
      <li v-for="c in chunks" :key="c.index" class="chunk-preview__item">
        <div class="chunk-preview__meta muted">
          #{{ c.index }} · {{ c.charCount }} 字符<span v-if="c.page"> · 第 {{ c.page }} 页</span>
        </div>
        <p class="chunk-preview__text">{{ c.text }}</p>
      </li>
    </ul>
    <p v-else-if="!loading" class="muted">暂无分块数据</p>
    <div v-if="hasMore || total != null" class="chunk-preview__more">
      <span class="muted">已加载 {{ chunks.length }}<template v-if="total != null"> / {{ total }}</template> 个分块</span>
      <el-button v-if="hasMore" size="small" text type="primary" @click="loadMore">加载更多</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { listChunks } from '@/api/documents'
import type { DocChunk } from '@/types/api'

const props = defineProps<{ docId: string; total?: number }>()
const chunks = ref<DocChunk[]>([])
const loading = ref(false)
const page = ref(1)
const hasMore = ref(false)

/** 后端 /chunks 固定每页 10 条 */
const PAGE_SIZE = 10

async function load(reset = false) {
  loading.value = true
  try {
    if (reset) {
      chunks.value = []
      page.value = 1
    }
    const res = await listChunks(props.docId, page.value)
    if (reset) chunks.value = res
    else chunks.value.push(...res)
    // 有真实总数就按总数判断，没有就只看这一页是否填满
    hasMore.value = res.length === PAGE_SIZE && (props.total == null || chunks.value.length < props.total)
  } catch { /* handled */ } finally {
    loading.value = false
  }
}

function loadMore() {
  page.value += 1
  load()
}

watch(() => props.docId, () => load(true), { immediate: true })
</script>

<style scoped>
.chunk-preview {
  padding: 8px 16px;
}
.chunk-preview__item {
  border-left: 3px solid var(--brand-soft);
  padding: 6px 10px;
  margin-bottom: 6px;
  background: #fafcfb;
  border-radius: 0 8px 8px 0;
}
.chunk-preview__meta {
  margin-bottom: 2px;
}
.chunk-preview__text {
  margin: 0;
  font-size: 13px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
}
.chunk-preview__more {
  text-align: center;
}
</style>
