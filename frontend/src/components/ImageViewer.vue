<script setup>
import { onBeforeUnmount, watch } from 'vue'
import { pushBack } from '../composables/backStack.js'
import Icon from './ui/Icon.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
})
const emit = defineEmits(['update:open'])

let release = null
watch(
  () => props.open,
  (open) => {
    if (open) {
      release = pushBack(() => {
        release = null
        emit('update:open', false)
      })
    } else {
      release?.()
      release = null
    }
  },
)
onBeforeUnmount(() => release?.())
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="open" class="viewer" role="dialog" aria-modal="true" :aria-label="alt"
        @keydown.esc.stop="emit('update:open', false)"
      >
        <button class="icon-btn viewer-close" aria-label="Fechar" @click="emit('update:open', false)">
          <Icon name="x" :size="26" />
        </button>
        <img :src="src" :alt="alt" />
      </div>
    </Transition>
  </Teleport>
</template>
