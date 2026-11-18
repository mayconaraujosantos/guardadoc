<script setup>
import { computed, ref, watch } from 'vue'
import { api, formatRelative, haptic } from '../api.js'
import { confirmAction, toast } from '../composables/ui.js'
import BottomSheet from './ui/BottomSheet.vue'
import Icon from './ui/Icon.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  doc: { type: Object, default: null },
})
const emit = defineEmits(['update:open', 'error'])

const isOpen = computed({ get: () => props.open, set: (v) => emit('update:open', v) })

const EXPIRATIONS = [
  { label: '1 hora', minutes: 60 },
  { label: '24 horas', minutes: 60 * 24 },
  { label: '7 dias', minutes: 60 * 24 * 7 },
  { label: '30 dias', minutes: 60 * 24 * 30 },
]
const STATUS = {
  ATIVO: 'Ativo',
  EXPIRADO: 'Expirado',
  REVOGADO: 'Revogado',
  ESGOTADO: 'Esgotado',
}

const expiresInMinutes = ref(60 * 24)
const limitEnabled = ref(false)
const downloadLimit = ref(1)
const links = ref([])
const loading = ref(false)
const creating = ref(false)
const createdUrl = ref('')
const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function'

const activeLinks = computed(() => links.value.filter((l) => l.status === 'ATIVO'))
const pastLinks = computed(() => links.value.filter((l) => l.status !== 'ATIVO'))

watch(
  () => [props.open, props.doc?.id],
  ([open]) => {
    if (!open) return
    createdUrl.value = ''
    void load()
  },
)

async function load() {
  loading.value = true
  try {
    links.value = await api.listShares(props.doc.id)
  } catch (e) {
    emit('error', e)
  } finally {
    loading.value = false
  }
}

function step(delta) {
  downloadLimit.value = Math.min(1000, Math.max(1, Number(downloadLimit.value || 1) + delta))
}

async function create() {
  creating.value = true
  try {
    const limit = limitEnabled.value ? Number(downloadLimit.value) : null
    const res = await api.createShare(props.doc.id, expiresInMinutes.value, limit)
    createdUrl.value = res.url
    haptic(15)
    await load()
  } catch (e) {
    emit('error', e)
  } finally {
    creating.value = false
  }
}

async function shareNative() {
  try {
    await navigator.share({ title: props.doc.name, text: `Documento compartilhado: ${props.doc.name}`, url: createdUrl.value })
  } catch (e) {
    if (e?.name !== 'AbortError') await copy()
  }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(createdUrl.value)
    haptic(10)
    toast('Link copiado.', { type: 'success' })
  } catch {
    toast('Não foi possível copiar. Selecione o link e copie manualmente.', { type: 'error' })
  }
}

async function revoke(link) {
  const ok = await confirmAction({
    title: 'Revogar link?',
    message: 'Quem tiver o link não conseguirá mais acessar o documento. Esta ação não pode ser desfeita.',
    confirmLabel: 'Revogar',
    danger: true,
  })
  if (!ok) return
  try {
    await api.revokeShare(link.id)
    haptic(15)
    toast('Link revogado.', { type: 'success' })
    await load()
  } catch (e) {
    emit('error', e)
  }
}

function linkDetail(link) {
  const downloads = link.downloadLimit
    ? `${link.downloads}/${link.downloadLimit} downloads`
    : `${link.downloads} ${link.downloads === 1 ? 'download' : 'downloads'}`
  if (link.status === 'ATIVO') return `Expira ${formatRelative(link.expiresAt)} · ${downloads}`
  if (link.status === 'REVOGADO') return `Revogado ${formatRelative(link.revokedAt)} · ${downloads}`
  if (link.status === 'EXPIRADO') return `Expirou ${formatRelative(link.expiresAt)} · ${downloads}`
  return downloads
}
</script>

<template>
  <BottomSheet v-model:open="isOpen" title="Compartilhar">
    <div v-if="doc" class="share">
      <p class="muted small">
        Qualquer pessoa com o link poderá baixar <strong>{{ doc.name }}</strong> até ele expirar ou ser revogado.
      </p>

      <!-- Link recém-criado -->
      <Transition name="fade">
        <div v-if="createdUrl" class="created">
          <p class="created-title"><Icon name="check" :size="18" /> Link criado</p>
          <input class="share-url" :value="createdUrl" readonly aria-label="Link de compartilhamento" @focus="$event.target.select()" />
          <div class="created-actions">
            <button v-if="canShare" class="btn btn-primary" @click="shareNative"><Icon name="share" :size="18" /> Enviar</button>
            <button class="btn" :class="canShare ? 'btn-tonal' : 'btn-primary'" @click="copy"><Icon name="copy" :size="18" /> Copiar</button>
          </div>
          <p class="hint">Por segurança, este link não será exibido novamente. Guarde ou envie agora.</p>
        </div>
      </Transition>

      <!-- Novo link -->
      <form v-if="!createdUrl" class="stack" @submit.prevent="create">
        <fieldset class="field">
          <legend>Expira em</legend>
          <div class="chips" role="radiogroup" aria-label="Expira em">
            <button
              v-for="e in EXPIRATIONS" :key="e.minutes" type="button" class="chip" role="radio"
              :aria-checked="expiresInMinutes === e.minutes" :class="{ on: expiresInMinutes === e.minutes }"
              @click="expiresInMinutes = e.minutes"
            >
              {{ e.label }}
            </button>
          </div>
        </fieldset>

        <label class="switch-row">
          <span><strong>Limitar downloads</strong><small>O link para de funcionar ao atingir o limite</small></span>
          <input v-model="limitEnabled" type="checkbox" role="switch" class="switch" />
        </label>

        <div v-if="limitEnabled" class="stepper">
          <button type="button" class="icon-btn" aria-label="Diminuir" @click="step(-1)"><Icon name="minus" /></button>
          <label>
            <span class="sr-only">Limite de downloads</span>
            <input v-model.number="downloadLimit" type="number" inputmode="numeric" min="1" max="1000" aria-label="Limite de downloads" />
          </label>
          <button type="button" class="icon-btn" aria-label="Aumentar" @click="step(1)"><Icon name="plus" /></button>
        </div>

        <button type="submit" class="btn btn-primary block" :disabled="creating">
          <span v-if="creating" class="spinner" /><Icon v-else name="link" :size="18" /> Gerar link
        </button>
      </form>
      <button v-else class="btn btn-ghost block" @click="createdUrl = ''">Criar outro link</button>

      <!-- Links existentes -->
      <section v-if="links.length" class="links">
        <h3>Links ativos <span class="count">{{ activeLinks.length }}</span></h3>
        <p v-if="!activeLinks.length" class="muted small">Nenhum link ativo.</p>
        <ul class="link-list">
          <li v-for="link in activeLinks" :key="link.id" class="link-item">
            <span class="link-icon"><Icon name="link" :size="18" /></span>
            <span class="lines">
              <span class="badge ativo">{{ STATUS[link.status] }}</span>
              <span class="meta">{{ linkDetail(link) }}</span>
            </span>
            <button class="btn btn-ghost danger-text" @click="revoke(link)">Revogar</button>
          </li>
        </ul>
        <details v-if="pastLinks.length" class="past">
          <summary>Histórico ({{ pastLinks.length }})</summary>
          <ul class="link-list">
            <li v-for="link in pastLinks" :key="link.id" class="link-item inactive">
              <span class="link-icon"><Icon name="ban" :size="18" /></span>
              <span class="lines">
                <span class="badge" :class="link.status.toLowerCase()">{{ STATUS[link.status] }}</span>
                <span class="meta">{{ linkDetail(link) }}</span>
              </span>
            </li>
          </ul>
        </details>
      </section>
      <p v-else-if="loading" class="muted small center"><span class="spinner dark" /></p>
    </div>
  </BottomSheet>
</template>
