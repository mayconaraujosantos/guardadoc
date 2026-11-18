import { expect } from '@playwright/test'

/** PDF mínimo válido para o validador de conteúdo do backend. */
export const samplePdf = Buffer.from('%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << >>\n%%EOF\n')

export function uniqueEmail(prefix) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
}

/** Login completo por OTP, lendo o código no outbox de desenvolvimento. */
export async function login(page, email) {
  await page.goto('/')
  await page.getByLabel('E-mail').fill(email)
  await page.getByRole('button', { name: 'Enviar código' }).click()
  await expect(page.getByLabel('Código')).toBeVisible()

  const outbox = await (await page.request.get('/dev/outbox')).json()
  const code = outbox[email.toLowerCase()]
  expect(code, 'código OTP no outbox').toMatch(/^\d{6}$/)

  await page.getByLabel('Código').fill(code)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: 'Meus documentos' })).toBeVisible()
}

export async function uploadPdf(page, name = 'documento.pdf') {
  await page.locator('input[type=file]').setInputFiles({ name, mimeType: 'application/pdf', buffer: samplePdf })
  await expect(page.locator('.doc-name', { hasText: name })).toBeVisible()
}
