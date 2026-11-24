<script setup>
import { computed, ref } from 'vue'
import { api, haptic } from '../api.js'
import { categories, createCategory } from '../composables/categories.js'
import BottomSheet from './ui/BottomSheet.vue'
import Icon from './ui/Icon.vue'

// Escolhe a categoria de um documento (ou cria uma nova e já move para ela).
const props = defineProps({
  open: { type: Boolean, default: false },
  doc: { type: Object, default: null },
})
const emit = defineEmits(['update:open', 'moved', 'error'])

const isOpen = computed({ get: () => props.open, set: (v) => emit('update:open', v) })
const busy = ref(false)
const newName = ref('')

async function choose(categoryId) {
  if (busy.value || !props.doc) return
  if ((props.doc.categoryId ?? null) === categoryId) {
    isOpen.value = false
    return
  }
  busy.value = true
  try {
    const updated = await api.setDocumentCategory(props.doc.id, categoryId)
    haptic(10)
    emit('moved', updated)
    isOpen.value = false
  } catch (e) {
    emit('error', e)
  } finally {
    busy.value = false
  }
}

async function createAndChoose() {
  const name = newName.value.trim()
  if (!name || busy.value) return
  busy.value = true
  let created
  try {
    created = await createCategory(name)
    newName.value = ''
  } catch (e) {
    emit('error', e)
  } finally {
    busy.value = false
  }
  if (created) await choose(created.id)
}
</script>

<template>
  <BottomSheet v-model:open="isOpen" title="Mover para categoria">
    <div class="action-list" role="radiogroup" aria-label="Categoria">
      <button
        class="action" role="radio" :aria-checked="!doc?.categoryId" :disabled="busy" @click="choose(null)"
      >
        <span class="action-icon muted-icon"><Icon name="ban" /></span>
        <span class="grow"><strong>Sem categoria</strong></span>
        <Icon v-if="!doc?.categoryId" name="check" class="check" />
      </button>
      <button
        v-for="c in categories" :key="c.id" class="action" role="radio"
        :aria-checked="doc?.categoryId === c.id" :disabled="busy" @click="choose(c.id)"
      >
        <span class="action-icon"><Icon name="folder" /></span>
        <span class="grow"><strong>{{ c.name }}</strong></span>
        <Icon v-if="doc?.categoryId === c.id" name="check" class="check" />
      </button>
    </div>

    <form class="inline-form picker-new" @submit.prevent="createAndChoose">
      <label class="field grow">
        <span class="sr-only">Nova categoria</span>
        <input v-model="newName" maxlength="40" placeholder="Nova categoria" aria-label="Nova categoria" enterkeyhint="done" />
      </label>
      <button class="btn btn-tonal" type="submit" :disabled="busy || !newName.trim()">Criar e mover</button>
    </form>
  </BottomSheet>
</template>
