<script setup>
import { onMounted, ref } from 'vue'
import { api, formatDate, formatSize } from '../api.js'
import SharePanel from './SharePanel.vue'

const emit = defineEmits(['session-expired'])

const MAX_SIZE = 10 * 1024 * 1024
const documents = ref([])
const uploading = ref(false)
const error = ref('')
const openShare = ref(null)
const fileInput = ref(null)

function handle(e) {
  if (e.status === 401) emit('session-expired')
  else error.value = e.message
}

async function load() {
  try {
    documents.value = await api.listDocuments()
  } catch (e) {
    handle(e)
  }
}

async function upload(event) {
  const file = event.target.files[0]
  event.target.value = ''
  if (!file) return
  error.value = ''
  if (file.size > MAX_SIZE) {
    error.value = 'Arquivo excede o limite de 10 MiB.'
    return
  }
  uploading.value = true
  try {
    await api.uploadDocument(file)
    await load()
  } catch (e) {
    handle(e)
  } finally {
    uploading.value = false
  }
}

async function remove(doc) {
  if (!confirm(`Excluir "${doc.name}"? Os links de compartilhamento serão revogados.`)) return
  try {
    await api.deleteDocument(doc.id)
    if (openShare.value === doc.id) openShare.value = null
    await load()
  } catch (e) {
    handle(e)
  }
}

onMounted(load)
</script>

<template>
  <section class="card">
    <div class="row between">
      <h1>Meus documentos</h1>
      <label class="button" :class="{ disabled: uploading }">
        {{ uploading ? 'Enviando…' : 'Enviar documento' }}
        <input
          ref="fileInput" type="file" hidden :disabled="uploading"
          accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" @change="upload"
        />
      </label>
    </div>
    <p class="muted small">PDF, JPEG ou PNG, até 10 MiB. Arquivos ficam em armazenamento privado.</p>
    <p v-if="error" class="alert error">{{ error }}</p>

    <p v-if="documents.length === 0" class="empty">Nenhum documento ainda.</p>
    <ul v-else class="doc-list">
      <li v-for="doc in documents" :key="doc.id">
        <div class="row between wrap">
          <div>
            <strong class="doc-name">{{ doc.name }}</strong>
            <div class="muted small">{{ formatSize(doc.sizeBytes) }} · {{ formatDate(doc.createdAt) }}</div>
          </div>
          <div class="actions">
            <a class="button ghost" :href="api.downloadUrl(doc.id)" rel="noreferrer">Baixar</a>
            <button class="ghost" @click="openShare = openShare === doc.id ? null : doc.id">Compartilhar</button>
            <button class="danger" @click="remove(doc)">Excluir</button>
          </div>
        </div>
        <SharePanel v-if="openShare === doc.id" :document-id="doc.id" @error="handle" />
      </li>
    </ul>
  </section>
</template>
