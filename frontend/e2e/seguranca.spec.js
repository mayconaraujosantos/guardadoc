import { expect, test } from '@playwright/test'
import { login, uniqueEmail, uploadPdf } from './helpers.js'

test('código OTP inválido mostra erro e não cria sessão', async ({ page }) => {
  const email = uniqueEmail('otp')
  await page.goto('/')
  await page.getByLabel('E-mail').fill(email)
  await page.getByRole('button', { name: 'Enviar código' }).click()

  const outbox = await (await page.request.get('/dev/outbox')).json()
  const wrong = outbox[email] === '000000' ? '111111' : '000000'
  await page.getByLabel('Código').fill(wrong)
  await page.getByRole('button', { name: 'Entrar' }).click()

  await expect(page.locator('.alert.error')).toBeVisible()
  await expect(page.getByLabel('Código')).toHaveValue('')
  expect((await page.request.get('/api/me')).status()).toBe(401)
})

test('arquivo com conteúdo que não é PDF/JPEG/PNG é recusado', async ({ page }) => {
  await login(page, uniqueEmail('tipo'))
  await page.locator('input[type=file]').setInputFiles({
    name: 'falso.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('<html><script>alert(1)</script></html>'),
  })
  await expect(page.locator('.alert.error')).toBeVisible()
  await expect(page.getByText('Nenhum documento ainda.')).toBeVisible()
})

test('link com limite de downloads deixa de funcionar ao esgotar', async ({ page, request }) => {
  await login(page, uniqueEmail('limite'))
  await uploadPdf(page)

  await page.getByRole('button', { name: 'Compartilhar' }).click()
  await page.getByLabel('Limite de downloads').fill('1')
  await page.getByRole('button', { name: 'Gerar link' }).click()
  const url = await page.locator('.share-panel input[readonly]').inputValue()

  expect((await request.get(`${url}/download`)).status()).toBe(200)
  expect((await request.get(`${url}/download`)).status()).toBe(404)
})

test('logout encerra a sessão', async ({ page }) => {
  await login(page, uniqueEmail('logout'))
  await page.getByRole('button', { name: 'Sair' }).click()
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
