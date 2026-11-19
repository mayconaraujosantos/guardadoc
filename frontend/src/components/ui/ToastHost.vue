<script setup>
import { dismissToast, toasts } from '../../composables/ui.js'
import Icon from './Icon.vue'

const ICONS = { success: 'check', error: 'alert', info: 'info' }

function run(t) {
  t.action?.run()
  dismissToast(t.id)
}
</script>

<template>
  <div class="toast-host" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.type" @click="dismissToast(t.id)">
        <Icon :name="ICONS[t.type]" :size="20" />
        <span class="toast-text">{{ t.message }}</span>
        <button v-if="t.action" class="toast-action" @click.stop="run(t)">{{ t.action.label }}</button>
      </div>
    </TransitionGroup>
  </div>
</template>
