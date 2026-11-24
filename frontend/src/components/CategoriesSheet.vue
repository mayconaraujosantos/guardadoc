<script setup>
import { computed, nextTick, ref } from 'vue'
import { haptic } from '../api.js'
import { categories, createCategory, deleteCategory, renameCategory, unusedSuggestions } from '../composables/categories.js'
import { confirmAction, toast } from '../composables/ui.js'
import BottomSheet from './ui/BottomSheet.vue'
import Icon from './ui/Icon.vue'

// Criar, renomear e excluir categorias.
const props = defineProps({
  open: { type: Boolean, default: false },
  /** Quantidade de documentos por id de categoria. */
  counts: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['update:open', 'deleted', 'error'])

const isOpen = computed({ get: () => props.open, set: (v) => emit('update:open', v) })
const suggestions = computed(unusedSuggestions)

const newName = ref('')
const busy = ref(false)
const editingId = ref(null)
const editName = ref('')
const editInput = ref(null)

function plural(n) {
  return n === 1 ? '1 documento' : `${n} documentos`
}

async function create(name = newName.value) {
  if (!name.trim() || busy.value) return
  busy.value = true
  try {
    await createCategory(name)
    haptic(10)
    if (name === newName.value) newName.value = ''
  } catch (e) {
    emit('error', e)
  } finally {
    busy.value = false
  }
}

async function startEdit(category) {
  editingId.value = category.id
  editName.value = category.name
  await nextTick()
  editInput.value?.[0]?.focus()
}

async function saveEdit(category) {
  const name = editName.value.trim()
  if (!name || name === category.name) {
    editingId.value = null
    return
  }
  busy.value = true
  try {
    await renameCategory(category.id, name)
    editingId.value = null
  } catch (e) {
    emit('error', e)
  } finally {
    busy.value = false
  }
}

async function remove(category) {
  const n = props.counts[category.id] ?? 0
  const ok = await confirmAction({
    title: `Excluir "${category.name}"?`,
    message: n ? `Os documentos não serão apagados: ${plural(n)} ficarão sem categoria.` : 'A categoria está vazia.',
    confirmLabel: 'Excluir categoria',
    danger: true,
  })
  if (!ok) return
  try {
    await deleteCategory(category.id)
    haptic(20)
    toast('Categoria excluída.', { type: 'success' })
    emit('deleted', category)
  } catch (e) {
    emit('error', e)
  }
}
</script>

<template>
  <BottomSheet v-model:open="isOpen" title="Categorias">
    <div class="categories">
      <form class="inline-form" @submit.prevent="create()">
        <label class="field grow">
          <span class="sr-only">Nome da nova categoria</span>
          <input v-model="newName" maxlength="40" placeholder="Nova categoria" aria-label="Nome da nova categoria" enterkeyhint="done" />
        </label>
        <button class="btn btn-primary" type="submit" :disabled="busy || !newName.trim()">Criar</button>
      </form>

      <section v-if="suggestions.length" aria-label="Sugestões">
        <p class="section-label">Sugestões</p>
        <div class="chips">
          <button
            v-for="s in suggestions" :key="s" type="button" class="chip" :disabled="busy"
            :aria-label="`Criar categoria ${s}`" @click="create(s)"
          >
            <Icon name="plus" :size="16" /> {{ s }}
          </button>
        </div>
      </section>

      <section aria-label="Suas categorias">
        <p class="section-label">Suas categorias</p>
        <p v-if="!categories.length" class="muted">Nenhuma categoria ainda. Crie uma acima ou toque numa sugestão.</p>
        <ul class="cat-list">
          <li v-for="c in categories" :key="c.id" class="cat-item">
            <span class="action-icon"><Icon name="folder" /></span>
            <form v-if="editingId === c.id" class="inline-form grow" @submit.prevent="saveEdit(c)">
              <input
                ref="editInput" v-model="editName" class="inline-input" maxlength="40"
                :aria-label="`Novo nome para ${c.name}`" @keydown.esc.stop="editingId = null"
              />
              <button class="icon-btn" type="submit" aria-label="Salvar nome"><Icon name="check" :size="20" /></button>
            </form>
            <template v-else>
              <span class="grow">
                <strong>{{ c.name }}</strong>
                <small class="muted">{{ plural(counts[c.id] ?? 0) }}</small>
              </span>
              <button class="icon-btn" :aria-label="`Renomear ${c.name}`" @click="startEdit(c)"><Icon name="edit" :size="20" /></button>
              <button class="icon-btn danger" :aria-label="`Excluir ${c.name}`" @click="remove(c)"><Icon name="trash" :size="20" /></button>
            </template>
          </li>
        </ul>
      </section>
    </div>
  </BottomSheet>
</template>
