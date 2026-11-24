<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, haptic } from './api.js'
import { pushBack } from './composables/backStack.js'
import { isStandalone, useInstall, useOnline } from './composables/install.js'
import { toast } from './composables/ui.js'
import LoginView from './components/LoginView.vue'
import DocumentsView from './components/DocumentsView.vue'
import AccountView from './components/AccountView.vue'
import Icon from './components/ui/Icon.vue'
import ToastHost from './components/ui/ToastHost.vue'
import ConfirmHost from './components/ui/ConfirmHost.vue'

const user = ref(null)
const loading = ref(true)
const bootError = ref('')
const notice = ref('')
const tab = ref('documentos')
const documentsView = ref(null)
const online = useOnline()

// Atalhos do ícone do app (manifest.shortcuts): /?acao=foto | /?acao=enviar
const params = new URLSearchParams(location.search)
const initialAction = ref(params.get('acao') ?? '')
if (initialAction.value) history.replaceState(null, '', '/')

const { canInstall, install } = useInstall()
const installDismissed = ref(readFlag('guardadoc.install-dismissed'))
const showInstallBanner = computed(() => !!canInstall.value && !installDismissed.value && !isStandalone())

function readFlag(key) {
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}
function dismissInstall() {
  installDismissed.value = true
  try {
    localStorage.setItem('guardadoc.install-dismissed', '1')
  } catch {
    /* armazenamento indisponível */
  }
}
async function onInstall() {
  if (await install()) toast('GuardaDoc instalado.', { type: 'success' })
}

async function boot() {
  loading.value = true
  bootError.value = ''
  try {
    user.value = await api.me()
  } catch (e) {
    bootError.value = e.message || 'Não foi possível conectar ao servidor.'
  } finally {
    loading.value = false
  }
}
onMounted(boot)

// --------------------------------------------------------------- abas

let releaseTab = null
function go(name) {
  if (tab.value === name) {
    if (name === 'documentos') window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  haptic(6)
  tab.value = name
  window.scrollTo({ top: 0 })
  // Fora da aba inicial, o "voltar" do celular retorna para Documentos.
  if (name !== 'documentos' && !releaseTab) {
    releaseTab = pushBack(() => {
      releaseTab = null
      tab.value = 'documentos'
    })
  } else if (name === 'documentos' && releaseTab) {
    releaseTab()
    releaseTab = null
  }
}

// ------------------------------------------------------------- sessão

function onAuthenticated(me) {
  notice.value = ''
  tab.value = 'documentos'
  user.value = me
}

function onSessionExpired() {
  if (!user.value) return
  user.value = null
  toast('Sua sessão expirou. Entre novamente.', { type: 'info' })
}

async function logout() {
  await api.logout().catch(() => {})
  if (releaseTab) {
    releaseTab()
    releaseTab = null
  }
  user.value = null
  tab.value = 'documentos'
}

function onAccountDeleted() {
  if (releaseTab) {
    releaseTab()
    releaseTab = null
  }
  user.value = null
  tab.value = 'documentos'
  notice.value = 'Sua conta e todos os seus documentos foram excluídos.'
}
</script>

<template>
  <div class="app" :class="{ authed: !!user }">
    <!-- Splash -->
    <div v-if="loading" class="splash" aria-busy="true">
      <img src="/icon.svg" alt="" width="80" height="80" />
      <span class="spinner dark" />
    </div>

    <!-- Falha ao iniciar -->
    <div v-else-if="bootError" class="splash">
      <div class="empty">
        <div class="empty-art"><Icon name="offline" :size="40" /></div>
        <h2>Sem conexão com o servidor</h2>
        <p>{{ bootError }}</p>
        <button class="btn btn-primary" @click="boot">Tentar novamente</button>
      </div>
    </div>

    <LoginView v-else-if="!user" :notice="notice" @authenticated="onAuthenticated" />

    <template v-else>
      <header class="appbar">
        <div class="appbar-inner">
          <div class="brand"><img src="/icon.svg" alt="" width="28" height="28" /><span>GuardaDoc</span></div>
          <button
            v-if="tab === 'documentos'" class="icon-btn" aria-label="Buscar"
            @click="documentsView?.toggleSearch()"
          >
            <Icon name="search" />
          </button>
        </div>
        <Transition name="fade">
          <output v-if="!online" class="offline-bar">
            <Icon name="offline" :size="16" /> Sem conexão. Seus documentos não ficam salvos offline.
          </output>
        </Transition>
      </header>

      <main class="content">
        <div v-if="showInstallBanner && tab === 'documentos'" class="install-banner">
          <Icon name="phone" />
          <span class="lines"><strong>Instale o GuardaDoc</strong><span class="meta">Abra direto da tela inicial, em tela cheia.</span></span>
          <button class="btn btn-primary sm" @click="onInstall">Instalar</button>
          <button class="icon-btn" aria-label="Dispensar" @click="dismissInstall"><Icon name="x" :size="18" /></button>
        </div>

        <DocumentsView
          v-show="tab === 'documentos'" ref="documentsView" :initial-action="initialAction"
          @session-expired="onSessionExpired"
        />
        <AccountView
          v-if="tab === 'conta'" :user="user" @logout="logout" @deleted="onAccountDeleted"
          @session-expired="onSessionExpired"
        />
      </main>

      <nav class="tabbar" aria-label="Navegação principal">
        <button class="tab" :class="{ on: tab === 'documentos' }" :aria-current="tab === 'documentos' ? 'page' : undefined" @click="go('documentos')">
          <span class="tab-pill"><Icon name="folder" /></span>
          <span>Documentos</span>
        </button>
        <button class="tab" :class="{ on: tab === 'conta' }" :aria-current="tab === 'conta' ? 'page' : undefined" @click="go('conta')">
          <span class="tab-pill"><Icon name="user" /></span>
          <span>Conta</span>
        </button>
      </nav>
    </template>

    <ToastHost />
    <ConfirmHost />
  </div>
</template>
