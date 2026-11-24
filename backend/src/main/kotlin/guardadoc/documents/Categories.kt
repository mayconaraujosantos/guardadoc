package guardadoc.documents

import guardadoc.application.categories.AssignCategory
import guardadoc.application.categories.AssignCategoryCommand
import guardadoc.application.categories.CategoryNames
import guardadoc.application.categories.CreateCategory
import guardadoc.application.categories.CreateCategoryCommand
import guardadoc.application.categories.DeleteCategory
import guardadoc.application.categories.DeleteCategoryCommand
import guardadoc.application.categories.ListCategories
import guardadoc.application.categories.ListCategoriesCommand
import guardadoc.application.categories.RenameCategory
import guardadoc.application.categories.RenameCategoryCommand
import guardadoc.application.categories.RequireCategoryOwnership
import guardadoc.application.categories.RequireCategoryOwnershipCommand
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

/** API da aplicação de categorias, sobre os casos de uso em `application.categories`. */
class CategoryService(
    categories: CategoryRepository,
    documents: DocumentRepository,
    clock: Clock,
) {
    private val listUseCase = ListCategories(categories)
    private val createUseCase = CreateCategory(categories, clock)
    private val renameUseCase = RenameCategory(categories)
    private val deleteUseCase = DeleteCategory(categories)
    private val assignUseCase = AssignCategory(categories, documents)
    private val requireOwnedUseCase = RequireCategoryOwnership(categories)

    fun list(ownerId: UUID): List<CategorySummary> = listUseCase(ListCategoriesCommand(ownerId))

    fun create(ownerId: UUID, rawName: String): Category =
        createUseCase(CreateCategoryCommand(ownerId, rawName))

    fun rename(ownerId: UUID, id: UUID, rawName: String): Category =
        renameUseCase(RenameCategoryCommand(ownerId, id, rawName))

    fun delete(ownerId: UUID, id: UUID) = deleteUseCase(DeleteCategoryCommand(ownerId, id))

    /** Move o documento para a categoria (ou tira de qualquer categoria, com null). */
    fun assign(ownerId: UUID, documentId: UUID, categoryId: UUID?): Document =
        assignUseCase(AssignCategoryCommand(ownerId, documentId, categoryId))

    /** Garante que a categoria (se informada) pertence ao usuário. */
    fun requireOwned(ownerId: UUID, categoryId: UUID?) =
        requireOwnedUseCase(RequireCategoryOwnershipCommand(ownerId, categoryId))

    companion object {
        const val MAX_PER_USER = CategoryNames.MAX_PER_USER
        const val MAX_NAME_LENGTH = CategoryNames.MAX_NAME_LENGTH

        fun normalizeName(raw: String): String = CategoryNames.normalize(raw)
    }
}
