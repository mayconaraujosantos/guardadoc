<script setup>
import { computed } from 'vue'
import { confirmState } from '../../composables/ui.js'
import BottomSheet from './BottomSheet.vue'

const open = computed({
  get: () => confirmState.value !== null,
  set: (value) => {
    if (!value) answer(false)
  },
})

function answer(ok) {
  const state = confirmState.value
  if (!state) return
  confirmState.value = null
  state.resolve(ok)
}
</script>

<template>
  <BottomSheet v-model:open="open" :title="confirmState?.title">
    <p v-if="confirmState?.message" class="sheet-text">{{ confirmState.message }}</p>
    <div class="sheet-actions">
      <button
        class="btn block" :class="confirmState?.danger ? 'btn-danger' : 'btn-primary'"
        data-testid="confirmar" @click="answer(true)"
      >
        {{ confirmState?.confirmLabel }}
      </button>
      <button class="btn block btn-ghost" @click="answer(false)">Cancelar</button>
    </div>
  </BottomSheet>
</template>
