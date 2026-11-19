import { expect, test } from '@playwright/test'
import { confirm, goToTab, login, openShare, uniqueEmail, uploadPdf } from './helpers.js'

test('login, upload, compartilhamento e download pelo link', async ({ page, browser }) => {
  await login(page, uniqueEmail('fluxo'))
  await uploadPdf(page, 'RG.pdf')

  const share = await openShare(page, 'RG.pdf')
  await share.getByRole('radio', { name: '7 dias' }).click()
  await share.getByRole('button', { name: 'Gerar link' }).click()
  const url = await share.locator('.share-url').inputValue()
  expect(url).toMatch(/\/s\/[A-Za-z0-9_-]+$/)

  // Destinatário anônimo (sem cookie de sessão).
  const anon = await browser.newContext()
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

  // Revogado (com confirmação), o link deixa de funcionar.
  await share.getByRole('button', { name: 'Criar outro link' }).click()
  await share.getByRole('button', { name: 'Revogar' }).click()
  await confirm(page)
  await expect(page.getByText('Link revogado.')).toBeVisible()
  await expect(share.getByText('Nenhum link ativo.')).toBeVisible()
  expect((await page.request.get(url)).status()).toBe(404)
})

test('service worker não armazena API, links nem documentos', async ({ page }) => {
  await login(page, uniqueEmail('cache'))
  await uploadPdf(page)
  await page.evaluate(() => navigator.serviceWorker.ready)
  // Gera tráfego de API com o service worker já ativo.
  await page.reload()
  await expect(page.locator('.doc-name')).toBeVisible()
  const [doc] = await (await page.request.get('/api/documents')).json()
  await page.evaluate((id) => fetch(`/api/documents/${id}/download`).then((r) => r.blob()), doc.id)

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

  await goToTab(page, 'Conta')
  await page.getByTestId('abrir-exclusao').click()
  const submit = page.getByTestId('confirmar-exclusao')
  await expect(submit).toBeDisabled()
  await page.getByTestId('confirmar-email').fill(email.toUpperCase())
  await submit.click()

  await expect(page.getByText('Sua conta e todos os seus documentos foram excluídos.')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  expect((await page.request.get('/api/me')).status()).toBe(401)

  // Um novo login com o mesmo e-mail começa sem documentos.
  await login(page, email)
  await expect(page.getByText('Nenhum documento ainda.')).toBeVisible()
})
