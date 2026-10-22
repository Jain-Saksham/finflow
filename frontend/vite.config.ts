/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the API is proxied, so the browser talks to one origin and no CORS is involved.
const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/v3/api-docs': backend,
      '/swagger-ui': backend,
    },
  },
  test: {
    environment: 'node',
  },
});
