import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Role } from '@/types/api'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/layouts/MainLayout.vue'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    minRole?: Role
    public?: boolean
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: MainLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/chat' },
      { path: 'chat', name: 'chat', component: () => import('@/views/ChatView.vue'), meta: { title: '智能问答' } },
      // 知识库/文档：后端矩阵对 GET 放开全部登录用户（内容按可见库过滤），写操作在视图内按角色隐藏（D-6）
      { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { title: '知识库' } },
      { path: 'documents', name: 'documents', component: () => import('@/views/DocumentsView.vue'), meta: { title: '文档管理' } },
      { path: 'eval', name: 'eval', component: () => import('@/views/EvalView.vue'), meta: { title: '评测中心', minRole: 'KM_ADMIN' } },
      { path: 'eval/questions', name: 'golden-questions', component: () => import('@/views/GoldenQuestionsView.vue'), meta: { title: '评测题库', minRole: 'KM_ADMIN' } },
      { path: 'monitor', name: 'monitor', component: () => import('@/views/MonitorView.vue'), meta: { title: '监控中心', minRole: 'SYS_ADMIN' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '系统设置', minRole: 'SYS_ADMIN' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/** 前端守卫仅做体验层控制，服务端仍会 403 */
router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const store = useUserStore()
  const ok = await store.restore()
  if (!ok) {
    return { path: '/login', query: to.fullPath === '/chat' ? undefined : { redirect: to.fullPath } }
  }
  if (to.meta.minRole && !store.atLeast(to.meta.minRole)) {
    return { path: '/chat' }
  }
  return true
})

export default router
