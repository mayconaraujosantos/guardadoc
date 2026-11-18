package guardadoc.maintenance

import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentService
import guardadoc.documents.DocumentStorage
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Prazos de retenção (ver docs/politicas/retencao-e-exclusao.md). */
data class RetentionPolicy(
    /** Desafios OTP: removidos após este prazo contado da expiração. */
    val otpChallenges: Duration = Duration.ofDays(1),
    /** Sessões: removidas após este prazo contado da expiração ou revogação. */
    val sessions: Duration = Duration.ofDays(1),
    /** Janelas do limitador de requisições. */
    val rateLimitWindows: Duration = Duration.ofDays(1),
    /** Links expirados ou revogados. */
    val inactiveShareLinks: Duration = Duration.ofDays(90),
    /** Metadados de documentos excluídos cujo arquivo já foi removido. */
    val deletedDocumentMetadata: Duration = Duration.ofDays(30),
    /** Eventos de auditoria. */
    val auditEvents: Duration = Duration.ofDays(180),
    /** Objetos sem metadados mais novos que isto não são tocados (uploads em andamento). */
    val orphanGracePeriod: Duration = Duration.ofHours(1),
)

data class RetentionReport(
    val otpChallenges: Int,
    val sessions: Int,
    val rateLimitWindows: Int,
    val shareLinks: Int,
    val documents: Int,
    val auditEvents: Int,
)

data class MaintenanceReport(val pendingFilesRemoved: Int, val retention: RetentionReport, val orphansRemoved: Int)

/** Porta de saída: remoção de dados vencidos conforme a política. */
interface RetentionRepository {
    fun purge(now: Instant, policy: RetentionPolicy): RetentionReport
}

class MaintenanceService(
    private val retention: RetentionRepository,
    private val documents: DocumentRepository,
    private val documentService: DocumentService,
    private val storage: DocumentStorage,
    private val audit: AuditRepository,
    val policy: RetentionPolicy,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(MaintenanceService::class.java)

    fun runAll(): MaintenanceReport {
        val pending = documentService.purgePendingFiles()
        val report = retention.purge(clock.instant(), policy)
        val orphans = sweepOrphans()
        return MaintenanceReport(pending, report, orphans).also { log.info("Manutenção concluída: {}", it) }
    }

    /** Remove objetos do armazenamento que não têm metadados no banco. */
    fun sweepOrphans(): Int {
        val cutoff = clock.instant().minus(policy.orphanGracePeriod)
        var removed = 0
        storage.listObjects()
            .filter { it.lastModified.isBefore(cutoff) }
            .chunked(500)
            .forEach { batch ->
                val known = documents.knownStorageKeys(batch.map { it.key })
                batch.filter { it.key !in known }.forEach { orphan ->
                    runCatching { storage.delete(orphan.key) }
                        .onSuccess {
                            removed++
                            audit.record(AuditEvent(AuditEventType.ARQUIVO_ORFAO_REMOVIDO))
                        }
                        .onFailure { log.warn("Falha ao remover objeto órfão: {}", it.javaClass.simpleName) }
                }
            }
        return removed
    }
}
