import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';
import tailwindcss from '@tailwindcss/vite';
import  VueRouter  from 'unplugin-vue-router/vite';
function getEnvConfig(key:string){
  const env = process.env;
  const dotenv = loadEnv('',process.cwd(),'')
  return dotenv[key] || env[key]
}
// https://vite.dev/config/
export default defineConfig(() => {
  return {
    base: '/',
    plugins: [
      VueRouter({
        routesFolder: './src/pages',
        extensions: ['.page.vue'],
        dts: './typed-router.d.ts',
      }),
      tailwindcss(),
      vue(),
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    server:{
      proxy:{
        "/api":{
          target: getEnvConfig('BASE_API') || 'http://localhost:8080',
          changeOrigin:true,
          rewrite:(path)=>path.replace(/^\/api/,'')
        }
      }
    },
    build: {
      // sourcemap 策略：生产构建默认不出 map。
      // 取舍——dist 由 nginx 原样静态托管（deploy/nginx.conf 无鉴权），带 sourceMappingURL 就等于
      // 把未压缩源码结构公开；而且 .map 通常是 js 的 1.5~2 倍体积，纯吃带宽。
      // 线上要复现压缩栈时显式 opt-in：`VITE_SOURCEMAP=hidden pnpm build` —— hidden 会生成 .map
      // 但不写 sourceMappingURL 注释，不进浏览器加载路径，也不进 gzip 预算口径
      // （check-bundle-budget.mjs 只统计 js/mjs/css）。
      sourcemap: getEnvConfig('VITE_SOURCEMAP') === 'hidden' ? 'hidden' : false,
      rollupOptions: {
        output: {
          // vendor 三分：ant-design-vue / echarts / 其余第三方各自成组。
          // 选函数式而不是对象式：对象式只搬显式列出的那几个包名，它们的依赖
          // （@ant-design/icons-vue、zrender、@tanstack/vue-query…）仍按引用图被 Rollup
          // 甩到别的 chunk 里，分桶大小不可控，预算阈值会天天漂。
          // 三个桶名与 scripts/check-bundle-budget.mjs 的 VENDOR_BUCKETS 一一对应，改一边必须改另一边。
          // 只对 node_modules 生效、不手动分应用代码，避免 vendor chunk 反向依赖 route chunk 造成
          // 初始化顺序问题（Rollup 的经典 manualChunks 坑）。
          manualChunks(id: string) {
            if (!id.includes('node_modules')) return undefined;
            if (/node_modules[\\/](ant-design-vue|@ant-design)[\\/]/.test(id)) return 'vendor-antd';
            if (/node_modules[\\/](echarts|zrender)[\\/]/.test(id)) return 'vendor-echarts';
            return 'vendor';
          },
        },
      },
    },
    test: {
      // e2e 目录由 Playwright 执行，Vitest 全量运行时排除，避免误收 *.spec.ts
      exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    },
  };
});

