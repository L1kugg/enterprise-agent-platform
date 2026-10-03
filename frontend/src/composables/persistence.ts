// 持久化注册表：从 App.vue 单体拆出。
// 各功能模块在模块顶层调用 registerPersistSlice 注册自己的持久化切片（getter
// 返回 {字段: 值}），persistState() 汇总所有切片写入单个 localStorage blob。
// persistence.ts 不反向导入任何功能模块——依赖方向永远是功能模块 → 这里，
// 因此不会成环。
//
// ⚠ 切片注册顺序 × 切片内字段顺序 = JSON key 顺序 = 历史 blob 的字节布局。
// 字段顺序必须与拆分前 persistState 的对象字面量一致（拆分验收标准之一是
// localStorage blob 逐字节不变，保证老用户数据无损）。
import { LEGACY_STORAGE_KEY, STORAGE_KEY } from '../utils/constants';
import { safeParse } from '../utils/dom';

type PersistSlice = () => Record<string, unknown>;

const slices: PersistSlice[] = [];
let cachedBootstrap: Record<string, unknown> | null = null;

/** 功能模块在模块顶层调用一次，注册自己的持久化字段切片。 */
export function registerPersistSlice(slice: PersistSlice): void {
  slices.push(slice);
}

/** 读启动引导数据：优先新 key，为空回退老 key（老用户一次性迁移）。整个生命周期只读一次。 */
export function readBootstrap(): Record<string, unknown> {
  if (cachedBootstrap === null) {
    const cached = safeParse(localStorage.getItem(STORAGE_KEY));
    const legacy = safeParse(localStorage.getItem(LEGACY_STORAGE_KEY));
    cachedBootstrap = Object.keys(cached).length > 0 ? cached : legacy;
  }
  return cachedBootstrap;
}

/** 把所有注册切片按注册顺序合并写入 localStorage（函数名与拆分前保持一致）。 */
export function persistState(): void {
  const blob: Record<string, unknown> = {};
  slices.forEach((slice) => {
    Object.assign(blob, slice());
  });
  localStorage.setItem(STORAGE_KEY, JSON.stringify(blob));
}
