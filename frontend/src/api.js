// Cliente da API. O token CSRF fica apenas em memória (nunca em localStorage);
// a sessão vive em cookie HttpOnly gerenciado pelo navegador.
let csrfToken = null

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

const OFFLINE_MESSAGE = 'Sem conexão. Verifique sua internet e tente novamente.'

function logApiFailure(method, path, details) {
  console.error('[GuardaDoc] Falha na API', {
    method,
    path,
    origin: window.location.origin,
    page: window.location.pathname,
    userAgent: navigator.userAgent,
    ...details,
  })
}

async function request(method, path, { json, body } = {}) {
  const headers = {}
  if (json !== undefined) headers['Content-Type'] = 'application/json'
  if (csrfToken && method !== 'GET') headers['X-CSRF-Token'] = csrfToken
  let response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: json !== undefined ? JSON.stringify(json) : body,
      credentials: 'same-origin',
      cache: 'no-store',
    })
  } catch (cause) {
    logApiFailure(method, path, {
      phase: 'network',
      errorName: cause instanceof Error ? cause.name : 'UnknownError',
    })
    throw new ApiError(0, OFFLINE_MESSAGE)
  }
  if (response.status === 204) return null
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    const message = data?.error ?? messageFor(response.status)
    if (!(path === '/api/me' && response.status === 401)) {
      logApiFailure(method, path, { phase: 'response', status: response.status, message })
    }
    throw new ApiError(response.status, message)
  }
  return data
}

function messageFor(status) {
  if (status === 413) return 'Arquivo excede o limite de 10 MiB.'
  if (status === 429) return 'Muitas tentativas. Aguarde alguns minutos.'
  if (status >= 500) return 'O servidor não respondeu. Tente novamente.'
  return `Erro ${status}`
}

function setSession(me) {
  csrfToken = me?.csrfToken ?? null
  return me
}

export const api = {
  async me() {
    try {
      return setSession(await request('GET', '/api/me'))
    } catch (e) {
      if (e.status === 401) return setSession(null)
      throw e
    }
  },
  requestOtp: (email) => request('POST', '/api/auth/otp', { json: { email } }),
  async verifyOtp(email, code) {
    return setSession(await request('POST', '/api/auth/otp/verify', { json: { email, code } }))
  },
  async logout() {
    await request('POST', '/api/auth/logout')
    setSession(null)
  },
  async deleteAccount() {
    await request('DELETE', '/api/me')
    setSession(null)
  },
  listDocuments: () => request('GET', '/api/documents'),

  /** Upload com progresso (fetch ainda não expõe progresso de envio). */
  uploadDocument(file, onProgress, categoryId = null) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/documents')
      xhr.withCredentials = true
      if (csrfToken) xhr.setRequestHeader('X-CSRF-Token', csrfToken)
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) onProgress?.(e.loaded / e.total)
      }
      xhr.onload = () => {
        let data = null
        try {
          data = JSON.parse(xhr.responseText)
        } catch {
          /* resposta sem JSON */
        }
        if (xhr.status >= 200 && xhr.status < 300) resolve(data)
        else reject(new ApiError(xhr.status, data?.error ?? messageFor(xhr.status)))
      }
      xhr.onerror = () => reject(new ApiError(0, OFFLINE_MESSAGE))
      const form = new FormData()
      if (categoryId) form.append('categoryId', categoryId)
      form.append('file', file)
      xhr.send(form)
    })
  },

  setDocumentCategory: (id, categoryId) => request('PATCH', `/api/documents/${id}`, { json: { categoryId } }),
  deleteDocument: (id) => request('DELETE', `/api/documents/${id}`),
  downloadUrl: (id) => `/api/documents/${id}/download`,

  /** Conteúdo do documento só em memória (prévia); nunca vai para cache. */
  async fetchDocumentBlob(id) {
    let response
    try {
      response = await fetch(`/api/documents/${id}/download`, { credentials: 'same-origin', cache: 'no-store' })
    } catch {
      throw new ApiError(0, OFFLINE_MESSAGE)
    }
    if (!response.ok) throw new ApiError(response.status, messageFor(response.status))
    return response.blob()
  },

  listCategories: () => request('GET', '/api/categories'),
  createCategory: (name) => request('POST', '/api/categories', { json: { name } }),
  renameCategory: (id, name) => request('PATCH', `/api/categories/${id}`, { json: { name } }),
  deleteCategory: (id) => request('DELETE', `/api/categories/${id}`),

  listShares: (documentId) => request('GET', `/api/documents/${documentId}/shares`),
  createShare: (documentId, expiresInMinutes, downloadLimit) =>
    request('POST', `/api/documents/${documentId}/shares`, {
      json: { expiresInMinutes, downloadLimit: downloadLimit || null },
    }),
  revokeShare: (id) => request('DELETE', `/api/shares/${id}`),
}

export function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} bytes`
}

export function formatDate(iso) {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

const rtf = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' })

/** "há 5 minutos", "em 3 dias", "ontem"... */
export function formatRelative(iso, now = Date.now()) {
  const diff = (new Date(iso).getTime() - now) / 1000
  const abs = Math.abs(diff)
  if (abs < 60) return diff < 0 ? 'agora há pouco' : 'em instantes'
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute')
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour')
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / 86400), 'day')
  return formatDate(iso)
}

export function fileKind(mimeType) {
  if (mimeType === 'application/pdf') return { label: 'PDF', icon: 'file', tone: 'pdf' }
  if (mimeType?.startsWith('image/')) return { label: mimeType === 'image/png' ? 'PNG' : 'JPEG', icon: 'image', tone: 'img' }
  return { label: 'Arquivo', icon: 'file', tone: 'other' }
}

/** Vibração curta de confirmação (quando o aparelho suporta). */
export function haptic(pattern = 10) {
  try {
    navigator.vibrate?.(pattern)
  } catch {
    /* sem suporte */
  }
}
