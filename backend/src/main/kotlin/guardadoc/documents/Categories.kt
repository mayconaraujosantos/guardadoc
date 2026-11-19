package guardadoc.documents

import guardadoc.shared.AppException
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class Category(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val createdAt: Instant,
)

data class CategorySummary(val category: Category, val documentCount: Int)

/** Já existe uma categoria do mesmo usuário com esse nome (sem diferenciar maiúsculas). */
class DuplicateCategoryException : RuntimeException()

interface CategoryRepository {
    /** Categorias do usuário, em ordem alfabética, com a contagem de documentos ativos. */
    fun list(ownerId: UUID): List<CategorySummary>
    fun find(id: UUID, ownerId: UUID): Category?
    fun count(ownerId: UUID): Int

    /** @throws DuplicateCategoryException */
    fun insert(category: Category)

    /** Retorna a categoria renomeada, ou null se não pertencer ao usuário. @throws DuplicateCategoryException */
    fun rename(id: UUID, ownerId: UUID, name: String): Category?

    /** Documentos da categoria ficam sem categoria. Retorna false se não pertencer ao usuário. */
    fun delete(id: UUID, ownerId: UUID): Boolean
}

/** Casos de uso: criar, listar, renomear e excluir categorias; mover documento de categoria. */
class CategoryService(
    private val categories: CategoryRepository,
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    fun list(ownerId: UUID): List<CategorySummary> = categories.list(ownerId)

    fun create(ownerId: UUID, rawName: String): Category {
        val name = normalizeName(rawName)
        if (categories.count(ownerId) >= MAX_PER_USER) {
            throw AppException.Invalid("Limite de $MAX_PER_USER categorias atingido")
        }
        val category = Category(UUID.randomUUID(), ownerId, name, clock.instant())
        try {
            categories.insert(category)
        } catch (_: DuplicateCategoryException) {
            throw duplicate()
        }
        return category
    }

    fun rename(ownerId: UUID, id: UUID, rawName: String): Category {
        val name = normalizeName(rawName)
        return try {
            categories.rename(id, ownerId, name)
        } catch (_: DuplicateCategoryException) {
            throw duplicate()
        } ?: throw AppException.NotFound()
    }

    fun delete(ownerId: UUID, id: UUID) {
        if (!categories.delete(id, ownerId)) throw AppException.NotFound()
    }

    /** Move o documento para a categoria (ou tira de qualquer categoria, com null). */
    fun assign(ownerId: UUID, documentId: UUID, categoryId: UUID?): Document {
        requireOwned(ownerId, categoryId)
        return documents.setCategory(documentId, ownerId, categoryId) ?: throw AppException.NotFound()
    }

    /** Garante que a categoria (se informada) pertence ao usuário. */
    fun requireOwned(ownerId: UUID, categoryId: UUID?) {
        if (categoryId != null && categories.find(categoryId, ownerId) == null) {
            throw AppException.Invalid("Categoria não encontrada")
        }
    }

    private fun duplicate() = AppException.Invalid("Já existe uma categoria com esse nome")

    companion object {
        const val MAX_PER_USER = 50
        const val MAX_NAME_LENGTH = 40

        /** Remove caracteres de controle e espaços repetidos; exige de 1 a 40 caracteres. */
        fun normalizeName(raw: String): String {
            val name = raw.filterNot { it.isISOControl() }.trim().replace(Regex("\\s+"), " ")
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                throw AppException.Invalid("O nome da categoria deve ter de 1 a $MAX_NAME_LENGTH caracteres")
            }
            return name
        }
    }
}
