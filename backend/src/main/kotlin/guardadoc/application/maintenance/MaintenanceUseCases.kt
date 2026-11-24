package guardadoc.application.maintenance

import guardadoc.application.Query
import guardadoc.application.ports.PendingFilePurger
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentStorage
import guardadoc.maintenance.MaintenanceReport
import guardadoc.maintenance.RetentionPolicy
import guardadoc.maintenance.RetentionRepository
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import org.slf4j.LoggerFactory
import java.time.Clock

/** Remove objetos do armazenamento que não têm metadados no banco. */
class SweepOrphans(
    private val storage: DocumentStorage,
    private val documents: DocumentRepository,
    private val audit: AuditRepository,
    private val policy: RetentionPolicy,
    private val clock: Clock,
) : Query<Int> {
    override fun invoke(): Int {
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

    companion object {
        private val log = LoggerFactory.getLogger(SweepOrphans::class.java)
    }
}

/** Executa todas as rotinas de manutenção em uma única passagem. */
class RunMaintenance(
    private val retention: RetentionRepository,
    private val pendingFiles: PendingFilePurger,
    private val sweepOrphans: SweepOrphans,
    private val policy: RetentionPolicy,
    private val clock: Clock,
) : Query<MaintenanceReport> {
    override fun invoke(): MaintenanceReport {
        val pending = pendingFiles.purgePendingFiles()
        val report = retention.purge(clock.instant(), policy)
        val orphans = sweepOrphans()
        return MaintenanceReport(pending, report, orphans).also { log.info("Manutenção concluída: {}", it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RunMaintenance::class.java)
    }
}
