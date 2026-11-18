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

// Estado compartilhado por todas as sheets: a última aberta fica por cima e a
// rolagem da página só é liberada quando a última camada fecha.
let openLayers = 0
let topZ = 100

/** Registra uma camada aberta; devolve o z-index que a coloca acima das demais. */
export function layerOpened() {
  openLayers++
  document.documentElement.classList.add('scroll-locked')
  return ++topZ
}

export function layerClosed() {
  openLayers = Math.max(0, openLayers - 1)
  if (openLayers === 0) {
    document.documentElement.classList.remove('scroll-locked')
    topZ = 100
  }
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
