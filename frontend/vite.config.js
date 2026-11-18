import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

const backend = process.env.BACKEND_URL ?? 'http://localhost:7070'

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      // Script externo (registerSW.js): compatível com CSP sem 'unsafe-inline'.
      injectRegister: 'script',
      includeAssets: ['icon.svg'],
      manifest: {
        name: 'GuardaDoc',
        short_name: 'GuardaDoc',
        description: 'Guarde documentos pessoais e compartilhe por links com validade.',
        lang: 'pt-BR',
        start_url: '/',
        display: 'standalone',
        background_color: '#f1f5f9',
        theme_color: '#1e3a8a',
        icons: [
          { src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        // Somente o shell e os estáticos versionados do build entram no cache.
        globPatterns: ['**/*.{js,css,html,svg,png,webmanifest}'],
        navigateFallback: '/index.html',
        // API, páginas de compartilhamento e rotas de dev nunca passam pelo SW.
        navigateFallbackDenylist: [/^\/api\//, /^\/s\//, /^\/dev\//],
        runtimeCaching: [],
        cleanupOutdatedCaches: true,
      },
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/s': backend,
      '/dev': backend,
    },
  },
})
