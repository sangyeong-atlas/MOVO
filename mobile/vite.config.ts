import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig(({mode})=>{const env=loadEnv(mode,'.','MOVO_');return {plugins:[react()],build:{rollupOptions:{output:{manualChunks:{map:['leaflet'],react:['react','react-dom','react-dom/server']}}}},server:{port:5173,proxy:{'/api':{target:env.MOVO_API_TARGET||'http://127.0.0.1:8787',rewrite:p=>p.replace(/^\/api/,'')}}}}});
