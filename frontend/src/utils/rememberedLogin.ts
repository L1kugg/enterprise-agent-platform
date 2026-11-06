// 登录页"记住账号 / 记住密码"：独立 localStorage 键，不进控制台状态 blob——
// 必须扛住登出与 401 清态（这正是"下次打开登录页还在"的语义）。
// 账号每次登录成功都记（非敏感）；密码只在勾选"记住密码"时保存，不勾选登录即清除。
// 密码的 base64 编码只是防肉眼一瞥的混淆，不是加密：任何同源脚本都读得到。
// 会话凭据本身在 HttpOnly Cookie 里不受影响，但存下的密码是可复用凭据——公用电脑不要勾选。

const STORAGE_KEY = 'kops_remembered_login';

interface StoredLogin {
  username?: string;
  password?: string;
}

export interface RememberedLogin {
  username: string;
  /** 空串 = 未记住密码 */
  password: string;
}

function encode(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function decode(value: string): string {
  const binary = atob(value);
  return new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)));
}

/** 读取记住的登录信息；存储损坏/不存在时返回空值，不影响登录页。 */
export function loadRememberedLogin(): RememberedLogin {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return { username: '', password: '' };
    }
    const stored = JSON.parse(raw) as StoredLogin;
    return {
      username: typeof stored.username === 'string' ? stored.username : '',
      password: typeof stored.password === 'string' && stored.password ? decode(stored.password) : '',
    };
  } catch {
    localStorage.removeItem(STORAGE_KEY);
    return { username: '', password: '' };
  }
}

/** 登录成功后调用：账号恒记；password 为 null 表示不记住密码（同时清掉旧的）。 */
export function saveRememberedLogin(username: string, password: string | null): void {
  const stored: StoredLogin = { username };
  if (password) {
    stored.password = encode(password);
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
}
