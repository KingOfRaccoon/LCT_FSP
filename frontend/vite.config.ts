import { defineConfig } from 'vite';
import { offlineMockPlugin } from './vite-mock-plugin';

export default defineConfig({
  plugins: [offlineMockPlugin()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8000' },
  },
});
