import { defineConfig } from 'vite';
import { env } from 'node:process';
import { offlineMockPlugin } from './vite-mock-plugin';

const offlineDemo = env.FSP_OFFLINE_DEMO === '1';

export default defineConfig({
  plugins: offlineDemo ? [offlineMockPlugin()] : [],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8000' },
  },
});
