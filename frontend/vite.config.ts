import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server:
    command === 'serve'
      ? {
          host: '0.0.0.0',
          https: {
            cert: readFileSync(
              new URL('../.local/tls/server.crt', import.meta.url)
            ),
            key: readFileSync(
              new URL('../.local/tls/server.key', import.meta.url)
            )
          },
          port: 5173,
          strictPort: true
        }
      : undefined
}))
