<script setup>
import { onMounted, ref } from 'vue'
import { api, formatDate } from '../api.js'

const props = defineProps({ documentId: { type: String, required: true } })
const emit = defineEmits(['error'])

const expirations = [
  { label: '1 hora', minutes: 60 },
  { label: '24 horas', minutes: 60 * 24 },
  { label: '7 dias', minutes: 60 * 24 * 7 },
  { label: '30 dias', minutes: 60 * 24 * 30 },
]
const expiresInMinutes = ref(60 * 24)
const downloadLimit = ref('')
const links = ref([])
const createdUrl = ref('')
const copied = ref(false)
const busy = ref(false)

async function load() {
  try {
    links.value = await api.listShares(props.documentId)
  } catch (e) {
    emit('error', e)
  }
}

async function create() {
  busy.value = true
  copied.value = false
  try {
    const limit = downloadLimit.value ? Number(downloadLimit.value) : null
    const res = await api.createShare(props.documentId, expiresInMinutes.value, limit)
    createdUrl.value = res.url
    await load()
  } catch (e) {
    emit('error', e)
  } finally {
    busy.value = false
  }
}

async function revoke(link) {
  try {
    await api.revokeShare(link.id)
    await load()
  } catch (e) {
    emit('error', e)
  }
}

async function copy() {
  await navigator.clipboard.writeText(createdUrl.value)
  copied.value = true
}

onMounted(load)
</script>

<template>
  <div class="share-panel">
    <form class="row wrap" @submit.prevent="create">
      <label>
        Expira em
        <select v-model.number="expiresInMinutes">
          <option v-for="e in expirations" :key="e.minutes" :value="e.minutes">{{ e.label }}</option>
        </select>
      </label>
      <label>
        Limite de downloads
        <input v-model="downloadLimit" type="number" min="1" max="1000" placeholder="sem limite" />
      </label>
      <button type="submit" :disabled="busy">Gerar link</button>
    </form>

    <div v-if="createdUrl" class="alert info">
      <p class="small">Copie agora: por segurança, este link não será exibido novamente.</p>
      <div class="row">
        <input :value="createdUrl" readonly @focus="$event.target.select()" />
        <button type="button" @click="copy">{{ copied ? 'Copiado!' : 'Copiar' }}</button>
      </div>
    </div>

    <table v-if="links.length" class="links">
      <thead>
        <tr><th>Criado</th><th>Expira</th><th>Downloads</th><th>Status</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="link in links" :key="link.id">
          <td>{{ formatDate(link.createdAt) }}</td>
          <td>{{ formatDate(link.expiresAt) }}</td>
          <td>{{ link.downloads }}<span v-if="link.downloadLimit"> / {{ link.downloadLimit }}</span></td>
          <td><span class="badge" :class="link.status.toLowerCase()">{{ link.status }}</span></td>
          <td>
            <button v-if="link.status === 'ATIVO'" class="danger small" @click="revoke(link)">Revogar</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
