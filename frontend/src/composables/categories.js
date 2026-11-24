import { ref } from 'vue'
import { api } from '../api.js'

// Categorias do usuário, compartilhadas entre a lista, o detalhe do documento
// e o gerenciador. As contagens são calculadas a partir dos documentos.

/** @type {import('vue').Ref<Array<{ id: string, name: string }>>} */
export const categories = ref([])

/** Sugestões para começar (só aparecem as que o usuário ainda não criou). */
export const SUGGESTIONS = [
  'Pessoais',
  'Escolar',
  'Faculdade',
  'Trabalho / RH',
  'Casa',
  'Saúde',
  'Financeiro',
  'Veículo',
]

function sortByName(list) {
  return [...list].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR', { sensitivity: 'base' }))
}

export async function loadCategories() {
  categories.value = sortByName(await api.listCategories())
}

export async function createCategory(name) {
  const created = await api.createCategory(name)
  categories.value = sortByName([...categories.value, created])
  return created
}

export async function renameCategory(id, name) {
  const renamed = await api.renameCategory(id, name)
  categories.value = sortByName(categories.value.map((c) => (c.id === id ? renamed : c)))
  return renamed
}

export async function deleteCategory(id) {
  await api.deleteCategory(id)
  categories.value = categories.value.filter((c) => c.id !== id)
}

export function categoryName(id) {
  return categories.value.find((c) => c.id === id)?.name ?? ''
}

export function unusedSuggestions() {
  const taken = new Set(categories.value.map((c) => c.name.toLocaleLowerCase('pt-BR')))
  return SUGGESTIONS.filter((s) => !taken.has(s.toLocaleLowerCase('pt-BR')))
}
