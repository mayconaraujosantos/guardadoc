package guardadoc.maintenance

import guardadoc.application.maintenance.RunMaintenance
import guardadoc.application.maintenance.SweepOrphans
import guardadoc.application.ports.PendingFilePurger
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentStorage
import guardadoc.shared.AuditRepository
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

/** Porta de saída: exclusão mútua entre instâncias para rotinas agendadas. */
interface JobLock {
    /** Executa [block] se o lock [name] estiver livre; caso contrário, devolve null sem executar. */
    fun <T> tryRun(name: String, block: () -> T): T?
}

/** Porta de saída: remoção de dados vencidos conforme a política. */
interface RetentionRepository {
    fun purge(now: Instant, policy: RetentionPolicy): RetentionReport
}

/** API das rotinas de manutenção, implementadas pelos casos de uso em `application.maintenance`. */
class MaintenanceService(
    retention: RetentionRepository,
    documents: DocumentRepository,
    documentService: PendingFilePurger,
    storage: DocumentStorage,
    audit: AuditRepository,
    val policy: RetentionPolicy,
    clock: Clock,
) {
    private val sweepOrphansUseCase = SweepOrphans(storage, documents, audit, policy, clock)
    private val runUseCase = RunMaintenance(retention, documentService, sweepOrphansUseCase, policy, clock)

    fun runAll(): MaintenanceReport = runUseCase()

    /** Remove objetos do armazenamento que não têm metadados no banco. */
    fun sweepOrphans(): Int = sweepOrphansUseCase()
}
