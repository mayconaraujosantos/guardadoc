package guardadoc.account

import guardadoc.auth.UserRepository
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentService
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import java.time.Clock
import java.util.UUID

/** Caso de uso: o titular exclui a própria conta e todos os seus dados. */
class AccountService(
    private val users: UserRepository,
    private val documents: DocumentRepository,
    private val documentService: DocumentService,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    fun deleteAccount(userId: UUID) {
        // 1. Documentos ficam inacessíveis e links são revogados (transação).
        val deleted = documents.markAllDeletedAndRevokeLinks(userId, clock.instant())
        // 2. Remove os arquivos. Falhas viram órfãos, removidos pela varredura.
        deleted.forEach { documentService.removeFile(it) }
        // 3. Remove usuário, sessões, metadados, links e desafios OTP.
        users.delete(userId)
        audit.record(AuditEvent(AuditEventType.CONTA_EXCLUIDA))
    }
}
