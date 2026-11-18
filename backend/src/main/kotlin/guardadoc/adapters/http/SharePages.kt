package guardadoc.adapters.http

import guardadoc.sharing.SharedDocument
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Páginas de compartilhamento: HTML autocontido, sem recursos de terceiros. */
internal object SharePages {
    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; " +
        "form-action 'none'; frame-ancestors 'none'"

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneId.of("UTC"))

    fun valid(token: String, shared: SharedDocument): String {
        val doc = shared.document
        val link = shared.link
        val remaining = link.downloadLimit?.let { "<li>Downloads restantes: ${it - link.downloads}</li>" } ?: ""
        return layout(
            """
            <h1>Documento compartilhado</h1>
            <p class="name">${escapeHtml(doc.originalName)}</p>
            <ul>
              <li>Tamanho: ${humanSize(doc.sizeBytes)}</li>
              <li>Disponível até: ${dateFormat.format(link.expiresAt)}</li>
              $remaining
            </ul>
            <a class="btn" href="/s/$token/download" rel="noreferrer">Baixar documento</a>
            <p class="hint">Acesso somente leitura. Não compartilhe este link com quem não deve ver o documento.</p>
            """,
        )
    }

    fun invalid(): String = layout(
        """
        <h1>Link indisponível</h1>
        <p>Este link é inválido, expirou, foi revogado ou atingiu o limite de downloads.</p>
        """,
    )

    private fun layout(body: String) = """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="referrer" content="no-referrer">
          <meta name="robots" content="noindex, nofollow">
          <title>GuardaDoc – compartilhamento</title>
          <style>
            body { font-family: system-ui, sans-serif; background: #f1f5f9; color: #0f172a; margin: 0;
                   display: flex; min-height: 100vh; align-items: center; justify-content: center; }
            main { background: #fff; padding: 2rem; border-radius: 12px; max-width: 28rem; width: 90%;
                   box-shadow: 0 4px 20px rgba(0,0,0,.08); }
            h1 { font-size: 1.3rem; color: #1e3a8a; margin-top: 0; }
            .name { font-weight: 600; word-break: break-all; }
            .btn { display: inline-block; background: #1d4ed8; color: #fff; padding: .7rem 1.2rem;
                   border-radius: 8px; text-decoration: none; margin-top: .5rem; }
            .hint { color: #64748b; font-size: .85rem; }
          </style>
        </head>
        <body><main>$body</main></body>
        </html>
    """.trimIndent()
}
