package guardadoc.shared

import java.util.UUID

enum class AuditEventType {
    OTP_SOLICITADO,
    LOGIN_SUCESSO,
    LOGIN_FALHA,
    LOGOUT,
    DOCUMENTO_ENVIADO,
    DOCUMENTO_BAIXADO,
    DOCUMENTO_EXCLUIDO,
    ARQUIVO_REMOVIDO,
    LINK_CRIADO,
    LINK_REVOGADO,
    LINK_ACESSADO,
    UPLOAD_REJEITADO_MALWARE,
    ARQUIVO_ORFAO_REMOVIDO,
    CONTA_EXCLUIDA,
}

/**
 * Evento mínimo de auditoria: tipo, identificadores internos e horário.
 * Nunca contém IP, user agent, OTP, tokens ou conteúdo de documentos.
 */
data class AuditEvent(
    val type: AuditEventType,
    val userId: UUID? = null,
    val documentId: UUID? = null,
    val linkId: UUID? = null,
)

/** Porta de saída. */
interface AuditRepository {
    fun record(event: AuditEvent)
}
