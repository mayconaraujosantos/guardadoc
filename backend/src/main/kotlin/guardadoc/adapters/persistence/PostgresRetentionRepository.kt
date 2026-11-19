package guardadoc.adapters.persistence

import guardadoc.maintenance.RetentionPolicy
import guardadoc.maintenance.RetentionReport
import guardadoc.maintenance.RetentionRepository
import java.time.Instant

class PostgresRetentionRepository(private val db: Database) : RetentionRepository {
    override fun purge(now: Instant, policy: RetentionPolicy): RetentionReport = db.withConnection { c ->
        RetentionReport(
            otpChallenges = c.update(
                "DELETE FROM desafios_otp WHERE expira_em < ?",
                now.minus(policy.otpChallenges),
            ),
            sessions = c.update(
                "DELETE FROM sessoes WHERE expira_em < ? OR revogada_em < ?",
                now.minus(policy.sessions), now.minus(policy.sessions),
            ),
            rateLimitWindows = c.update(
                "DELETE FROM limites_requisicao WHERE inicio_janela < ?",
                now.minus(policy.rateLimitWindows),
            ),
            shareLinks = c.update(
                "DELETE FROM links_compartilhamento WHERE expira_em < ? OR revogado_em < ?",
                now.minus(policy.inactiveShareLinks), now.minus(policy.inactiveShareLinks),
            ),
            // Somente documentos já excluídos e cujo arquivo foi removido.
            documents = c.update(
                "DELETE FROM documentos WHERE deletado_em IS NOT NULL AND arquivo_removido_em < ?",
                now.minus(policy.deletedDocumentMetadata),
            ),
            auditEvents = c.update(
                "DELETE FROM eventos_auditoria WHERE criado_em < ?",
                now.minus(policy.auditEvents),
            ),
        )
    }
}
