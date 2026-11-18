<script setup>
import { computed, ref, watch } from 'vue'
import { api, fileKind, formatDate, formatSize, haptic } from '../api.js'
import { confirmAction } from '../composables/ui.js'
import BottomSheet from './ui/BottomSheet.vue'
import Icon from './ui/Icon.vue'
import ImageViewer from './ImageViewer.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  doc: { type: Object, default: null },
})
const emit = defineEmits(['update:open', 'share', 'deleted', 'error'])

const isOpen = computed({ get: () => props.open, set: (v) => emit('update:open', v) })
const kind = computed(() => fileKind(props.doc?.mimeType))
const isImage = computed(() => kind.value.tone === 'img')

// Prévia só em memória (object URL), liberada ao fechar; nada vai para cache.
const previewUrl = ref('')
const previewLoading = ref(false)
const viewerOpen = ref(false)
const deleting = ref(false)

function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}

watch(
  () => [props.open, props.doc?.id],
  async ([open]) => {
    if (!open) return
    clearPreview()
    if (!isImage.value) return
    previewLoading.value = true
    try {
      const blob = await api.fetchDocumentBlob(props.doc.id)
      if (props.open) previewUrl.value = URL.createObjectURL(blob)
    } catch {
      /* prévia é opcional */
    } finally {
      previewLoading.value = false
    }
  },
)

async function remove() {
  const ok = await confirmAction({
    title: 'Excluir documento?',
    message: `"${props.doc.name}" será apagado e os links de compartilhamento deixarão de funcionar.`,
    confirmLabel: 'Excluir',
    danger: true,
  })
  if (!ok) return
  deleting.value = true
  try {
    await api.deleteDocument(props.doc.id)
    haptic(20)
    emit('deleted', props.doc)
  } catch (e) {
    emit('error', e)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <BottomSheet v-model:open="isOpen" :title="doc?.name" @closed="clearPreview">
    <template v-if="doc" #header>
      <div class="doc-head">
        <span class="tile lg" :class="kind.tone"><Icon :name="kind.icon" :size="26" /></span>
        <div class="doc-head-text">
          <h2 class="doc-title">{{ doc.name }}</h2>
          <p class="meta">{{ kind.label }} · {{ formatSize(doc.sizeBytes) }} · enviado {{ formatDate(doc.createdAt) }}</p>
        </div>
      </div>
    </template>

    <template v-if="doc">
      <button v-if="isImage" class="preview" :disabled="!previewUrl" aria-label="Ver imagem em tela cheia" @click="viewerOpen = true">
        <img v-if="previewUrl" :src="previewUrl" :alt="doc.name" />
        <span v-else class="preview-loading"><span class="spinner" /></span>
      </button>

      <div class="action-list">
        <button v-if="isImage" class="action" :disabled="!previewUrl" @click="viewerOpen = true">
          <span class="action-icon"><Icon name="eye" /></span>
          <span><strong>Visualizar</strong><small>Abrir em tela cheia</small></span>
        </button>
        <a class="action" :href="api.downloadUrl(doc.id)" rel="noreferrer" download>
          <span class="action-icon"><Icon name="download" /></span>
          <span><strong>Baixar</strong><small>Salvar uma cópia no aparelho</small></span>
        </a>
        <button class="action" @click="emit('share')">
          <span class="action-icon"><Icon name="share" /></span>
          <span><strong>Compartilhar</strong><small>Criar link com validade</small></span>
        </button>
        <button class="action danger" :disabled="deleting" @click="remove">
          <span class="action-icon"><Icon name="trash" /></span>
          <span><strong>Excluir</strong><small>Apaga o arquivo e revoga os links</small></span>
        </button>
      </div>
    </template>
  </BottomSheet>

  <ImageViewer v-model:open="viewerOpen" :src="previewUrl" :alt="doc?.name ?? ''" />
</template>
