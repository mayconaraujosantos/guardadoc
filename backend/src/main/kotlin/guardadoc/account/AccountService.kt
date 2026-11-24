package guardadoc.account

import guardadoc.application.account.DeleteAccount
import guardadoc.application.account.DeleteAccountCommand
import guardadoc.application.ports.DocumentFileRemoval
import guardadoc.auth.UserRepository
import guardadoc.documents.DocumentRepository
import guardadoc.shared.AuditRepository
import java.time.Clock
import java.util.UUID

/** API da aplicação para exclusão da conta pelo titular. */
class AccountService(
    users: UserRepository,
    documents: DocumentRepository,
    fileRemoval: DocumentFileRemoval,
    audit: AuditRepository,
    clock: Clock,
) {
    private val deleteAccountUseCase = DeleteAccount(users, documents, fileRemoval, audit, clock)

    fun deleteAccount(userId: UUID) = deleteAccountUseCase(DeleteAccountCommand(userId))
}
