import { expect, test } from '@playwright/test'
import { confirm, goToTab, login, openShare, requestCode, uniqueEmail, uploadPdf } from './helpers.js'

test('código OTP inválido mostra erro e não cria sessão', async ({ page }) => {
  const code = await requestCode(page, uniqueEmail('otp'))
  const wrong = code === '000000' ? '111111' : '000000'
  await page.getByLabel('Código').fill(wrong) // envia sozinho ao completar

  await expect(page.locator('.banner.error')).toBeVisible()
  await expect(page.getByLabel('Código')).toHaveValue('')
  expect((await page.request.get('/api/me')).status()).toBe(401)
})

test('arquivo com conteúdo que não é PDF/JPEG/PNG é recusado', async ({ page }) => {
  await login(page, uniqueEmail('tipo'))
  await page.getByTestId('arquivo-input').setInputFiles({
    name: 'falso.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('<html><script>alert(1)</script></html>'),
  })
  await expect(page.locator('.toast.error')).toBeVisible()
  await expect(page.getByText('Nenhum documento ainda.')).toBeVisible()
})

test('link com limite de downloads deixa de funcionar ao esgotar', async ({ page, request }) => {
  await login(page, uniqueEmail('limite'))
  await uploadPdf(page)

  const share = await openShare(page, 'documento.pdf')
  await share.getByRole('switch').check()
  await share.getByLabel('Limite de downloads').fill('1')
  await share.getByRole('button', { name: 'Gerar link' }).click()
  const url = await share.locator('.share-url').inputValue()

  expect((await request.get(`${url}/download`)).status()).toBe(200)
  expect((await request.get(`${url}/download`)).status()).toBe(404)
})

test('logout encerra a sessão', async ({ page }) => {
  await login(page, uniqueEmail('logout'))
  await goToTab(page, 'Conta')
  await page.getByRole('button', { name: /^Sair/ }).click()
  await confirm(page)
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
  expect((await page.request.get('/api/me')).status()).toBe(401)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible()
})

test('um usuário não acessa documentos de outro', async ({ browser }) => {
  const alice = await (await browser.newContext()).newPage()
  await login(alice, uniqueEmail('alice'))
  await uploadPdf(alice, 'segredo.pdf')
  const [doc] = await (await alice.request.get('/api/documents')).json()

  const bob = await (await browser.newContext()).newPage()
  await login(bob, uniqueEmail('bob'))
  await expect(bob.getByText('Nenhum documento ainda.')).toBeVisible()
  expect((await bob.request.get(`/api/documents/${doc.id}/download`)).status()).toBe(404)
  expect((await bob.request.get(`/api/documents/${doc.id}/shares`)).status()).toBe(404)

  await alice.context().close()
  await bob.context().close()
})
