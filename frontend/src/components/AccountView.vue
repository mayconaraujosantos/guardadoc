<script setup>
import { computed, ref } from 'vue'
import { api, formatRelative, haptic } from '../api.js'
import { isIos, isStandalone, useInstall } from '../composables/install.js'
import { confirmAction, toast } from '../composables/ui.js'
import BottomSheet from './ui/BottomSheet.vue'
import Icon from './ui/Icon.vue'

const props = defineProps({ user: { type: Object, required: true } })
const emit = defineEmits(['logout', 'deleted', 'session-expired'])

const { canInstall, install } = useInstall()
const standalone = isStandalone()
const showIosHint = !standalone && isIos()
const iosHintOpen = ref(false)

const initial = computed(() => props.user.email.charAt(0).toUpperCase())

const deleteOpen = ref(false)
const confirmation = ref('')
const deleting = ref(false)
const registeringPasskey = ref(false)
const passkeyMessage = ref('')
const deleteError = ref('')
const confirmed = computed(() => confirmation.value.trim().toLowerCase() === props.user.email.toLowerCase())

async function onInstall() {
  if (await install()) toast('GuardaDoc instalado.', { type: 'success' })
}

async function logout() {
  const ok = await confirmAction({ title: 'Sair do GuardaDoc?', message: 'Você poderá entrar novamente com sua Passkey.', confirmLabel: 'Sair' })
  if (ok) emit('logout')
}

async function registerPasskey() {
  registeringPasskey.value = true
  passkeyMessage.value = ''
  try {
    await api.registerPasskey()
    haptic(15)
    passkeyMessage.value = 'Passkey criada neste dispositivo.'
  } catch (e) {
    passkeyMessage.value = e.message
  } finally {
    registeringPasskey.value = false
  }
}

function openDelete() {
  confirmation.value = ''
  deleteError.value = ''
  deleteOpen.value = true
}

async function deleteAccount() {
  if (!confirmed.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api.deleteAccount()
    haptic([20, 40, 20])
    deleteOpen.value = false
    emit('deleted')
  } catch (e) {
    if (e.status === 401) emit('session-expired')
    else deleteError.value = e.message
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">Conta</h1>

    <section class="profile">
      <span class="avatar" aria-hidden="true">{{ initial }}</span>
      <div class="lines">
        <strong class="nowrap">{{ user.email }}</strong>
        <span class="meta">Sessão expira {{ formatRelative(user.sessionExpiresAt) }}</span>
      </div>
    </section>

    <h2 class="group-title">Aplicativo</h2>
    <div class="group">
      <button v-if="canInstall" class="item" @click="onInstall">
        <span class="item-icon"><Icon name="phone" /></span>
        <span class="lines"><strong>Instalar o app</strong><span class="meta">Acesso rápido pela tela inicial</span></span>
        <Icon name="chevron" class="row-end" />
      </button>
      <button v-else-if="showIosHint" class="item" @click="iosHintOpen = true">
        <span class="item-icon"><Icon name="phone" /></span>
        <span class="lines"><strong>Adicionar à Tela de Início</strong><span class="meta">Use como um app no iPhone</span></span>
        <Icon name="chevron" class="row-end" />
      </button>
      <div class="item static">
        <span class="item-icon"><Icon name="shield" /></span>
        <span class="lines">
          <strong>Privacidade</strong>
          <span class="meta">Arquivos em armazenamento privado. Nada fica salvo no aparelho além do app.</span>
        </span>
      </div>
    </div>

    <h2 class="group-title">Acesso</h2>
    <div class="group">
      <button class="item" :disabled="registeringPasskey" @click="registerPasskey">
        <span class="item-icon"><Icon name="shield" /></span>
        <span class="lines"><strong>Criar Passkey</strong><span class="meta">Entre depois usando biometria ou PIN deste dispositivo</span></span>
      </button>
      <p v-if="passkeyMessage" class="group-message">{{ passkeyMessage }}</p>
    </div>

    <h2 class="group-title">Sessão</h2>
    <div class="group">
      <button class="item" @click="logout">
        <span class="item-icon"><Icon name="logout" /></span>
        <span class="lines"><strong>Sair</strong><span class="meta">Encerra a sessão neste aparelho</span></span>
      </button>
    </div>

    <h2 class="group-title danger-text">Zona de perigo</h2>
    <div class="group">
      <button class="item danger" data-testid="abrir-exclusao" @click="openDelete">
        <span class="item-icon"><Icon name="trash" /></span>
        <span class="lines"><strong>Excluir minha conta</strong><span class="meta">Remove todos os documentos, links e sessões</span></span>
      </button>
    </div>

    <p class="version">GuardaDoc · v0.1</p>

    <BottomSheet v-model:open="deleteOpen" title="Excluir conta">
      <form class="stack" @submit.prevent="deleteAccount">
        <p class="banner error">
          <Icon name="alert" :size="18" />
          Esta ação é permanente: todos os seus documentos serão apagados e os links compartilhados deixarão de funcionar.
        </p>
        <label class="field">
          <span>Para confirmar, digite seu e-mail ({{ user.email }})</span>
          <input
            v-model="confirmation" type="email" inputmode="email" autocomplete="off" autocapitalize="off"
            data-testid="confirmar-email"
          />
        </label>
        <p v-if="deleteError" class="banner error" role="alert">{{ deleteError }}</p>
        <button type="submit" class="btn btn-danger block" :disabled="!confirmed || deleting" data-testid="confirmar-exclusao">
          <span v-if="deleting" class="spinner" />{{ deleting ? 'Excluindo…' : 'Excluir definitivamente' }}
        </button>
        <button type="button" class="btn btn-ghost block" :disabled="deleting" @click="deleteOpen = false">Cancelar</button>
      </form>
    </BottomSheet>

    <BottomSheet v-model:open="iosHintOpen" title="Instalar no iPhone">
      <ol class="steps">
        <li>Toque em <strong>Compartilhar</strong> <Icon name="upload" :size="18" /> na barra do Safari.</li>
        <li>Escolha <strong>Adicionar à Tela de Início</strong>.</li>
        <li>Toque em <strong>Adicionar</strong>. O GuardaDoc abrirá em tela cheia, como um app.</li>
      </ol>
      <button class="btn btn-primary block" @click="iosHintOpen = false">Entendi</button>
    </BottomSheet>
  </div>
</template>
