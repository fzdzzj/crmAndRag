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
    test: {
      // e2e 目录由 Playwright 执行，Vitest 全量运行时排除，避免误收 *.spec.ts
      exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    },
  };
});

