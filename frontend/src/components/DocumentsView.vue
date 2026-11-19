<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, fileKind, formatRelative, formatSize, haptic } from '../api.js'
import { toast } from '../composables/ui.js'
import BottomSheet from './ui/BottomSheet.vue'
import Icon from './ui/Icon.vue'
import DocumentSheet from './DocumentSheet.vue'
import ShareSheet from './ShareSheet.vue'

const props = defineProps({ initialAction: { type: String, default: '' } })
const emit = defineEmits(['session-expired'])

const MAX_SIZE = 10 * 1024 * 1024
const ALLOWED = new Set(['application/pdf', 'image/jpeg', 'image/png'])

const documents = ref([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const searching = ref(false)
const searchInput = ref(null)

const addOpen = ref(props.initialAction === 'foto' || props.initialAction === 'enviar')
const selected = ref(null)
const detailOpen = ref(false)
const shareOpen = ref(false)
const dragOver = ref(false)

const cameraInput = ref(null)
const galleryInput = ref(null)
const fileInput = ref(null)

const uploads = reactive([])
let uploadId = 0

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  return q ? documents.value.filter((d) => d.name.toLowerCase().includes(q)) : documents.value
})
const totalSize = computed(() => documents.value.reduce((sum, d) => sum + d.sizeBytes, 0))

function handle(e) {
  if (e.status === 401) emit('session-expired')
  else toast(e.message, { type: 'error' })
}

async function load({ silent = false } = {}) {
  if (!silent) loading.value = true
  loadError.value = ''
  try {
    documents.value = await api.listDocuments()
  } catch (e) {
    if (e.status === 401) emit('session-expired')
    else loadError.value = e.message
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------- upload

/** @param {HTMLInputElement | null} input o elemento (o template já desembrulha o ref) */
function pick(input) {
  addOpen.value = false
  // Abre o seletor no mesmo gesto do toque (exigência dos navegadores móveis).
  input?.click()
}

function niceName(file, fromCamera) {
  if (!fromCamera) return file
  const stamp = new Date().toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).replace(/[/:]/g, '-').replace(',', '')
  const ext = file.type === 'image/png' ? 'png' : 'jpg'
  return new File([file], `Foto ${stamp}.${ext}`, { type: file.type })
}

async function onFiles(event, fromCamera = false) {
  const files = Array.from(event.target.files ?? [])
  event.target.value = ''
  await uploadAll(files.map((f) => niceName(f, fromCamera)))
}

async function uploadAll(files) {
  // Em sequência, de propósito: um envio por vez mantém o progresso legível
  // e não satura conexões móveis.
  for (const file of files) {
    if (!ALLOWED.has(file.type)) {
      toast(`"${file.name}": envie PDF, JPEG ou PNG.`, { type: 'error' })
      continue
    }
    if (file.size > MAX_SIZE) {
      toast(`"${file.name}" passa de 10 MB.`, { type: 'error' })
      continue
    }
    const item = reactive({ id: ++uploadId, name: file.name, progress: 0, status: 'sending' })
    uploads.push(item)
    try {
      await api.uploadDocument(file, (p) => (item.progress = p))
      item.status = 'done'
      item.progress = 1
      haptic(12)
      await load({ silent: true })
    } catch (e) {
      item.status = 'error'
      handle(e)
    } finally {
      setTimeout(() => uploads.splice(uploads.indexOf(item), 1), item.status === 'done' ? 900 : 2500)
    }
  }
}

// ------------------------------------------------- arrastar e soltar (desktop)

function onDragOver(event) {
  if (event.dataTransfer?.types?.includes('Files')) {
    event.preventDefault()
    dragOver.value = true
  }
}
function onDrop(event) {
  event.preventDefault()
  dragOver.value = false
  void uploadAll(Array.from(event.dataTransfer?.files ?? []))
}

// -------------------------------------------------- detalhes e ações

function open(doc) {
  selected.value = doc
  detailOpen.value = true
}

function onDeleted(doc) {
  documents.value = documents.value.filter((d) => d.id !== doc.id)
  detailOpen.value = false
  toast('Documento excluído.', { type: 'success' })
}

function openShare() {
  shareOpen.value = true
}

async function toggleSearch() {
  searching.value = !searching.value
  if (!searching.value) query.value = ''
  else {
    await Promise.resolve()
    searchInput.value?.focus()
  }
}

// ----------------------------------------------------- puxar p/ atualizar

const root = ref(null)
const pull = ref(0)
const refreshing = ref(false)
let startY = null

function onTouchStart(e) {
  // Só na aba de documentos visível (a aba fica montada com v-show).
  if (!root.value || root.value.offsetParent === null) {
    startY = null
    return
  }
  startY = window.scrollY <= 0 && !document.documentElement.classList.contains('scroll-locked') ? e.touches[0].clientY : null
}
function onTouchMove(e) {
  if (startY === null) return
  const dy = e.touches[0].clientY - startY
  pull.value = dy > 0 ? Math.min(dy * 0.5, 90) : 0
}
async function onTouchEnd() {
  if (startY === null) return
  startY = null
  if (pull.value >= 60 && !refreshing.value) {
    refreshing.value = true
    pull.value = 56
    haptic(8)
    await load({ silent: true })
    refreshing.value = false
  }
  pull.value = 0
}

onMounted(() => {
  void load()
  window.addEventListener('touchstart', onTouchStart, { passive: true })
  window.addEventListener('touchmove', onTouchMove, { passive: true })
  window.addEventListener('touchend', onTouchEnd)
})
onBeforeUnmount(() => {
  window.removeEventListener('touchstart', onTouchStart)
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('touchend', onTouchEnd)
})

defineExpose({ toggleSearch, reload: () => load({ silent: true }) })
</script>

<template>
  <div ref="root" class="page" @dragover="onDragOver" @dragleave.self="dragOver = false" @drop="onDrop">
    <div class="ptr" :style="{ height: `${pull}px` }" aria-hidden="true">
      <span class="ptr-spinner" :class="{ spinning: refreshing, ready: pull >= 60 }"><Icon name="download" :size="18" /></span>
    </div>

    <Transition name="fade">
      <div v-if="searching" class="search">
        <Icon name="search" :size="20" />
        <input
          ref="searchInput" v-model="query" type="search" placeholder="Buscar documentos"
          aria-label="Buscar documentos" enterkeyhint="search"
        />
        <button class="icon-btn" aria-label="Fechar busca" @click="toggleSearch"><Icon name="x" :size="20" /></button>
      </div>
    </Transition>

    <h1 class="page-title">Meus documentos</h1>
    <p v-if="!loading && documents.length" class="page-sub">
      {{ documents.length }} {{ documents.length === 1 ? 'documento' : 'documentos' }} · {{ formatSize(totalSize) }}
    </p>

    <!-- Carregando -->
    <ul v-if="loading" class="list" aria-busy="true" aria-label="Carregando documentos">
      <li v-for="i in 4" :key="i" class="row skeleton">
        <span class="tile" /><span class="lines"><span /><span /></span>
      </li>
    </ul>

    <!-- Erro de carregamento -->
    <div v-else-if="loadError" class="empty">
      <div class="empty-art"><Icon name="offline" :size="40" /></div>
      <h2>Não foi possível carregar</h2>
      <p>{{ loadError }}</p>
      <button class="btn btn-primary" @click="load()">Tentar novamente</button>
    </div>

    <!-- Vazio -->
    <div v-else-if="documents.length === 0" class="empty">
      <div class="empty-art"><Icon name="folder" :size="40" /></div>
      <h2>Nenhum documento ainda.</h2>
      <p>Fotografe ou envie RG, CNH, comprovantes e outros documentos. Eles ficam em armazenamento privado.</p>
      <div class="empty-actions">
        <button class="btn btn-primary" @click="pick(cameraInput)"><Icon name="camera" :size="20" /> Tirar foto</button>
        <button class="btn btn-tonal" @click="pick(fileInput)"><Icon name="upload" :size="20" /> Enviar arquivo</button>
      </div>
    </div>

    <!-- Lista -->
    <template v-else>
      <p v-if="filtered.length === 0" class="muted center">Nenhum documento encontrado para "{{ query }}".</p>
      <TransitionGroup tag="ul" name="list" class="list">
        <li v-for="doc in filtered" :key="doc.id">
          <button class="row" :aria-label="`Abrir ${doc.name}`" @click="open(doc)">
            <span class="tile" :class="fileKind(doc.mimeType).tone"><Icon :name="fileKind(doc.mimeType).icon" /></span>
            <span class="lines">
              <strong class="doc-name">{{ doc.name }}</strong>
              <span class="meta">{{ fileKind(doc.mimeType).label }} · {{ formatSize(doc.sizeBytes) }} · {{ formatRelative(doc.createdAt) }}</span>
            </span>
            <Icon name="more" class="row-end" />
          </button>
        </li>
      </TransitionGroup>
    </template>

    <!-- Fila de envio -->
    <output v-if="uploads.length" class="uploads" aria-live="polite">
      <div v-for="u in uploads" :key="u.id" class="upload" :class="u.status">
        <Icon :name="u.status === 'done' ? 'check' : u.status === 'error' ? 'alert' : 'upload'" :size="20" />
        <div class="upload-info">
          <span class="upload-name">{{ u.name }}</span>
          <span class="progress"><span :style="{ width: `${Math.round(u.progress * 100)}%` }" /></span>
        </div>
        <span class="upload-pct">{{ u.status === 'error' ? 'Falhou' : `${Math.round(u.progress * 100)}%` }}</span>
      </div>
    </output>

    <button class="fab" aria-label="Adicionar documento" data-testid="adicionar" @click="addOpen = true">
      <Icon name="plus" :size="26" />
    </button>

    <div v-if="dragOver" class="dropzone"><Icon name="upload" :size="40" /><span>Solte para enviar</span></div>

    <!-- Entradas de arquivo (ocultas) -->
    <input
      ref="cameraInput" type="file" accept="image/*" capture="environment" hidden aria-label="Tirar foto"
      data-testid="camera-input" @change="onFiles($event, true)"
    />
    <input ref="galleryInput" type="file" accept="image/jpeg,image/png" multiple hidden aria-label="Escolher da galeria" @change="onFiles($event)" />
    <input
      ref="fileInput" type="file" multiple hidden aria-label="Escolher arquivo" data-testid="arquivo-input"
      accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" @change="onFiles($event)"
    />

    <BottomSheet v-model:open="addOpen" title="Adicionar documento">
      <div class="action-list">
        <button class="action" @click="pick(cameraInput)">
          <span class="action-icon"><Icon name="camera" /></span>
          <span><strong>Tirar foto</strong><small>Use a câmera para fotografar o documento</small></span>
        </button>
        <button class="action" @click="pick(galleryInput)">
          <span class="action-icon"><Icon name="gallery" /></span>
          <span><strong>Escolher da galeria</strong><small>Imagens JPEG ou PNG</small></span>
        </button>
        <button class="action" @click="pick(fileInput)">
          <span class="action-icon"><Icon name="file" /></span>
          <span><strong>Escolher arquivo</strong><small>PDF, JPEG ou PNG, até 10 MB</small></span>
        </button>
      </div>
    </BottomSheet>

    <DocumentSheet
      v-model:open="detailOpen" :doc="selected" @share="openShare" @deleted="onDeleted" @error="handle"
    />
    <ShareSheet v-model:open="shareOpen" :doc="selected" @error="handle" />
  </div>
</template>
