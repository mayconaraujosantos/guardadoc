<script setup>
import { ref } from 'vue'
import { api } from '../api.js'

const emit = defineEmits(['authenticated'])

const step = ref('email')
const email = ref('')
const code = ref('')
const busy = ref(false)
const message = ref('')
const error = ref('')
const isDev = import.meta.env.DEV

async function sendCode() {
  error.value = ''
  busy.value = true
  try {
    const res = await api.requestOtp(email.value)
    message.value = res.message
    step.value = 'code'
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function verify() {
  error.value = ''
  busy.value = true
  try {
    emit('authenticated', await api.verifyOtp(email.value, code.value))
  } catch (e) {
    error.value = e.message
    code.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="card narrow">
    <h1>Entrar</h1>
    <p class="muted">Sem senha: enviaremos um código de 6 dígitos para seu e-mail.</p>

    <form v-if="step === 'email'" @submit.prevent="sendCode">
      <label for="email">E-mail</label>
      <input id="email" v-model="email" type="email" autocomplete="email" required maxlength="254" />
      <button type="submit" :disabled="busy">{{ busy ? 'Enviando…' : 'Enviar código' }}</button>
    </form>

    <form v-else @submit.prevent="verify">
      <p class="alert info">{{ message }}</p>
      <label for="code">Código</label>
      <input
        id="code" v-model="code" inputmode="numeric" autocomplete="one-time-code"
        pattern="[0-9]{6}" maxlength="6" required placeholder="000000"
      />
      <button type="submit" :disabled="busy">{{ busy ? 'Verificando…' : 'Entrar' }}</button>
      <button type="button" class="ghost" @click="step = 'email'; code = ''">Usar outro e-mail</button>
      <p v-if="isDev" class="muted small">
        Dev: veja o código em <a href="/dev/outbox" target="_blank" rel="noreferrer">/dev/outbox</a>.
      </p>
    </form>

    <p v-if="error" class="alert error">{{ error }}</p>
  </section>
</template>
