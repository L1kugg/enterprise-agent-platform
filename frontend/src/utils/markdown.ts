// Markdown 渲染管线（代码高亮 + 消毒）：从 App.vue 单体拆出，实现逐字一致。
// ⚠ 本模块顶层有副作用（hljs.registerLanguage / marked.use / DOMPurify.addHook），
// 在首次 import 时执行一次；renderMarkdown 被模板直接引用，不会被 tree-shake 掉。
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bashLang from 'highlight.js/lib/languages/bash';
import javaLang from 'highlight.js/lib/languages/java';
import javascriptLang from 'highlight.js/lib/languages/javascript';
import jsonLang from 'highlight.js/lib/languages/json';
import markdownLang from 'highlight.js/lib/languages/markdown';
import pythonLang from 'highlight.js/lib/languages/python';
import sqlLang from 'highlight.js/lib/languages/sql';
import typescriptLang from 'highlight.js/lib/languages/typescript';
import xmlLang from 'highlight.js/lib/languages/xml';
import yamlLang from 'highlight.js/lib/languages/yaml';
import { marked } from 'marked';

import { escapeHtml, toBase64 } from './dom';

hljs.registerLanguage('bash', bashLang);
hljs.registerLanguage('java', javaLang);
hljs.registerLanguage('javascript', javascriptLang);
hljs.registerLanguage('json', jsonLang);
hljs.registerLanguage('markdown', markdownLang);
hljs.registerLanguage('python', pythonLang);
hljs.registerLanguage('sql', sqlLang);
hljs.registerLanguage('typescript', typescriptLang);
hljs.registerLanguage('xml', xmlLang);
hljs.registerLanguage('yaml', yamlLang);

const renderer = new marked.Renderer();
renderer.code = ((token: { text: string; lang?: string }) => {
  const rawCode = token.text ?? '';
  const lang = token.lang?.trim().toLowerCase().split(/\s+/)[0] ?? 'plaintext';
  const language = hljs.getLanguage(lang) ? lang : 'plaintext';
  const highlighted =
    language === 'plaintext'
      ? escapeHtml(rawCode)
      : hljs.highlight(rawCode, { language, ignoreIllegals: true }).value;

  const lines = highlighted.split('\n');
  const numbered = lines
    .map((line, index) => {
      const content = line || '&nbsp;';
      return `<span class="code-line"><span class="line-no">${index + 1}</span><span class="line-content">${content}</span></span>`;
    })
    .join('');

  const payload = escapeHtml(toBase64(rawCode));

  return `<div class="code-block"><div class="code-toolbar"><span class="code-lang">${language}</span><button class="copy-code-btn" type="button" data-code="${payload}">复制代码</button></div><pre><code class="hljs language-${language}">${numbered}</code></pre></div>`;
}) as typeof renderer.code;

marked.use({
  gfm: true,
  breaks: true,
  renderer,
});

// 模块级钩子：对 LLM 输出中渲染出的所有带 target="_blank" 的链接
// 强制添加 rel="noopener noreferrer"。否则被提示词注入的响应可能
// 打开新标签页，新标签页的 JS 就能回访原页面的 window.opener.location
// （反向 tabnabbing 攻击）。该钩子在模块加载时注册一次；DOMPurify 的
// 钩子按事件名作为键并会覆盖先前的注册，因此在多次重渲染间是安全的。
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.getAttribute('target') === '_blank') {
    const existing = (node.getAttribute('rel') || '').toLowerCase();
    const merged = new Set(existing.split(/\s+/).filter(Boolean));
    merged.add('noopener');
    merged.add('noreferrer');
    node.setAttribute('rel', Array.from(merged).join(' '));
  }
});

export function renderMarkdown(content: string): string {
  if (!content?.trim()) {
    return '<p>等待模型输出...</p>';
  }
  const html = marked.parse(content) as string;
  // 纵深防御：显式禁止内联事件处理器、javascript: URL 以及
  // 未带 rel=noopener 的 target=_blank。DOMPurify 本身已清除
  // 危险形式（script、onerror、javascript:），但默认配置会保留
  // target 等少数属性，这足以让 LLM 输出渲染出的链接
  // 成为反向 tabnabbing 攻击的入口。
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['data-code'],
    ALLOWED_ATTR: [
      'href',
      'title',
      'alt',
      'src',
      'name',
      'target',
      'rel',
      'class',
      'id',
      'data-code',
      'data-line',
      'colspan',
      'rowspan',
      'align',
    ],
    FORBID_ATTR: ['style', 'onload', 'onclick', 'onerror', 'onmouseover'],
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input'],
  });
}
