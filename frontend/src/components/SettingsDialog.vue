<template>
  <!-- 鉴权与模型：低频配置收进弹窗。按"当前身份 → 管理员入口 → 对话偏好 → 外观 → 危险操作"分区 -->
  <el-dialog v-model="opsDialogVisible" title="鉴权与模型" width="min(520px, 92vw)">
    <div class="ops-body">
      <!-- 当前身份：最常被确认的信息放最上面 -->
      <section class="settings-section">
        <div class="identity-card">
          <div class="identity-avatar" :class="{ admin: isAdmin }">{{ isAdmin ? 'A' : 'U' }}</div>
          <div class="identity-main">
            <p class="identity-line">
              <span class="identity-key">租户</span>
              <span class="identity-value">{{ tenantInput || 'public' }}</span>
              <el-tag v-if="role" size="small" :type="isAdmin ? 'danger' : 'info'">{{ role }}</el-tag>
            </p>
            <p class="identity-line">
              <span class="identity-key">访问令牌</span>
              <span class="identity-value" :class="tokenToneClass">{{ tokenStatusText }}</span>
            </p>
          </div>
        </div>
      </section>

      <!-- 管理员 API Key 登录：折叠收起，普通用户不占注意力 -->
      <section class="settings-section">
        <button
          type="button"
          class="section-toggle"
          @click="apiKeySectionOpen = !apiKeySectionOpen"
        >
          <el-icon :size="12" class="toggle-icon" :class="{ open: apiKeySectionOpen }">
            <CaretRight />
          </el-icon>
          管理员 API Key 登录
        </button>
        <div v-if="apiKeySectionOpen" class="api-key-panel">
          <el-form label-position="top" size="small">
            <el-form-item label="API Key">
              <el-input
                v-model="apiKeyInput"
                placeholder="输入 API Key（生产建议短时使用）"
                show-password
                type="password"
              />
            </el-form-item>
            <el-form-item label="Tenant（可选）">
              <el-input v-model="tenantInput" placeholder="public" />
            </el-form-item>
          </el-form>
          <el-button type="primary" size="small" :loading="authLoading" @click="handleLogin"
            >换取 JWT</el-button
          >
        </div>
      </section>

      <!-- 对话偏好 -->
      <section class="settings-section">
        <p class="section-label">对话偏好</p>
        <el-form label-position="top" size="small">
          <el-form-item label="模型档位">
            <el-select v-model="modelProfile" class="full-width">
              <el-option label="economy（经济档）" value="economy" />
              <el-option label="balanced（均衡档）" value="balanced" />
              <el-option label="quality（质量档）" value="quality" />
              <el-option label="ab_auto（A/B 自动实验）" value="ab_auto" />
              <el-option label="quality_first（固定最高档）" value="quality_first" />
              <el-option label="cost_first（固定最低档）" value="cost_first" />
            </el-select>
            <p class="field-hint">{{ PROFILE_HINTS[modelProfile] ?? '' }}</p>
          </el-form-item>
          <el-form-item label="Agent 引擎（主聊天）">
            <el-radio-group v-model="agentEngine">
              <el-radio-button value="standard">标准 ReAct</el-radio-button>
              <el-radio-button value="workflow">工作流引擎</el-radio-button>
            </el-radio-group>
            <p class="field-hint">{{ ENGINE_HINTS[agentEngine] ?? '' }}</p>
          </el-form-item>
          <el-form-item label="响应模式">
            <div class="switch-row">
              <el-switch v-model="streaming" inline-prompt active-text="SSE" inactive-text="JSON" />
              <span class="field-hint inline">{{
                streaming ? '流式输出：回答逐字推送，首字更快' : '整包返回：等待完整回答后一次显示'
              }}</span>
            </div>
          </el-form-item>
        </el-form>
      </section>

      <!-- 外观 -->
      <section class="settings-section">
        <p class="section-label">外观</p>
        <div class="switch-row">
          <el-switch v-model="darkMode" inline-prompt active-text="Dark" inactive-text="Light" />
          <span class="field-hint inline">{{
            darkMode ? '暗色：低光环境更护眼' : '亮色：白天使用更清晰'
          }}</span>
        </div>
      </section>

      <!-- 底部操作：令牌续期与退出分开，退出是危险操作 -->
      <div class="settings-footer">
        <el-button
          size="small"
          :disabled="!refreshToken"
          :loading="refreshing"
          @click="handleRefresh(false)"
          >刷新访问令牌</el-button
        >
        <el-button size="small" type="danger" plain @click="logout">退出登录</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
// 设置弹窗：当前身份卡片 + 折叠式管理员入口 + 带说明的对话偏好。
// 令牌倒计时只在弹窗打开时计时，关闭即停，不常驻后台定时器。
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { CaretRight } from '@element-plus/icons-vue';
import { darkMode, opsDialogVisible } from '../composables/useGlobalUi';
import { agentEngine, modelProfile, streaming } from '../composables/useChatState';
import {
  apiKeyInput,
  authLoading,
  isAdmin,
  refreshToken,
  refreshing,
  role,
  tenantInput,
  token,
  tokenExpiresAt,
} from '../composables/useAuthState';
import { handleLogin, handleRefresh, logout } from '../composables/useAuthActions';

const apiKeySectionOpen = ref(false);

const PROFILE_HINTS: Record<string, string> = {
  economy: '经济档 · qwen-turbo：速度最快、成本最低，适合日常问答',
  balanced: '均衡档 · qwen-plus：质量与成本均衡，日常使用推荐',
  quality: '质量档 · qwen-max：最强推理质量，复杂问题选用',
  ab_auto: 'A/B 自动实验：按租户分桶自动路由，用于效果对比',
  quality_first: '固定最高档：所有请求强制走质量档',
  cost_first: '固定最低档：所有请求强制走经济档',
};

const ENGINE_HINTS: Record<string, string> = {
  standard: '标准 ReAct：主聊天默认引擎，带会话记忆。',
  workflow: '工作流引擎：每一步全留痕可回放、轨迹逐轮实时推送；但不读写会话记忆。',
};

// 令牌剩余时间：弹窗打开期间每 30 秒刷新一次显示
const nowMs = ref(Date.now());
let tokenTimer: number | undefined;

watch(opsDialogVisible, (open) => {
  if (open) {
    nowMs.value = Date.now();
    tokenTimer = window.setInterval(() => {
      nowMs.value = Date.now();
    }, 30_000);
  } else if (tokenTimer !== undefined) {
    window.clearInterval(tokenTimer);
    tokenTimer = undefined;
  }
});

onBeforeUnmount(() => {
  if (tokenTimer !== undefined) window.clearInterval(tokenTimer);
});

const tokenRemainingMs = computed(() =>
  tokenExpiresAt.value > 0 ? tokenExpiresAt.value - nowMs.value : 0,
);

const tokenStatusText = computed(() => {
  if (!token.value) return '未登录';
  if (tokenRemainingMs.value <= 0) return '已过期，请刷新';
  const minutes = Math.floor(tokenRemainingMs.value / 60_000);
  if (minutes < 1) return '即将过期（不足 1 分钟）';
  if (minutes < 60) return `${minutes} 分钟后过期`;
  const hours = Math.floor(minutes / 60);
  return `${hours} 小时 ${minutes % 60} 分后过期`;
});

const tokenToneClass = computed(() =>
  !token.value || tokenRemainingMs.value <= 0
    ? 'tone-danger'
    : tokenRemainingMs.value < 5 * 60_000
      ? 'tone-warn'
      : 'tone-ok',
);
</script>

<style scoped>
.ops-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 2px 2px 0;
}

.settings-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.settings-section + .settings-section {
  border-top: 1px solid var(--ui-border);
  padding-top: 14px;
}

.section-label {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

/* 身份卡片 */
.identity-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: color-mix(in oklab, var(--ui-panel) 75%, transparent);
}

.identity-avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--ui-accent-strong);
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  flex-shrink: 0;
}

.identity-avatar.admin {
  background: #b45309;
}

.identity-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.identity-line {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.identity-key {
  font-size: 11px;
  color: var(--ui-muted);
  flex-shrink: 0;
}

.identity-value {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tone-ok {
  color: var(--ui-accent-strong);
}

.tone-warn {
  color: #b45309;
}

.tone-danger {
  color: #dc2626;
}

/* 折叠式管理员入口 */
.section-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  padding: 2px 0;
  color: var(--ui-muted);
  font-size: 13px;
  cursor: pointer;
}

.section-toggle:hover {
  color: var(--ui-text);
}

.toggle-icon {
  transition: transform 160ms ease;
}

.toggle-icon.open {
  transform: rotate(90deg);
}

.api-key-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  border: 1px dashed var(--ui-border);
  border-radius: 10px;
}

/* 字段说明 */
.field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-muted);
}

.field-hint.inline {
  margin: 0;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* 底部操作 */
.settings-footer {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  border-top: 1px solid var(--ui-border);
  padding-top: 14px;
}

.full-width {
  width: 100%;
}

@media (max-width: 520px) {
  .settings-footer {
    flex-direction: column-reverse;
  }

  .settings-footer .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>