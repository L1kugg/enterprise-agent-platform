// 未保存修改守卫：各编辑页（Studio）注册脏检查，提供三类提示——
// ① 切换页签确认（useViewActivation 的 activateView 调 confirmLeaveUnsaved）；
// ② 关闭/刷新浏览器（beforeunload，模块内懒安装）；
// ③ 页内切换条目（各 Studio 自己调 confirmLeaveUnsaved）。
// 注册表是模块级单例：Studio 挂载注册、卸载注销，App.vue 无需感知各页内部状态。
import { ElMessageBox } from 'element-plus';

type UnsavedChecker = () => boolean;

const checkers = new Set<UnsavedChecker>();
let beforeUnloadInstalled = false;

/** 注册脏检查（组件挂载时调用；回调返回 true = 有未保存修改）。 */
export function registerUnsavedCheck(checker: UnsavedChecker): void {
  checkers.add(checker);
  installBeforeUnload();
}

/** 注销脏检查（组件卸载时调用，避免已卸载组件的状态误报）。 */
export function unregisterUnsavedCheck(checker: UnsavedChecker): void {
  checkers.delete(checker);
}

/** 任一注册页有未保存修改则 true。 */
export function hasUnsavedChanges(): boolean {
  for (const checker of checkers) {
    if (checker()) {
      return true;
    }
  }
  return false;
}

/**
 * 有未保存修改时弹确认框；用户确认离开（丢弃）返回 true，取消留在本页返回 false；
 * 无修改直接放行。切页签/切条目统一走这一个文案入口。
 */
export async function confirmLeaveUnsaved(
  message = '当前有未保存的修改，离开将丢失这些修改。确定离开？',
): Promise<boolean> {
  if (!hasUnsavedChanges()) {
    return true;
  }
  try {
    await ElMessageBox.confirm(message, '未保存的修改', {
      confirmButtonText: '丢弃修改并离开',
      cancelButtonText: '留在本页',
      type: 'warning',
    });
    return true;
  } catch {
    return false;
  }
}

function installBeforeUnload(): void {
  if (beforeUnloadInstalled) {
    return;
  }
  beforeUnloadInstalled = true;
  window.addEventListener('beforeunload', (event) => {
    if (hasUnsavedChanges()) {
      // 现代浏览器忽略自定义文案，只按存在 returnValue 显示原生确认
      event.preventDefault();
      event.returnValue = '';
    }
  });
}
