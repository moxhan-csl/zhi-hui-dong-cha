<template>
  <div class="login">
    <!-- 左品牌区 -->
    <section class="login__brand" aria-hidden="true">
      <div class="login__brand-inner">
        <div class="login__logo">智</div>
        <h1 class="login__product">智汇洞察</h1>
        <p class="login__slogan">企业级 AI 知识助手</p>
        <ul class="login__features">
          <li><el-icon><Select /></el-icon> RAG 检索增强，答案可溯源</li>
          <li><el-icon><Select /></el-icon> 多智能体编排，全过程可视</li>
          <li><el-icon><Select /></el-icon> 知识库隔离权限，安全合规</li>
          <li><el-icon><Select /></el-icon> 评测与监控，质量运营闭环</li>
        </ul>
      </div>
    </section>

    <!-- 右表单区 -->
    <section class="login__panel">
      <div class="login__form-wrap">
        <h2 class="login__welcome">欢迎回来</h2>
        <p class="muted">登录以开始与企业知识对话</p>

        <el-alert v-if="topError" :title="topError" type="error" show-icon :closable="false" class="login__alert" role="alert" />

        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item :error="errors.account">
            <template #label>企业账号</template>
            <el-input
              v-model="form.account"
              placeholder="邮箱或员工工号"
              size="large"
              autocomplete="username"
              :prefix-icon="User"
              @blur="validateAccount"
            />
          </el-form-item>
          <el-form-item :error="errors.password">
            <template #label>密码</template>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="6-32 位，含字母与数字"
              size="large"
              autocomplete="current-password"
              show-password
              :prefix-icon="Lock"
              @blur="validatePassword"
              @keyup.enter="submit"
            />
          </el-form-item>

          <div class="login__row">
            <el-checkbox v-model="form.remember">记住登录</el-checkbox>
            <a class="login__forgot" href="javascript:void(0)" @click="forgot">忘记密码？</a>
          </div>

          <el-button
            type="primary"
            size="large"
            class="login__submit"
            :disabled="!formValid || cooldown > 0 || loading"
            :loading="loading"
            @click="submit"
          >
            {{ cooldown > 0 ? `请稍后重试（${cooldown}s）` : '登 录' }}
          </el-button>
        </el-form>

        <footer class="login__foot muted">智汇洞察 v1.0.0 · © 2026 Corp. Inc. 保留所有权利</footer>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Select, User } from '@element-plus/icons-vue'
import { login } from '@/api/auth'
import { HttpError } from '@/api/http'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()

const REMEMBER_KEY = 'zd_remember_account'
const form = reactive({
  account: localStorage.getItem(REMEMBER_KEY) || '',
  password: '',
  remember: true,
})
const errors = reactive({ account: '', password: '' })
const topError = ref('')
const loading = ref(false)
const cooldown = ref(0)
let timer: number | undefined

const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const empNoRe = /^[A-Za-z]\w{2,49}$/

function validateAccount() {
  const a = form.account.trim()
  if (!a) errors.account = '请输入账号'
  else if (a.length > 50) errors.account = '账号不超过 50 字符'
  else if (!emailRe.test(a) && !empNoRe.test(a)) errors.account = '需为邮箱或员工工号格式'
  else errors.account = ''
  return !errors.account
}
function validatePassword() {
  const p = form.password
  if (!p) errors.password = '请输入密码'
  else if (p.length < 6 || p.length > 32) errors.password = '密码需 6-32 位'
  else if (!/[A-Za-z]/.test(p) || !/\d/.test(p)) errors.password = '密码需包含字母与数字'
  else errors.password = ''
  return !errors.password
}

const formValid = computed(() => !errors.account && !errors.password && form.account.trim() && form.password)

function startCooldown(seconds: number) {
  cooldown.value = seconds
  window.clearInterval(timer)
  timer = window.setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) window.clearInterval(timer)
  }, 1000)
}
onBeforeUnmount(() => window.clearInterval(timer))

async function submit() {
  topError.value = ''
  const okA = validateAccount()
  const okP = validatePassword()
  if (!okA || !okP) return
  loading.value = true
  try {
    const r = await login(form.account.trim(), form.password)
    store.setLoginResult(r)
    if (form.remember) localStorage.setItem(REMEMBER_KEY, form.account.trim())
    else localStorage.removeItem(REMEMBER_KEY)
    ElMessage.success(`欢迎回来，${r.user.name}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/chat'
    router.replace(redirect)
  } catch (e) {
    if (e instanceof HttpError) {
      if (e.status === 429) {
        startCooldown(e.retryAfter || 60)
        topError.value = e.message
      } else if (e.status === 0) {
        topError.value = '网络异常，请稍后重试'
      } else {
        topError.value = e.message // 401：账号或密码不正确
      }
    } else {
      topError.value = '登录失败，请稍后重试'
    }
  } finally {
    loading.value = false
  }
}

function forgot() {
  ElMessage.info('请联系企业 IT 管理员重置密码')
}
</script>

<style scoped>
.login {
  display: flex;
  height: 100%;
}
.login__brand {
  flex: 1;
  background: linear-gradient(160deg, #0e936b 0%, var(--brand) 55%, #3ecfa0 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login__brand-inner {
  max-width: 420px;
  padding: 40px;
}
.login__logo {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.18);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 20px;
}
.login__product {
  font-size: 34px;
  margin: 0 0 6px;
  letter-spacing: 2px;
}
.login__slogan {
  font-size: 17px;
  opacity: 0.92;
  margin: 0 0 28px;
}
.login__features {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 14px;
  opacity: 0.95;
}
.login__features li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.login__panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}
.login__form-wrap {
  width: 360px;
  max-width: 92vw;
}
.login__welcome {
  font-size: 24px;
  margin: 0 0 4px;
}
.login__alert {
  margin: 12px 0;
}
.login__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 4px 0 16px;
}
.login__forgot {
  font-size: var(--font-aux);
}
.login__submit {
  width: 100%;
}
.login__foot {
  margin-top: 28px;
  text-align: center;
}
@media (max-width: 900px) {
  .login__brand { display: none; }
}
</style>
