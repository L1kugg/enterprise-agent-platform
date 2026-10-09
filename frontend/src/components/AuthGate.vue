<template>
  <!-- 登录门闩：未登录（无 token 且无 API Key）时整页显示登录/注册，登录后才渲染控制台 -->
  <div v-if="!canUseRemoteSync" class="auth-gate">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="auth-mark">K</span>
        <div class="auth-heading">
        <p class="eyebrow">Enterprise Agent Platform</p>
        <h1>{{ authMode === 'login' ? '登录控制台' : '注册新账号' }}</h1>
        </div>
      </div>
      <el-tabs v-model="authMode">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-input
        v-model="authUsername"
        placeholder="用户名：3-32 位小写字母、数字、- 或 _"
        @keyup.enter="handlePasswordAuth"
      />
      <el-input
        v-model="authPassword"
        type="password"
        show-password
        :placeholder="authMode === 'login' ? '密码' : '密码（至少 8 位）'"
        @keyup.enter="handlePasswordAuth"
      />
      <el-button
        type="primary"
        class="auth-submit"
        :loading="authLoading"
        @click="handlePasswordAuth"
        >{{ authMode === 'login' ? '登录' : '注册并登录' }}</el-button
      >
      <details class="admin-key-login">
        <summary>管理员 API Key 登录</summary>
        <el-input
          v-model="apiKeyInput"
          type="password"
          show-password
          placeholder="X-API-Key（管理员用）"
        />
        <el-input v-model="tenantInput" placeholder="租户（默认 public）" />
        <el-button :loading="authLoading" @click="handleLogin">换取 JWT</el-button>
      </details>
    </div>
  </div>
</template>

<script setup lang="ts">
// 登录/注册门闩：模板与样式从 App.vue 原文搬入，行为零变化。
// 状态来自 useAuthState 单例、动作来自 useAuthActions，无需 props/emits。
import {
  apiKeyInput,
  authLoading,
  authMode,
  authPassword,
  authUsername,
  canUseRemoteSync,
  tenantInput,
} from '../composables/useAuthState';
import { handleLogin, handlePasswordAuth } from '../composables/useAuthActions';
</script>

<style scoped>
.auth-gate {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.auth-card {
  width: min(392px, 100%);
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 30px 28px 26px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-card) 92%, transparent);
  backdrop-filter: blur(14px);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.06),
    0 16px 40px rgba(15, 23, 42, 0.1);
  overflow: hidden;
}

/* 顶部品牌发丝线：比渐变横幅克制，但让卡片有识别度 */
.auth-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: linear-gradient(90deg, var(--ui-accent-strong), var(--ui-accent));
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.auth-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--ui-accent-strong);
  color: #fff;
  font-size: 20px;
  font-weight: 800;
  box-shadow: 0 6px 14px color-mix(in oklab, var(--ui-accent-strong) 32%, transparent);
}

.auth-heading {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.auth-card .eyebrow {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

.auth-card h1 {
  margin: 0;
  font-size: 21px;
  letter-spacing: -0.01em;
  color: var(--ui-text);
}

.auth-submit {
  width: 100%;
  --el-button-size: 40px;
  font-weight: 600;
}

:deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--ui-border);
}

:deep(.el-tabs__item) {
  font-weight: 600;
}


.admin-key-login summary {
  font-size: 13px;
  color: var(--ui-muted);
  cursor: pointer;
}

.admin-key-login {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.admin-key-login[open] {
  padding-top: 10px;
  border-top: 1px dashed var(--ui-border);
}
</style>
