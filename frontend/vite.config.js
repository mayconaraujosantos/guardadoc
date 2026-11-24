import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

const backend = process.env.BACKEND_URL ?? 'http://localhost:7070'

export default defineConfig({
  build: {
    target: ['safari13', 'chrome87', 'firefox78', 'edge88'],
  },
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      // Script externo (registerSW.js): compatível com CSP sem 'unsafe-inline'.
      injectRegister: 'script',
      includeAssets: ['icon.svg', 'apple-touch-icon.png'],
      manifest: {
        id: '/',
        name: 'GuardaDoc',
        short_name: 'GuardaDoc',
        description: 'Guarde documentos pessoais e compartilhe por links com validade.',
        lang: 'pt-BR',
        dir: 'ltr',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        display_override: ['standalone', 'minimal-ui'],
        orientation: 'portrait',
        background_color: '#1e3a8a',
        theme_color: '#1e3a8a',
        categories: ['productivity', 'utilities'],
        icons: [
          { src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: 'icon-maskable-192.png', sizes: '192x192', type: 'image/png', purpose: 'maskable' },
          { src: 'icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
        // Atalhos ao manter pressionado o ícone do app (Android).
        shortcuts: [
          {
            name: 'Tirar foto de documento',
            short_name: 'Tirar foto',
            url: '/?acao=foto',
            icons: [{ src: 'icon-192.png', sizes: '192x192', type: 'image/png' }],
          },
          {
            name: 'Enviar arquivo',
            short_name: 'Enviar',
            url: '/?acao=enviar',
            icons: [{ src: 'icon-192.png', sizes: '192x192', type: 'image/png' }],
          },
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
