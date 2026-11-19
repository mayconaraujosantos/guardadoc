import { reactive, ref } from 'vue'

// ------------------------------------------------------------------ toasts

export const toasts = reactive([])
let toastId = 0

/**
 * Mensagem curta no rodapé (snackbar).
 * @param {string} message
 * @param {{ type?: 'info'|'success'|'error', action?: { label: string, run: () => void }, duration?: number }} options
 */
export function toast(message, { type = 'info', action, duration = 3500 } = {}) {
  const id = ++toastId
  toasts.push({ id, message, type, action })
  setTimeout(() => dismissToast(id), duration)
  return id
}

export function dismissToast(id) {
  const index = toasts.findIndex((t) => t.id === id)
  if (index !== -1) toasts.splice(index, 1)
}

// ----------------------------------------------------- camadas sobrepostas

// Pilha compartilhada por todas as sheets: a última aberta fica por cima, só
// ela reage ao Esc, e a rolagem da página só é liberada quando a pilha esvazia.
const layers = []

/** Registra uma camada aberta; devolve o z-index que a coloca acima das demais. */
export function layerOpened() {
  const z = (layers.at(-1) ?? 100) + 1
  layers.push(z)
  document.documentElement.classList.add('scroll-locked')
  return z
}

/** A camada com esse z-index é a de cima? */
export function isTopLayer(z) {
  return layers.at(-1) === z
}

export function layerClosed(z) {
  const index = layers.lastIndexOf(z)
  if (index !== -1) layers.splice(index, 1)
  if (layers.length === 0) document.documentElement.classList.remove('scroll-locked')
}

// ------------------------------------------------------------ confirmação

/** Estado do sheet de confirmação único, renderizado por ConfirmHost. */
export const confirmState = ref(null)

/**
 * Substitui window.confirm por um bottom sheet.
 * @returns {Promise<boolean>}
 */
export function confirmAction({ title, message = '', confirmLabel = 'Confirmar', danger = false }) {
  return new Promise((resolve) => {
    confirmState.value = { title, message, confirmLabel, danger, resolve }
  })
}
