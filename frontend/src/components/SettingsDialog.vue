<template>
  <!-- 鉴权与模型：低频配置收进弹窗（原侧栏 ops-panel） -->
  <el-dialog v-model="opsDialogVisible" title="鉴权与模型" width="520px">
    <div class="ops-body">
      <el-form label-position="top" size="small">
        <el-form-item label="API Key">
          <el-input
            v-model="apiKeyInput"
            placeholder="输入 API Key（生产建议短时使用）"
            show-password
            type="password"
          />
        </el-form-item>
        <el-form-item label="Tenant (可选)">
          <el-input v-model="tenantInput" placeholder="public" />
        </el-form-item>
        <el-form-item label="Model Profile">
          <el-select v-model="modelProfile" class="full-width">
            <el-option label="economy（经济档 qwen-turbo）" value="economy" />
            <el-option label="balanced（均衡档 qwen-plus）" value="balanced" />
            <el-option label="quality（质量档 qwen-max）" value="quality" />
            <el-option label="ab_auto（A/B 自动对比实验）" value="ab_auto" />
            <el-option label="quality_first（固定最高档）" value="quality_first" />
            <el-option label="cost_first（固定最低档）" value="cost_first" />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent 引擎（主聊天）">
          <el-radio-group v-model="agentEngine">
            <el-radio-button value="standard">标准 ReAct</el-radio-button>
            <el-radio-button value="workflow">工作流引擎</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <p class="engine-hint">
          {{
            agentEngine === 'workflow'
              ? '工作流引擎：每一步全留痕可回放、轨迹逐轮实时推送；但不读写会话记忆。'
              : '标准 ReAct：主聊天默认引擎，带会话记忆。'
          }}
        </p>
        <el-form-item label="响应模式">
          <el-switch v-model="streaming" inline-prompt active-text="SSE" inactive-text="JSON" />
        </el-form-item>
        <el-form-item label="外观">
          <el-switch v-model="darkMode" inline-prompt active-text="Dark" inactive-text="Light" />
        </el-form-item>
      </el-form>
      <div class="auth-buttons">
        <el-tag v-if="role" size="small" :type="isAdmin ? 'danger' : 'info'">{{ role }}</el-tag>
        <el-button type="primary" :loading="authLoading" @click="handleLogin">换取 JWT</el-button>
        <el-button :disabled="!refreshToken" :loading="refreshing" @click="handleRefresh"
          >刷新</el-button
        >
        <el-button @click="logout">退出登录</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
// 鉴权与模型设置弹窗：模板与样式从 App.vue 原文搬入，行为零变化。
// 打开开关在 useGlobalUi（图标栏"设置"按钮直接置 true），状态/动作来自
// useAuthState + useChatState + useAuthActions 单例，无需 props/emits。
import { darkMode, opsDialogVisible } from '../composables/useGlobalUi';
import {
  agentEngine,
  modelProfile,
  streaming,
} from '../composables/useChatState';
import {
  apiKeyInput,
  authLoading,
  isAdmin,
  refreshToken,
  refreshing,
  role,
  tenantInput,
} from '../composables/useAuthState';
import { handleLogin, handleRefresh, logout } from '../composables/useAuthActions';
</script>

<style scoped>
/* 鉴权与模型住在弹窗里，只保留表单体样式 */
.ops-body {
  padding: 4px 2px 2px;
}

.auth-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.full-width {
  width: 100%;
}

.engine-hint {
  margin: -6px 0 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-muted);
}
</style>
