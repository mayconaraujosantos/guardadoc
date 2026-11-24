package guardadoc.application.categories

import guardadoc.application.CommandUseCase
import guardadoc.application.UseCase
import guardadoc.documents.Category
import guardadoc.documents.CategoryRepository
import guardadoc.documents.CategorySummary
import guardadoc.documents.Document
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DuplicateCategoryException
import guardadoc.shared.AppException
import java.time.Clock
import java.util.UUID

object CategoryNames {
    const val MAX_PER_USER = 50
    const val MAX_NAME_LENGTH = 40

    /** Remove caracteres de controle e espaços repetidos; exige de 1 a 40 caracteres. */
    fun normalize(raw: String): String {
        val name = raw.filterNot { it.isISOControl() }.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw AppException.Invalid("O nome da categoria deve ter de 1 a $MAX_NAME_LENGTH caracteres")
        }
        return name
    }
}

data class ListCategoriesCommand(val ownerId: UUID)

/** Lista as categorias do titular com a contagem de documentos ativos. */
class ListCategories(private val categories: CategoryRepository) :
    UseCase<ListCategoriesCommand, List<CategorySummary>> {
    override fun invoke(command: ListCategoriesCommand) = categories.list(command.ownerId)
}

data class CreateCategoryCommand(val ownerId: UUID, val rawName: String)

/** Cria uma categoria, respeitando o limite por usuário e a unicidade do nome. */
class CreateCategory(
    private val categories: CategoryRepository,
    private val clock: Clock,
) : UseCase<CreateCategoryCommand, Category> {
    override fun invoke(command: CreateCategoryCommand): Category {
        val name = CategoryNames.normalize(command.rawName)
        if (categories.count(command.ownerId) >= CategoryNames.MAX_PER_USER) {
            throw AppException.Invalid("Limite de ${CategoryNames.MAX_PER_USER} categorias atingido")
        }
        val category = Category(UUID.randomUUID(), command.ownerId, name, clock.instant())
        try {
            categories.insert(category)
        } catch (_: DuplicateCategoryException) {
            throw duplicate()
        }
        return category
    }
}

data class RenameCategoryCommand(val ownerId: UUID, val id: UUID, val rawName: String)

/** Renomeia uma categoria do titular. */
class RenameCategory(private val categories: CategoryRepository) :
    UseCase<RenameCategoryCommand, Category> {
    override fun invoke(command: RenameCategoryCommand): Category {
        val name = CategoryNames.normalize(command.rawName)
        return try {
            categories.rename(command.id, command.ownerId, name)
        } catch (_: DuplicateCategoryException) {
            throw duplicate()
        } ?: throw AppException.NotFound()
    }
}

data class DeleteCategoryCommand(val ownerId: UUID, val id: UUID)

/** Exclui a categoria; os documentos ficam sem categoria. */
class DeleteCategory(private val categories: CategoryRepository) : CommandUseCase<DeleteCategoryCommand> {
    override fun invoke(command: DeleteCategoryCommand) {
        if (!categories.delete(command.id, command.ownerId)) throw AppException.NotFound()
    }
}

data class RequireCategoryOwnershipCommand(val ownerId: UUID, val categoryId: UUID?)

/** Garante que a categoria informada (se houver) pertence ao titular. */
class RequireCategoryOwnership(private val categories: CategoryRepository) :
    CommandUseCase<RequireCategoryOwnershipCommand> {
    override fun invoke(command: RequireCategoryOwnershipCommand) {
        val categoryId = command.categoryId ?: return
        if (categories.find(categoryId, command.ownerId) == null) {
            throw AppException.Invalid("Categoria não encontrada")
        }
    }
}

data class AssignCategoryCommand(val ownerId: UUID, val documentId: UUID, val categoryId: UUID?)

/** Move o documento para a categoria (ou tira de qualquer categoria, com null). */
class AssignCategory(
    private val categories: CategoryRepository,
    private val documents: DocumentRepository,
) : UseCase<AssignCategoryCommand, Document> {
    private val requireOwned = RequireCategoryOwnership(categories)

    override fun invoke(command: AssignCategoryCommand): Document {
        requireOwned(RequireCategoryOwnershipCommand(command.ownerId, command.categoryId))
        return documents.setCategory(command.documentId, command.ownerId, command.categoryId)
            ?: throw AppException.NotFound()
    }
}

private fun duplicate() = AppException.Invalid("Já existe uma categoria com esse nome")
