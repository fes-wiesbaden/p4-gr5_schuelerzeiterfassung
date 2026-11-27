import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./test/setup.ts'],
    // Jeder Test beginnt ohne Ersatz-Globals und mit echten Methoden statt Spies
    unstubGlobals: true,
    restoreMocks: true
  }
})
