package guardadoc.adapters.persistence

import guardadoc.documents.Document
import guardadoc.documents.DocumentRepository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

internal const val DOCUMENT_COLUMNS =
    "d.id AS d_id, d.usuario_id AS d_usuario_id, d.storage_key AS d_storage_key, " +
        "d.nome_original AS d_nome_original, d.mime_type AS d_mime_type, " +
        "d.tamanho_bytes AS d_tamanho_bytes, d.criado_em AS d_criado_em"

internal fun ResultSet.toDocument() = Document(
    id = uuid("d_id"),
    ownerId = uuid("d_usuario_id"),
    storageKey = getString("d_storage_key"),
    originalName = getString("d_nome_original"),
    mimeType = getString("d_mime_type"),
    sizeBytes = getLong("d_tamanho_bytes"),
    createdAt = instant("d_criado_em"),
)

class PostgresDocumentRepository(private val db: Database) : DocumentRepository {
    override fun insert(document: Document) {
        db.withConnection { c ->
            c.update(
                """INSERT INTO documentos (id, usuario_id, storage_key, nome_original, mime_type, tamanho_bytes, criado_em)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                document.id, document.ownerId, document.storageKey, document.originalName,
                document.mimeType, document.sizeBytes, document.createdAt,
            )
        }
    }

    override fun listActive(ownerId: UUID): List<Document> = db.withConnection { c ->
        c.query(
            "SELECT $DOCUMENT_COLUMNS FROM documentos d WHERE d.usuario_id = ? AND d.deletado_em IS NULL ORDER BY d.criado_em DESC",
            ownerId,
        ) { it.toDocument() }
    }

    override fun findActive(id: UUID, ownerId: UUID): Document? = db.withConnection { c ->
        c.queryOne(
            "SELECT $DOCUMENT_COLUMNS FROM documentos d WHERE d.id = ? AND d.usuario_id = ? AND d.deletado_em IS NULL",
            id, ownerId,
        ) { it.toDocument() }
    }

    override fun markDeletedAndRevokeLinks(id: UUID, ownerId: UUID, now: Instant): Document? = db.transaction { c ->
        val document = c.queryOne(
            """UPDATE documentos d SET deletado_em = ?
               WHERE d.id = ? AND d.usuario_id = ? AND d.deletado_em IS NULL
               RETURNING $DOCUMENT_COLUMNS""",
            now, id, ownerId,
        ) { it.toDocument() } ?: return@transaction null
        c.update(
            "UPDATE links_compartilhamento SET revogado_em = ? WHERE documento_id = ? AND revogado_em IS NULL",
            now, id,
        )
        document
    }

    override fun markFileRemoved(id: UUID, now: Instant) {
        db.withConnection { c ->
            c.update("UPDATE documentos SET arquivo_removido_em = ? WHERE id = ? AND arquivo_removido_em IS NULL", now, id)
        }
    }

    override fun pendingFileRemoval(limit: Int): List<Document> = db.withConnection { c ->
        c.query(
            """SELECT $DOCUMENT_COLUMNS FROM documentos d
               WHERE d.deletado_em IS NOT NULL AND d.arquivo_removido_em IS NULL
               ORDER BY d.deletado_em LIMIT ?""",
            limit,
        ) { it.toDocument() }
    }

    override fun knownStorageKeys(keys: Collection<String>): Set<String> {
        if (keys.isEmpty()) return emptySet()
        return db.withConnection { c ->
            c.prepareStatement("SELECT storage_key FROM documentos WHERE storage_key = ANY(?)").use { st ->
                st.setArray(1, c.createArrayOf("text", keys.toTypedArray()))
                st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }
    }

    override fun markAllDeletedAndRevokeLinks(ownerId: UUID, now: Instant): List<Document> = db.transaction { c ->
        val documents = c.query(
            """UPDATE documentos d SET deletado_em = ?
               WHERE d.usuario_id = ? AND d.deletado_em IS NULL
               RETURNING $DOCUMENT_COLUMNS""",
            now, ownerId,
        ) { it.toDocument() }
        c.update(
            """UPDATE links_compartilhamento l SET revogado_em = ?
               FROM documentos d
               WHERE d.id = l.documento_id AND d.usuario_id = ? AND l.revogado_em IS NULL""",
            now, ownerId,
        )
        documents
    }
}
