<script setup>
import { computed, ref } from 'vue'
import { api } from '../api.js'

const props = defineProps({ email: { type: String, required: true } })
const emit = defineEmits(['deleted', 'session-expired'])

const open = ref(false)
const confirmation = ref('')
const deleting = ref(false)
const error = ref('')

const confirmed = computed(() => confirmation.value.trim().toLowerCase() === props.email.toLowerCase())

function cancel() {
  open.value = false
  confirmation.value = ''
  error.value = ''
}

async function deleteAccount() {
  if (!confirmed.value) return
  deleting.value = true
  error.value = ''
  try {
    await api.deleteAccount()
    emit('deleted')
  } catch (e) {
    if (e.status === 401) emit('session-expired')
    else error.value = e.message
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <section class="card account">
    <h2>Minha conta</h2>
    <p v-if="!open" class="row between wrap">
      <span class="muted small">Excluir a conta remove todos os documentos, links e sessões.</span>
      <button class="danger" data-testid="abrir-exclusao" @click="open = true">Excluir minha conta</button>
    </p>
    <form v-else @submit.prevent="deleteAccount">
      <p class="alert error">
        Esta ação é permanente: todos os seus documentos serão apagados e os links compartilhados deixarão de
        funcionar.
      </p>
      <label>
        Para confirmar, digite seu e-mail ({{ email }})
        <input v-model="confirmation" type="email" autocomplete="off" data-testid="confirmar-email" />
      </label>
      <p v-if="error" class="alert error">{{ error }}</p>
      <div class="row">
        <button type="submit" class="danger" :disabled="!confirmed || deleting" data-testid="confirmar-exclusao">
          {{ deleting ? 'Excluindo…' : 'Excluir definitivamente' }}
        </button>
        <button type="button" class="ghost" :disabled="deleting" @click="cancel">Cancelar</button>
      </div>
    </form>
  </section>
</template>
