// Integra telas sobrepostas (sheets, visualizador, abas) ao botão "voltar" do
// celular: abrir empilha uma entrada no histórico; o "voltar" fecha a camada
// do topo em vez de sair do app.
const stack = []
let skipPops = 0
let pendingBack = 0

if (typeof window !== 'undefined') {
  window.addEventListener('popstate', () => {
    if (skipPops > 0) {
      skipPops--
      return
    }
    const top = stack.pop()
    top?.onBack()
  })
}

/** Agrupa fechamentos simultâneos em um único history.go(-n) (um só popstate). */
function scheduleBack() {
  pendingBack++
  if (pendingBack > 1) return
  skipPops++
  queueMicrotask(() => {
    const steps = pendingBack
    pendingBack = 0
    history.go(-steps)
  })
}

/**
 * Registra uma camada. `onBack` é chamado quando o usuário aperta "voltar".
 * Retorna `release()`, para quando a camada é fechada pela interface.
 */
export function pushBack(onBack) {
  const entry = { onBack }
  stack.push(entry)
  history.pushState({ guardadocLayer: stack.length }, '')
  return function release() {
    const index = stack.indexOf(entry)
    if (index === -1) return // já fechada pelo "voltar"
    stack.splice(index, 1)
    scheduleBack()
  }
}
