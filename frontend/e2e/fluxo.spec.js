import { expect, test } from '@playwright/test'
import { login, uniqueEmail, uploadPdf } from './helpers.js'

test('login, upload, compartilhamento e download pelo link', async ({ page, context }) => {
  await login(page, uniqueEmail('fluxo'))
  await uploadPdf(page, 'RG.pdf')

  await page.getByRole('button', { name: 'Compartilhar' }).click()
  await page.getByRole('button', { name: 'Gerar link' }).click()
  const url = await page.locator('.share-panel input[readonly]').inputValue()
  expect(url).toMatch(/\/s\/[A-Za-z0-9_-]+$/)

  // Destinatário anônimo (sem cookie de sessão).
  const anon = await context.browser().newContext()
  const shared = await anon.newPage()
  const response = await shared.goto(url)
  expect(response.status()).toBe(200)
  expect(response.headers()['cache-control']).toContain('no-store')
  expect(response.headers()['referrer-policy']).toBe('no-referrer')
  await expect(shared.getByText('RG.pdf')).toBeVisible()

  const download = await anon.request.get(`${url}/download`)
  expect(download.status()).toBe(200)
  expect(download.headers()['content-disposition']).toContain('attachment')
  await anon.close()

  // Revogado, o link deixa de funcionar.
  await page.getByRole('button', { name: 'Revogar' }).click()
  await expect(page.locator('.badge.revogado')).toBeVisible()
  expect((await page.request.get(url)).status()).toBe(404)
})

test('service worker não armazena API, links nem documentos', async ({ page }) => {
  await login(page, uniqueEmail('cache'))
  await uploadPdf(page)
  await page.evaluate(() => navigator.serviceWorker.ready)
  // Gera tráfego de API com o service worker já ativo.
  await page.reload()
  await expect(page.locator('.doc-name')).toBeVisible()
  await page.getByRole('link', { name: 'Baixar' }).evaluate((a) => fetch(a.href).then((r) => r.blob()))

  const cached = await page.evaluate(async () => {
    const urls = []
    for (const name of await caches.keys()) {
      const cache = await caches.open(name)
      for (const req of await cache.keys()) urls.push(new URL(req.url).pathname)
    }
    return urls
  })
  expect(cached.length).toBeGreaterThan(0) // o shell está em cache
  expect(cached.filter((p) => /^\/(api|s|dev)\//.test(p))).toEqual([])
})

test('exclusão de conta remove documentos e encerra a sessão', async ({ page }) => {
  const email = uniqueEmail('conta')
  await login(page, email)
  await uploadPdf(page)

  await page.getByTestId('abrir-exclusao').click()
  const confirm = page.getByTestId('confirmar-exclusao')
  await expect(confirm).toBeDisabled()
  await page.getByTestId('confirmar-email').fill(email.toUpperCase())
  await confirm.click()

  await expect(page.getByText('Sua conta e todos os seus documentos foram excluídos.')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  expect((await page.request.get('/api/me')).status()).toBe(401)

  // Um novo login com o mesmo e-mail começa sem documentos.
  await login(page, email)
  await expect(page.getByText('Nenhum documento ainda.')).toBeVisible()
})
