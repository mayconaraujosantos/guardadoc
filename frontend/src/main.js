import { createApp } from 'vue'
import App from './App.vue'
import { captureInstallPrompt } from './composables/install.js'
import './style.css'

// O evento de instalação dispara cedo; registra antes de montar o app.
captureInstallPrompt()

createApp(App).mount('#app')
