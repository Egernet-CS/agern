import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = process.env.FACTORY_BACKEND ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: false, ws: true },
      '/mcp': { target: backend, changeOrigin: false },
    },
  },
  build: {
    target: 'es2022',
    // No source maps in production builds: they would ship the full source with the release jar.
    sourcemap: false,
  },
});
