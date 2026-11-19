import { readFileSync } from 'node:fs'
import { expect } from '@playwright/test'

/** PDF mínimo válido para o validador de conteúdo do backend. */
export const samplePdf = Buffer.from('%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << >>\n%%EOF\n')

/** JPEG real (320x200) para testar câmera e prévia. */
export const sampleJpeg = readFileSync(new URL('./fixtures/documento.jpg', import.meta.url))

export function uniqueEmail(prefix) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
}

/** Pede o código e devolve o OTP lido no outbox de desenvolvimento. */
export async function requestCode(page, email) {
  await page.goto('/')
  await page.getByLabel('E-mail').fill(email)
  await page.getByRole('button', { name: 'Enviar código' }).click()
  await expect(page.getByRole('heading', { name: 'Digite o código' })).toBeVisible()
  const outbox = await (await page.request.get('/dev/outbox')).json()
  const code = outbox[email.toLowerCase()]
  expect(code, 'código OTP no outbox').toMatch(/^\d{6}$/)
  return code
}

/** Login completo: o formulário envia sozinho ao completar 6 dígitos. */
export async function login(page, email) {
  const code = await requestCode(page, email)
  await page.getByLabel('Código').fill(code)
  await expect(page.getByRole('heading', { name: 'Meus documentos' })).toBeVisible()
}

export async function uploadPdf(page, name = 'documento.pdf') {
  await page.getByTestId('arquivo-input').setInputFiles({ name, mimeType: 'application/pdf', buffer: samplePdf })
  await expect(page.locator('.doc-name', { hasText: name })).toBeVisible()
}

/** Abre a sheet de detalhes de um documento da lista. */
export async function openDocument(page, name) {
  await page.getByRole('button', { name: `Abrir ${name}` }).click()
  const sheet = page.getByRole('dialog', { name })
  await expect(sheet).toBeVisible()
  return sheet
}

/** Abre a sheet de compartilhamento a partir da lista. */
export async function openShare(page, name) {
  const doc = await openDocument(page, name)
  await doc.getByRole('button', { name: /Compartilhar/ }).click()
  const sheet = page.getByRole('dialog', { name: 'Compartilhar' })
  await expect(sheet).toBeVisible()
  return sheet
}

/** Confirma o bottom sheet de confirmação (substitui window.confirm). */
export async function confirm(page) {
  await page.getByTestId('confirmar').click()
}

export async function goToTab(page, name) {
  await page.getByRole('navigation', { name: 'Navegação principal' }).getByRole('button', { name }).click()
}
