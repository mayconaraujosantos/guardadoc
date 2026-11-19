import { expect, test } from '@playwright/test'
import { confirm, login, openDocument, uniqueEmail, uploadPdf } from './helpers.js'

// Organização por categorias: criar, filtrar, mover, enviar direto para a
// categoria aberta, renomear e excluir sem perder documentos.

const bar = (page) => page.getByRole('navigation', { name: 'Categorias' })
const chip = (page, name) => bar(page).getByRole('button', { name: new RegExp(`^${name}\\b`) })

test('categorias organizam e filtram documentos', async ({ page }) => {
  await login(page, uniqueEmail('categorias'))
  await uploadPdf(page, 'Histórico escolar.pdf')
  await uploadPdf(page, 'Conta de luz.pdf')

  // Cria categorias a partir das sugestões e digitando um nome.
  await page.getByTestId('gerenciar-categorias').click()
  const manage = page.getByRole('dialog', { name: 'Categorias' })
  await manage.getByRole('button', { name: 'Criar categoria Escolar' }).click()
  await manage.getByRole('button', { name: 'Criar categoria Trabalho / RH' }).click()
  await manage.getByLabel('Nome da nova categoria').fill('Contas da casa')
  await manage.getByRole('button', { name: 'Criar', exact: true }).click()
  await expect(manage.locator('.cat-item strong')).toHaveText(['Contas da casa', 'Escolar', 'Trabalho / RH'])
  await page.keyboard.press('Escape')
  await expect(manage).toBeHidden()

  // Move cada documento para sua categoria pelo detalhe.
  for (const [doc, category] of [['Histórico escolar.pdf', 'Escolar'], ['Conta de luz.pdf', 'Contas da casa']]) {
    const sheet = await openDocument(page, doc)
    await sheet.getByTestId('mudar-categoria').click()
    await page.getByRole('dialog', { name: 'Mover para categoria' }).getByRole('radio', { name: category }).click()
    await expect(page.getByText(`Movido para ${category}.`)).toBeVisible()
    await expect(sheet.getByTestId('mudar-categoria')).toContainText(category)
    await page.keyboard.press('Escape')
    await expect(sheet).toBeHidden()
  }

  // Filtra pela categoria.
  await expect(chip(page, 'Escolar')).toContainText('1')
  await chip(page, 'Escolar').click()
  await expect(page.locator('.doc-name')).toHaveText(['Histórico escolar.pdf'])

  // Com a categoria aberta, o envio vai direto para ela.
  await page.getByTestId('adicionar').click()
  await expect(page.getByText('Será salvo em Escolar')).toBeVisible()
  await page.keyboard.press('Escape')
  await uploadPdf(page, 'Boletim.pdf')
  await expect(chip(page, 'Escolar')).toContainText('2')

  // Categoria vazia mostra um estado próprio.
  await chip(page, 'Trabalho / RH').click()
  await expect(page.getByText('Nenhum documento em Trabalho / RH.')).toBeVisible()

  // Busca também encontra pelo nome da categoria.
  await chip(page, 'Todos').click()
  await page.getByRole('button', { name: 'Buscar' }).click()
  await page.getByLabel('Buscar documentos').fill('contas da')
  await expect(page.locator('.doc-name')).toHaveText(['Conta de luz.pdf'])
  await page.getByRole('button', { name: 'Fechar busca' }).click()

  // Renomear e excluir: os documentos continuam, só ficam sem categoria.
  await page.getByTestId('gerenciar-categorias').click()
  await manage.getByRole('button', { name: 'Renomear Escolar' }).click()
  await manage.getByLabel('Novo nome para Escolar').fill('Escola')
  await manage.getByRole('button', { name: 'Salvar nome' }).click()
  await expect(manage.getByText('Escola', { exact: true })).toBeVisible()
  await manage.getByRole('button', { name: 'Excluir Escola' }).click()
  await expect(page.getByText('2 documentos ficarão sem categoria')).toBeVisible()
  await confirm(page)
  await expect(page.getByText('Categoria excluída.')).toBeVisible()
  await page.keyboard.press('Escape')

  await expect(page.locator('.doc-name')).toHaveCount(3)
  await chip(page, 'Sem categoria').click()
  await expect(page.locator('.doc-name')).toHaveText(['Boletim.pdf', 'Histórico escolar.pdf'])
})
