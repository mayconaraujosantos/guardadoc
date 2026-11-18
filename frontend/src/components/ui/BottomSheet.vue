<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { pushBack } from '../../composables/backStack.js'
import { layerClosed, layerOpened } from '../../composables/ui.js'

// Painel que sobe do rodapé (no desktop vira um diálogo central).
// Fecha por: arrastar para baixo, tocar fora, Esc ou o "voltar" do celular.
const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
})
const emit = defineEmits(['update:open', 'closed'])

const panel = ref(null)
const dragY = ref(0)
const dragging = ref(false)
let release = null
let startY = 0
let returnFocus = null

const zIndex = ref(100)
let layered = false

function lockScroll(lock) {
  if (lock && !layered) {
    zIndex.value = layerOpened()
    layered = true
  } else if (!lock && layered) {
    layerClosed()
    layered = false
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      returnFocus = document.activeElement
      release = pushBack(() => {
        release = null
        emit('update:open', false)
      })
      lockScroll(true)
      await nextTick()
      panel.value?.focus({ preventScroll: true })
    } else {
      dragY.value = 0
      release?.()
      release = null
      lockScroll(false)
      returnFocus?.focus?.({ preventScroll: true })
    }
  },
  { immediate: false },
)

onBeforeUnmount(() => {
  release?.()
  lockScroll(false)
})

function close() {
  emit('update:open', false)
}

function onPointerDown(event) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  dragging.value = true
  startY = event.clientY
  event.currentTarget.setPointerCapture(event.pointerId)
}
function onPointerMove(event) {
  if (!dragging.value) return
  dragY.value = Math.max(0, event.clientY - startY)
}
function onPointerUp() {
  if (!dragging.value) return
  dragging.value = false
  if (dragY.value > 90) close()
  else dragY.value = 0
}

const panelStyle = computed(() =>
  dragY.value ? { transform: `translateY(${dragY.value}px)`, transition: dragging.value ? 'none' : undefined } : {},
)
</script>

<template>
  <Teleport to="body">
    <Transition name="sheet" @after-leave="emit('closed')">
      <div v-if="open" class="sheet-root" :style="{ zIndex }" @keydown.esc.stop="close">
        <div class="sheet-backdrop" @click="close" />
        <section
          ref="panel" class="sheet" role="dialog" aria-modal="true" :aria-label="title || undefined"
          tabindex="-1" :style="panelStyle"
        >
          <div
            class="sheet-grip" @pointerdown="onPointerDown" @pointermove="onPointerMove"
            @pointerup="onPointerUp" @pointercancel="onPointerUp"
          >
            <span />
          </div>
          <header v-if="title || $slots.header" class="sheet-header">
            <slot name="header"><h2>{{ title }}</h2></slot>
          </header>
          <div class="sheet-body">
            <slot />
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
