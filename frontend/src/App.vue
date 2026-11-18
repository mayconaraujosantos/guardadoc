<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api.js'
import LoginView from './components/LoginView.vue'
import DocumentsView from './components/DocumentsView.vue'
import AccountPanel from './components/AccountPanel.vue'

const user = ref(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    user.value = await api.me()
  } catch (e) {
    error.value = 'Não foi possível conectar ao servidor.'
  } finally {
    loading.value = false
  }
})

const notice = ref('')

function onAuthenticated(me) {
  notice.value = ''
  user.value = me
}

function onAccountDeleted() {
  user.value = null
  notice.value = 'Sua conta e todos os seus documentos foram excluídos.'
}

async function logout() {
  await api.logout().catch(() => {})
  user.value = null
}
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <img src="/icon.svg" alt="" width="32" height="32" />
      <span>GuardaDoc</span>
    </div>
    <div v-if="user" class="session">
      <span class="muted">{{ user.email }}</span>
      <button class="ghost" @click="logout">Sair</button>
    </div>
  </header>

  <main class="container">
    <p v-if="loading" class="muted">Carregando…</p>
    <p v-else-if="error" class="alert error">{{ error }}</p>
    <template v-else-if="!user">
      <p v-if="notice" class="alert info">{{ notice }}</p>
      <LoginView @authenticated="onAuthenticated" />
    </template>
    <template v-else>
      <DocumentsView @session-expired="user = null" />
      <AccountPanel :email="user.email" @deleted="onAccountDeleted" @session-expired="user = null" />
    </template>
  </main>
</template>
