import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// 빌드 산출물(dist/)은 Gradle bootJar가 BOOT-INF/classes/static/으로 포함하고, bootRun은 dist/를 정적 경로로 추가해 제공한다(build.gradle).
// /api 프록시는 `npm run dev` 전용 — 제품에서는 같은 출처의 상대 경로(/api/...)를 쓴다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
});
