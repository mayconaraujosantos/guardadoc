package guardadoc.adapters.persistence

import guardadoc.sharing.ShareLink
import guardadoc.sharing.ShareRepository
import guardadoc.sharing.SharedDocument
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

private const val LINK_COLUMNS =
    "l.id AS l_id, l.documento_id AS l_documento_id, l.criado_por AS l_criado_por, l.expira_em AS l_expira_em, " +
        "l.revogado_em AS l_revogado_em, l.limite_downloads AS l_limite_downloads, " +
        "l.downloads AS l_downloads, l.criado_em AS l_criado_em"

private fun ResultSet.toLink() = ShareLink(
    id = uuid("l_id"),
    documentId = uuid("l_documento_id"),
    createdBy = uuid("l_criado_por"),
    expiresAt = instant("l_expira_em"),
    revokedAt = instantOrNull("l_revogado_em"),
    downloadLimit = intOrNull("l_limite_downloads"),
    downloads = getInt("l_downloads"),
    createdAt = instant("l_criado_em"),
)

/** Condições que tornam um link utilizável. */
private const val VALID_LINK = """
    l.revogado_em IS NULL AND l.expira_em > ?
    AND (l.limite_downloads IS NULL OR l.downloads < l.limite_downloads)
    AND d.deletado_em IS NULL
"""

class PostgresShareRepository(private val db: Database) : ShareRepository {
    override fun create(
        documentId: UUID,
        createdBy: UUID,
        tokenHash: ByteArray,
        expiresAt: Instant,
        downloadLimit: Int?,
    ): ShareLink = db.withConnection { c ->
        c.queryOne(
            """INSERT INTO links_compartilhamento AS l (documento_id, criado_por, token_hash, expira_em, limite_downloads)
               VALUES (?, ?, ?, ?, ?) RETURNING $LINK_COLUMNS""",
            documentId, createdBy, tokenHash, expiresAt, downloadLimit,
        ) { it.toLink() }!!
    }

    override fun listForDocument(documentId: UUID, ownerId: UUID): List<ShareLink> = db.withConnection { c ->
        c.query(
            """SELECT $LINK_COLUMNS FROM links_compartilhamento l
               JOIN documentos d ON d.id = l.documento_id
               WHERE l.documento_id = ? AND d.usuario_id = ?
               ORDER BY l.criado_em DESC""",
            documentId, ownerId,
        ) { it.toLink() }
    }

    override fun revoke(linkId: UUID, ownerId: UUID, now: Instant): Boolean = db.withConnection { c ->
        c.update(
            """UPDATE links_compartilhamento l SET revogado_em = COALESCE(l.revogado_em, ?)
               FROM documentos d
               WHERE l.id = ? AND d.id = l.documento_id AND d.usuario_id = ?""",
            now, linkId, ownerId,
        ) == 1
    }

    override fun findValid(tokenHash: ByteArray, now: Instant): SharedDocument? = db.withConnection { c ->
        c.queryOne(
            """SELECT $LINK_COLUMNS, $DOCUMENT_COLUMNS FROM links_compartilhamento l
               JOIN documentos d ON d.id = l.documento_id
               WHERE l.token_hash = ? AND $VALID_LINK""",
            tokenHash, now,
        ) { SharedDocument(it.toLink(), it.toDocument()) }
    }

    override fun consumeDownload(tokenHash: ByteArray, now: Instant): SharedDocument? = db.withConnection { c ->
        // UPDATE com bloqueio de linha: requisições concorrentes reavaliam o
        // limite após o bloqueio, então o contador nunca ultrapassa o limite.
        c.queryOne(
            """UPDATE links_compartilhamento l SET downloads = l.downloads + 1
               FROM documentos d
               WHERE l.token_hash = ? AND d.id = l.documento_id AND $VALID_LINK
               RETURNING $LINK_COLUMNS, $DOCUMENT_COLUMNS""",
            tokenHash, now,
        ) { SharedDocument(it.toLink(), it.toDocument()) }
    }
}
