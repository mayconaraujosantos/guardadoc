<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, haptic } from '../api.js'
import Icon from './ui/Icon.vue'

const props = defineProps({ notice: { type: String, default: '' } })
const emit = defineEmits(['authenticated'])

const RESEND_SECONDS = 30

const step = ref('email')
const email = ref('')
const code = ref('')
const busy = ref(false)
const error = ref('')
const resendIn = ref(0)
const codeInput = ref(null)
const emailInput = ref(null)
let timer = null

const digits = computed(() => Array.from({ length: 6 }, (_, i) => code.value[i] ?? ''))
const webmail = computed(() => {
  const domain = email.value.split('@')[1]?.toLowerCase()
  if (domain === 'gmail.com' || domain === 'googlemail.com') return { label: 'Abrir Gmail', url: 'https://mail.google.com/' }
  if (['outlook.com', 'hotmail.com', 'live.com'].includes(domain)) return { label: 'Abrir Outlook', url: 'https://outlook.live.com/mail/' }
  if (domain === 'yahoo.com' || domain === 'yahoo.com.br') return { label: 'Abrir Yahoo Mail', url: 'https://mail.yahoo.com/' }
  return null
})

function startResendTimer() {
  resendIn.value = RESEND_SECONDS
  clearInterval(timer)
  timer = setInterval(() => {
    resendIn.value--
    if (resendIn.value <= 0) clearInterval(timer)
  }, 1000)
}
onBeforeUnmount(() => clearInterval(timer))

async function sendCode() {
  error.value = ''
  busy.value = true
  try {
    await api.requestOtp(email.value.trim())
    step.value = 'code'
    code.value = ''
    startResendTimer()
    await nextTick()
    codeInput.value?.focus()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function verify() {
  if (code.value.length !== 6 || busy.value) return
  error.value = ''
  busy.value = true
  try {
    const me = await api.verifyOtp(email.value.trim(), code.value)
    haptic(15)
    emit('authenticated', me)
  } catch (e) {
    haptic([30, 40, 30])
    error.value = e.message
    code.value = ''
    await nextTick()
    codeInput.value?.focus()
  } finally {
    busy.value = false
  }
}

function onCodeInput(event) {
  code.value = event.target.value.replace(/\D/g, '').slice(0, 6)
  event.target.value = code.value
}

// Envia sozinho ao completar 6 dígitos (digitação, colar ou preenchimento automático).
watch(code, (value) => {
  if (value.length === 6) void verify()
})

async function changeEmail() {
  step.value = 'email'
  code.value = ''
  error.value = ''
  await nextTick()
  emailInput.value?.focus()
}
</script>

<template>
  <div class="login">
    <div class="login-hero">
      <img src="/icon.svg" alt="" width="72" height="72" class="login-logo" />
      <h1>GuardaDoc</h1>
      <p>Seus documentos pessoais, guardados com segurança e compartilhados só com quem você quiser.</p>
    </div>

    <section class="login-card">
      <p v-if="props.notice" class="banner success"><Icon name="check" :size="18" />{{ props.notice }}</p>

      <form v-if="step === 'email'" class="stack" @submit.prevent="sendCode">
        <h2>Entrar</h2>
        <p class="muted">Sem senha: enviaremos um código de 6 dígitos para o seu e-mail.</p>
        <label class="field">
          <span>E-mail</span>
          <input
            ref="emailInput" v-model="email" type="email" inputmode="email" autocomplete="email"
            autocapitalize="off" spellcheck="false" required maxlength="254" placeholder="voce@exemplo.com"
          />
        </label>
        <button type="submit" class="btn btn-primary block" :disabled="busy">
          <span v-if="busy" class="spinner" />{{ busy ? 'Enviando…' : 'Enviar código' }}
        </button>
      </form>

      <form v-else class="stack" @submit.prevent="verify">
        <button type="button" class="link-back" @click="changeEmail">
          <Icon name="back" :size="18" /> Usar outro e-mail
        </button>
        <h2>Digite o código</h2>
        <p class="muted">Enviamos um código para <strong class="nowrap">{{ email }}</strong>. Ele vale por 5 minutos.</p>

        <label class="otp" :class="{ error: !!error, busy }">
          <span class="sr-only">Código</span>
          <input
            ref="codeInput" :value="code" class="otp-input" inputmode="numeric" autocomplete="one-time-code"
            pattern="[0-9]*" maxlength="6" aria-label="Código" :disabled="busy" @input="onCodeInput"
          />
          <span
            v-for="(d, i) in digits" :key="i" class="otp-box"
            :class="{ filled: d, active: i === Math.min(code.length, 5) }" aria-hidden="true"
          >{{ d }}</span>
        </label>

        <button type="submit" class="btn btn-primary block" :disabled="busy || code.length !== 6">
          <span v-if="busy" class="spinner" />{{ busy ? 'Verificando…' : 'Entrar' }}
        </button>

        <div class="login-links">
          <a v-if="webmail" class="btn btn-ghost" :href="webmail.url" target="_blank" rel="noreferrer">
            <Icon name="mail" :size="18" /> {{ webmail.label }}
          </a>
          <button type="button" class="btn btn-ghost" :disabled="resendIn > 0 || busy" @click="sendCode">
            {{ resendIn > 0 ? `Reenviar em ${resendIn}s` : 'Reenviar código' }}
          </button>
        </div>
      </form>

      <p v-if="error" class="banner error" role="alert"><Icon name="alert" :size="18" />{{ error }}</p>
    </section>

    <p class="login-foot"><Icon name="shield" :size="16" /> Arquivos privados · links com validade</p>
  </div>
</template>
