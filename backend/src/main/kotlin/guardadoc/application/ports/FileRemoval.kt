package guardadoc.application.ports

import guardadoc.documents.Document
import java.io.InputStream

/**
 * Portas de saída declaradas pelos casos de uso que precisam de arquivos.
 *
 * Ficam no lado de quem consome (camada de aplicação), não no módulo de
 * documentos: assim [guardadoc.account] e [guardadoc.maintenance] não dependem
 * da classe concreta `DocumentService`.
 */

/** Remove o objeto de armazenamento de um documento já inacessível. */
interface DocumentFileRemoval {
    fun removeFile(document: Document): Boolean
}

/** Reprocessa remoções de arquivos que falharam em uma execução anterior. */
interface PendingFilePurger {
    fun purgePendingFiles(limit: Int = 100): Int
}

/** Abre o conteúdo de um objeto para entrega (download). */
fun interface DocumentContentOpener {
    fun open(storageKey: String): InputStream
}
