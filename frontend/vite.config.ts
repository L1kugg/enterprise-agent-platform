import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue'],
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-markdown': ['marked', 'dompurify', 'highlight.js']
        }
      }
    }
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        // 默认本地后端；联调生产/远端环境时设 VITE_PROXY_TARGET 覆盖（如 http://82.157.60.115:8088）
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (requestPath) => requestPath.replace(/^\/api/, '')
      }
    }
  }
});
