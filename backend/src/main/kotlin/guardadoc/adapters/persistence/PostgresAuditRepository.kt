package guardadoc.adapters.persistence

import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditRepository

class PostgresAuditRepository(private val db: Database) : AuditRepository {
    override fun record(event: AuditEvent) {
        db.withConnection { c ->
            c.update(
                "INSERT INTO eventos_auditoria (evento, usuario_id, documento_id, link_id) VALUES (?, ?, ?, ?)",
                event.type.name, event.userId, event.documentId, event.linkId,
            )
        }
    }
}
