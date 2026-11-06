<template>
  <!-- 登录门闩：未登录（无服务端会话）时整页显示登录/注册，登录后才渲染控制台 -->
  <div v-if="!canUseRemoteSync" class="auth-gate">
    <!-- Agent 运行时氛围背景：工程网格/点阵/雷达扫描/电路神经网络（全部 aria-hidden，不进可访问树） -->
    <div class="backdrop-grid" aria-hidden="true"></div>
    <div class="backdrop-dots" aria-hidden="true"></div>
    <span class="backdrop-sweep" aria-hidden="true"></span>
    <svg
      class="backdrop-circuit"
      viewBox="0 0 1440 900"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      focusable="false"
    >
      <!-- 电路走线：淡色基线 -->
      <path class="trace" d="M -40 250 H 430 L 520 340 H 900 L 1000 250 H 1480" />
      <path class="trace" d="M -40 655 H 350 L 455 760 H 1020 L 1125 655 H 1480" />
      <path class="trace" d="M 1165 -40 V 175 L 1245 255 V 520 L 1165 600 V 940" />
      <path class="trace" d="M 275 -40 V 150 L 195 230 V 470" />
      <!-- 数据流：沿走线流动的短虚线，不同路径速度/延迟错开，形成"多 agent 并行"的节奏 -->
      <path class="flow flow-a" d="M -40 250 H 430 L 520 340 H 900 L 1000 250 H 1480" />
      <path class="flow flow-b" d="M -40 655 H 350 L 455 760 H 1020 L 1125 655 H 1480" />
      <path class="flow flow-c" d="M 1165 -40 V 175 L 1245 255 V 520 L 1165 600 V 940" />
      <path class="flow flow-d" d="M 275 -40 V 150 L 195 230 V 470" />
      <!-- 神经节点：走线折点处的脉冲圆点，错峰呼吸 -->
      <circle class="node" cx="430" cy="250" r="4" />
      <circle class="node" cx="900" cy="250" r="4" />
      <circle class="node" cx="520" cy="340" r="4" />
      <circle class="node" cx="350" cy="655" r="4" />
      <circle class="node" cx="1020" cy="760" r="4" />
      <circle class="node" cx="1245" cy="255" r="4" />
      <circle class="node" cx="195" cy="230" r="4" />
    </svg>
    <span class="backdrop-hud hud-left" aria-hidden="true">
      <i class="hud-dot"></i>enterprise agent runtime · online
    </span>
    <span class="backdrop-hud hud-right" aria-hidden="true">session · secured</span>
    <div class="auth-stage">
      <span class="stage-corner tl" aria-hidden="true"></span>
      <span class="stage-corner tr" aria-hidden="true"></span>
      <span class="stage-corner bl" aria-hidden="true"></span>
      <span class="stage-corner br" aria-hidden="true"></span>
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
        <el-checkbox v-model="rememberLogin" class="remember-row">记住密码（公用电脑勿勾选）</el-checkbox>
        <el-button
          type="primary"
          class="auth-submit"
          :loading="authLoading"
          @click="handlePasswordAuth"
          >{{ authMode === 'login' ? '登录' : '注册并登录' }}</el-button
        >
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 登录/注册门闩：未登录（无服务端会话）时整页显示登录/注册，登录后才渲染控制台。
// 程序化接入走 X-API-Key 直调后端，人的登录只有用户名密码一条路。
// 上次登录账号恒预填（非敏感）；勾选"记住密码"时密码以 base64 混淆存
// localStorage（便利级而非安全级，见 utils/rememberedLogin.ts 头注释）。
import { onMounted } from 'vue';
import {
  authLoading,
  authMode,
  authPassword,
  authUsername,
  canUseRemoteSync,
  rememberLogin,
} from '../composables/useAuthState';
import { handlePasswordAuth } from '../composables/useAuthActions';
import { loadRememberedLogin } from '../utils/rememberedLogin';

// 登录页挂载即预填：上次登录的账号恒填；记住了密码则连密码一起回填并勾选
onMounted(() => {
  const remembered = loadRememberedLogin();
  if (remembered.username) {
    authUsername.value = remembered.username;
  }
  if (remembered.password) {
    authPassword.value = remembered.password;
    rememberLogin.value = true;
  }
});
</script>

<style scoped>
.auth-gate {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  position: relative;
  overflow: hidden;
  /* Agent 运行时底：冷色渐变压底，氛围层（网格/点阵/扫描/电路/光晕）在其上叠加 */
  background: linear-gradient(165deg, #eef2f9 0%, #e6edf7 52%, #dde6f2 100%);
}

/* ---------- 氛围层 1：工程网格（径向遮罩，中心聚焦、边缘渐隐） ---------- */
.backdrop-grid {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(49, 93, 235, 0.1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(49, 93, 235, 0.1) 1px, transparent 1px);
  background-size: 42px 42px;
  -webkit-mask-image: radial-gradient(ellipse 72% 64% at 50% 46%, black 26%, transparent 78%);
  mask-image: radial-gradient(ellipse 72% 64% at 50% 46%, black 26%, transparent 78%);
}

/* ---------- 氛围层 2：网格交叉点阵，叠出"节点矩阵"的第二层密度 ---------- */
.backdrop-dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, rgba(49, 93, 235, 0.22) 1px, transparent 1.7px);
  background-size: 42px 42px;
  background-position: 0 0;
  opacity: 0.5;
  -webkit-mask-image: radial-gradient(ellipse 76% 68% at 50% 46%, black 20%, transparent 74%);
  mask-image: radial-gradient(ellipse 76% 68% at 50% 46%, black 20%, transparent 74%);
}

/* ---------- 氛围层 3：雷达扫描束（conic 光扇缓慢旋转，Agent 在"巡检"） ---------- */
.backdrop-sweep {
  position: absolute;
  left: -18vmax;
  top: -22vmax;
  width: 64vmax;
  height: 64vmax;
  border-radius: 50%;
  background: conic-gradient(
    from 0deg,
    transparent 0deg,
    rgba(49, 93, 235, 0.09) 22deg,
    rgba(111, 142, 241, 0.05) 38deg,
    transparent 58deg,
    transparent 360deg
  );
  filter: blur(3px);
  animation: sweep-rotate 44s linear infinite;
  -webkit-mask-image: radial-gradient(closest-side, transparent 30%, black 48%, black 62%, transparent 82%);
  mask-image: radial-gradient(closest-side, transparent 30%, black 48%, black 62%, transparent 82%);
  pointer-events: none;
}

@keyframes sweep-rotate {
  to {
    transform: rotate(360deg);
  }
}

/* ---------- 氛围层 4：电路神经网络（正交走线 + 流动数据 + 脉冲节点） ---------- */
.backdrop-circuit {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.backdrop-circuit .trace {
  fill: none;
  stroke: rgba(49, 93, 235, 0.16);
  stroke-width: 1.2;
  vector-effect: non-scaling-stroke;
}

.backdrop-circuit .flow {
  fill: none;
  stroke: rgba(49, 93, 235, 0.5);
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-dasharray: 4 22;
  vector-effect: non-scaling-stroke;
  animation: circuit-flow 7s linear infinite;
}

.backdrop-circuit .flow-b {
  animation-duration: 9.5s;
  animation-delay: -2.4s;
}

.backdrop-circuit .flow-c {
  animation-duration: 11s;
  animation-delay: -5s;
}

.backdrop-circuit .flow-d {
  animation-duration: 8s;
  animation-delay: -3.8s;
}

@keyframes circuit-flow {
  from {
    stroke-dashoffset: 0;
  }
  to {
    stroke-dashoffset: -260; /* 260 是 dasharray 周期 26 的整数倍，循环无跳变 */
  }
}

.backdrop-circuit .node {
  fill: rgba(49, 93, 235, 0.55);
  transform-box: fill-box;
  transform-origin: center;
  animation: node-pulse 3.4s ease-in-out infinite;
}

.backdrop-circuit .node:nth-child(odd) {
  animation-delay: -1.2s;
}

.backdrop-circuit .node:nth-child(3n) {
  animation-delay: -2.3s;
}

@keyframes node-pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.45);
  }
}

/* ---------- 氛围层 5：两颗缓慢漂移的光晕（agent 运行中的呼吸感） ---------- */
.auth-gate::before,
.auth-gate::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
  filter: blur(28px);
}

.auth-gate::before {
  width: 46vw;
  height: 46vw;
  max-width: 680px;
  max-height: 680px;
  left: -8vw;
  top: -12vh;
  background: radial-gradient(circle, rgba(49, 93, 235, 0.18) 0%, transparent 62%);
  animation: auth-orb 24s ease-in-out infinite alternate;
}

.auth-gate::after {
  width: 42vw;
  height: 42vw;
  max-width: 600px;
  max-height: 600px;
  right: -7vw;
  bottom: -14vh;
  background: radial-gradient(circle, rgba(111, 142, 241, 0.17) 0%, transparent 62%);
  animation: auth-orb 30s ease-in-out infinite alternate-reverse;
}

@keyframes auth-orb {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
    opacity: 0.85;
  }
  50% {
    opacity: 1;
  }
  100% {
    transform: translate3d(9vw, 7vh, 0) scale(1.12);
    opacity: 0.9;
  }
}

/* ---------- 暗色主题：夜航模式，网格/电路/扫描整体提亮并注入蓝青极光 ---------- */
html.dark .auth-gate {
  background: linear-gradient(165deg, #0a111d 0%, #0c1424 52%, #091020 100%);
}

html.dark .backdrop-grid {
  background:
    linear-gradient(rgba(111, 142, 241, 0.13) 1px, transparent 1px),
    linear-gradient(90deg, rgba(111, 142, 241, 0.13) 1px, transparent 1px);
}

html.dark .backdrop-dots {
  background-image: radial-gradient(circle, rgba(111, 142, 241, 0.3) 1px, transparent 1.7px);
  opacity: 0.6;
}

html.dark .backdrop-sweep {
  background: conic-gradient(
    from 0deg,
    transparent 0deg,
    rgba(111, 142, 241, 0.13) 22deg,
    rgba(56, 189, 248, 0.07) 38deg,
    transparent 58deg,
    transparent 360deg
  );
}

html.dark .backdrop-circuit .trace {
  stroke: rgba(111, 142, 241, 0.22);
}

html.dark .backdrop-circuit .flow {
  stroke: rgba(111, 142, 241, 0.62);
}

html.dark .backdrop-circuit .node {
  fill: rgba(111, 142, 241, 0.68);
}

html.dark .auth-gate::before {
  background: radial-gradient(circle, rgba(111, 142, 241, 0.2) 0%, transparent 62%);
}

html.dark .auth-gate::after {
  background: radial-gradient(circle, rgba(49, 93, 235, 0.19) 0%, transparent 62%);
}

/* ---------- 角落 HUD 装饰：等宽小字 + 呼吸状态点，远看是仪表读数 ---------- */
.backdrop-hud {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-family: ui-monospace, 'Cascadia Mono', Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: color-mix(in oklab, var(--ui-muted) 62%, transparent);
  pointer-events: none;
  z-index: 1;
}

.hud-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ui-accent);
  box-shadow: 0 0 0 3px color-mix(in oklab, var(--ui-accent) 18%, transparent);
  animation: hud-blink 2.6s ease-in-out infinite;
}

@keyframes hud-blink {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.hud-left {
  left: 28px;
  bottom: 22px;
}

.hud-right {
  right: 28px;
  bottom: 22px;
}

/* ---------- 登录舞台：卡片 + 四角 HUD 取景框 ---------- */
.auth-stage {
  position: relative;
  z-index: 1;
  width: min(392px, 100%);
}

.stage-corner {
  position: absolute;
  width: 18px;
  height: 18px;
  border: 2px solid color-mix(in oklab, var(--ui-accent) 62%, transparent);
  pointer-events: none;
}

.stage-corner.tl {
  left: -13px;
  top: -13px;
  border-right: none;
  border-bottom: none;
  border-top-left-radius: 4px;
}

.stage-corner.tr {
  right: -13px;
  top: -13px;
  border-left: none;
  border-bottom: none;
  border-top-right-radius: 4px;
}

.stage-corner.bl {
  left: -13px;
  bottom: -13px;
  border-right: none;
  border-top: none;
  border-bottom-left-radius: 4px;
}

.stage-corner.br {
  right: -13px;
  bottom: -13px;
  border-left: none;
  border-top: none;
  border-bottom-right-radius: 4px;
}

.auth-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 30px 28px 26px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-card) 80%, transparent);
  backdrop-filter: blur(18px);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.06),
    0 16px 40px rgba(15, 23, 42, 0.12),
    inset 0 0 0 1px color-mix(in oklab, var(--ui-accent) 6%, transparent);
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

.remember-row {
  height: auto;
  margin: -6px 0 -4px;
  color: var(--ui-muted);
}

.remember-row :deep(.el-checkbox__label) {
  font-size: 12.5px;
}

:deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--ui-border);
}

:deep(.el-tabs__item) {
  font-weight: 600;
}

/* ---------- 减弱动效：所有氛围动画关闭，静态层次保留 ---------- */
@media (prefers-reduced-motion: reduce) {
  .auth-gate::before,
  .auth-gate::after,
  .backdrop-sweep,
  .backdrop-circuit .flow,
  .backdrop-circuit .node,
  .hud-dot {
    animation: none;
  }
}

</style>
