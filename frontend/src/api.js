// Cliente da API. O token CSRF fica apenas em memória (nunca em localStorage);
// a sessão vive em cookie HttpOnly gerenciado pelo navegador.
let csrfToken = null

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function request(method, path, { json, body } = {}) {
  const headers = {}
  if (json !== undefined) headers['Content-Type'] = 'application/json'
  if (csrfToken && method !== 'GET') headers['X-CSRF-Token'] = csrfToken
  const response = await fetch(path, {
    method,
    headers,
    body: json !== undefined ? JSON.stringify(json) : body,
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (response.status === 204) return null
  const data = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(response.status, data?.error ?? `Erro ${response.status}`)
  return data
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
  uploadDocument(file) {
    const form = new FormData()
    form.append('file', file)
    return request('POST', '/api/documents', { body: form })
  },
  deleteDocument: (id) => request('DELETE', `/api/documents/${id}`),
  downloadUrl: (id) => `/api/documents/${id}/download`,
  listShares: (documentId) => request('GET', `/api/documents/${documentId}/shares`),
  createShare: (documentId, expiresInMinutes, downloadLimit) =>
    request('POST', `/api/documents/${documentId}/shares`, {
      json: { expiresInMinutes, downloadLimit: downloadLimit || null },
    }),
  revokeShare: (id) => request('DELETE', `/api/shares/${id}`),
}

export function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${bytes} bytes`
}

export function formatDate(iso) {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}
