import { defineConfig, devices } from '@playwright/test'

// Testes E2E contra o backend real (runDev) servindo o build do PWA.
// Usa porta e banco embarcado próprios, recriados a cada execução, para não
// tocar nos dados de desenvolvimento em backend/data/.
const PORT = Number(process.env.E2E_PORT ?? 7071)
const baseURL = `http://localhost:${PORT}`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    // Celular com toque e tela pequena (Chromium emulando um Pixel 7).
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'npm run build && cd ../backend && ./gradlew runDev --no-daemon -q',
    url: `${baseURL}/api/health`,
    timeout: 240_000,
    reuseExistingServer: !process.env.CI,
    env: {
      PORT: String(PORT),
      PUBLIC_BASE_URL: baseURL,
      DEV_PG_DIR: './build/e2e-pg',
      DEV_PG_CLEAN: 'true',
      DEV_PG_PORT: String(PORT + 47000),
      RATE_LIMIT_STORE: 'memory',
      // A suíte inteira faz login dezenas de vezes do mesmo IP.
      RATE_LIMIT_OTP_PER_IP: '1000',
    },
  },
})
