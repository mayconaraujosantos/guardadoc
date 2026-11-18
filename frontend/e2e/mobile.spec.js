import { expect, test } from '@playwright/test'
import { confirm, goToTab, login, openDocument, sampleJpeg, uniqueEmail, uploadPdf } from './helpers.js'

// Comportamentos de app móvel: voltar, câmera, prévia, abas, busca.

test('botão voltar fecha a sheet em vez de sair do app', async ({ page }) => {
  await login(page, uniqueEmail('voltar'))
  await uploadPdf(page, 'CNH.pdf')

  const sheet = await openDocument(page, 'CNH.pdf')
  await page.goBack()
  await expect(sheet).toBeHidden()
  await expect(page.getByRole('heading', { name: 'Meus documentos' })).toBeVisible()

  // Fechar pela interface também não deixa entradas sobrando no histórico.
  await openDocument(page, 'CNH.pdf')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
  await goToTab(page, 'Conta')
  await expect(page.getByRole('heading', { name: 'Conta' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { name: 'Meus documentos' })).toBeVisible()
})

test('foto da câmera recebe nome legível e abre com prévia', async ({ page }) => {
  await login(page, uniqueEmail('camera'))
  await page.getByTestId('camera-input').setInputFiles({ name: 'image.jpg', mimeType: 'image/jpeg', buffer: sampleJpeg })

  const row = page.getByRole('button', { name: /^Abrir Foto .*\.jpg$/ })
  await expect(row).toBeVisible()
  await row.click()
  const sheet = page.getByRole('dialog').last()
  await expect(sheet.locator('.preview img')).toBeVisible()

  await sheet.getByRole('button', { name: /Visualizar/ }).click()
  const viewer = page.locator('.viewer')
  await expect(viewer.locator('img')).toBeVisible()
  await page.goBack() // fecha o visualizador
  await expect(viewer).toBeHidden()
  await expect(sheet).toBeVisible()
})

test('menu de adicionar oferece câmera, galeria e arquivo', async ({ page }) => {
  await login(page, uniqueEmail('fab'))
  await page.getByTestId('adicionar').click()
  const sheet = page.getByRole('dialog', { name: 'Adicionar documento' })
  await expect(sheet.getByRole('button', { name: /Tirar foto/ })).toBeVisible()
  await expect(sheet.getByRole('button', { name: /Escolher da galeria/ })).toBeVisible()
  await expect(sheet.getByRole('button', { name: /Escolher arquivo/ })).toBeVisible()
})

test('busca filtra a lista e exclusão pede confirmação', async ({ page }) => {
  await login(page, uniqueEmail('busca'))
  await uploadPdf(page, 'Comprovante de residência.pdf')
  await uploadPdf(page, 'Passaporte.pdf')

  await page.getByRole('button', { name: 'Buscar' }).click()
  await page.getByLabel('Buscar documentos').fill('passa')
  await expect(page.locator('.doc-name')).toHaveText(['Passaporte.pdf'])

  await page.getByRole('button', { name: 'Fechar busca' }).click()
  await expect(page.locator('.doc-name')).toHaveCount(2)

  const sheet = await openDocument(page, 'Passaporte.pdf')
  await sheet.getByRole('button', { name: /Excluir/ }).click()
  await confirm(page)
  await expect(page.getByText('Documento excluído.')).toBeVisible()
  await expect(page.locator('.doc-name')).toHaveText(['Comprovante de residência.pdf'])
})

test('atalho do app abre o menu de adicionar', async ({ page }) => {
  await login(page, uniqueEmail('atalho'))
  await page.goto('/?acao=foto')
  await expect(page.getByRole('dialog', { name: 'Adicionar documento' })).toBeVisible()
  await expect(page).toHaveURL(/\/$/)
})
