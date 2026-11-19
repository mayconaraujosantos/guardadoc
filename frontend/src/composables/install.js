import { onBeforeUnmount, onMounted, ref } from 'vue'

// Convite de instalação (Android/Chrome/Edge) e instrução manual no iOS.
const deferredPrompt = ref(null)
const installed = ref(false)

export function captureInstallPrompt() {
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault()
    deferredPrompt.value = event
  })
  window.addEventListener('appinstalled', () => {
    installed.value = true
    deferredPrompt.value = null
  })
}

export function isStandalone() {
  return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true
}

export function isIos() {
  return /iphone|ipad|ipod/i.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
}

export function useInstall() {
  async function install() {
    const prompt = deferredPrompt.value
    if (!prompt) return false
    prompt.prompt()
    const { outcome } = await prompt.userChoice
    deferredPrompt.value = null
    return outcome === 'accepted'
  }
  return { canInstall: deferredPrompt, installed, install }
}

/** Estado de conexão reativo. */
export function useOnline() {
  const online = ref(navigator.onLine)
  const update = () => (online.value = navigator.onLine)
  onMounted(() => {
    window.addEventListener('online', update)
    window.addEventListener('offline', update)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('online', update)
    window.removeEventListener('offline', update)
  })
  return online
}
