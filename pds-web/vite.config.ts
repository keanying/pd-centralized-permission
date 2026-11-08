import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

/**
 * 构建期只决定访问路径（VITE_BASE）与输出目录（PDS_WEB_OUT_DIR）：
 *   - 单独部署：pnpm build        → base=/          输出 dist/
 *   - 随 PDS 发布：pnpm build:pds → base=/console/  输出到 pds-server（见 .env.pds）
 * 其余配置（PDS 地址、客户端、接口地址、标题）全部在运行时 config.js 中，同一份产物可用于任意环境。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const pds = env.PDS_DEV_TARGET || 'http://127.0.0.1:9000'
  return {
    base: env.VITE_BASE || '/',
    plugins: [vue(), tailwindcss()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        // View UI Plus 图标字体目录（主题 less 中引用）
        '@viewui-fonts': fileURLToPath(new URL('./node_modules/view-ui-plus/src/styles/common/iconfont/fonts', import.meta.url)),
      },
    },
    css: { preprocessorOptions: { less: { javascriptEnabled: true } } },
    build: {
      outDir: env.PDS_WEB_OUT_DIR || 'dist',
      emptyOutDir: true,
      chunkSizeWarningLimit: 2000,
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      // 开发时把接口与令牌端点代理到本地 PDS（同源访问），并去掉 Origin 头使其按同源处理
      proxy: Object.fromEntries(['/api', '/oauth2/token'].map((path) => [path, {
        target: pds,
        configure: (proxy: any) => proxy.on('proxyReq', (req: any) => req.removeHeader('origin')),
      }])),
    },
  }
})
