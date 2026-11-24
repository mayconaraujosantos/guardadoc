package guardadoc.application.account

import guardadoc.application.CommandUseCase
import guardadoc.application.ports.DocumentFileRemoval
import guardadoc.auth.UserRepository
import guardadoc.documents.DocumentRepository
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import java.time.Clock
import java.util.UUID

data class DeleteAccountCommand(val userId: UUID)

/** O titular exclui a própria conta e todos os seus dados. */
class DeleteAccount(
    private val users: UserRepository,
    private val documents: DocumentRepository,
    private val fileRemoval: DocumentFileRemoval,
    private val audit: AuditRepository,
    private val clock: Clock,
) : CommandUseCase<DeleteAccountCommand> {
    override fun invoke(command: DeleteAccountCommand) {
        // 1. Documentos ficam inacessíveis e links são revogados (transação).
        val deleted = documents.markAllDeletedAndRevokeLinks(command.userId, clock.instant())
        // 2. Remove os arquivos. Falhas viram órfãos, removidos pela varredura.
        deleted.forEach { fileRemoval.removeFile(it) }
        // 3. Remove usuário, sessões, metadados, links e desafios OTP.
        users.delete(command.userId)
        audit.record(AuditEvent(AuditEventType.CONTA_EXCLUIDA))
    }
}
