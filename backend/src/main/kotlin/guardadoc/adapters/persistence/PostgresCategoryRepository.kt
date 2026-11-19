package guardadoc.adapters.persistence

import guardadoc.documents.Category
import guardadoc.documents.CategoryRepository
import guardadoc.documents.CategorySummary
import guardadoc.documents.DuplicateCategoryException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

private const val UNIQUE_VIOLATION = "23505"

private fun ResultSet.toCategory() = Category(
    id = uuid("id"),
    ownerId = uuid("usuario_id"),
    name = getString("nome"),
    createdAt = instant("criado_em"),
)

class PostgresCategoryRepository(private val db: Database) : CategoryRepository {
    override fun list(ownerId: UUID): List<CategorySummary> = db.withConnection { c ->
        c.query(
            """SELECT c.id, c.usuario_id, c.nome, c.criado_em, count(d.id) AS documentos
               FROM categorias c
               LEFT JOIN documentos d ON d.categoria_id = c.id AND d.deletado_em IS NULL
               WHERE c.usuario_id = ?
               GROUP BY c.id
               ORDER BY lower(c.nome)""",
            ownerId,
        ) { CategorySummary(it.toCategory(), it.getInt("documentos")) }
    }

    override fun find(id: UUID, ownerId: UUID): Category? = db.withConnection { c ->
        c.queryOne(
            "SELECT id, usuario_id, nome, criado_em FROM categorias WHERE id = ? AND usuario_id = ?",
            id, ownerId,
        ) { it.toCategory() }
    }

    override fun count(ownerId: UUID): Int = db.withConnection { c ->
        c.queryOne("SELECT count(*) FROM categorias WHERE usuario_id = ?", ownerId) { it.getInt(1) } ?: 0
    }

    override fun insert(category: Category) {
        uniqueName {
            db.withConnection { c ->
                c.update(
                    "INSERT INTO categorias (id, usuario_id, nome, criado_em) VALUES (?, ?, ?, ?)",
                    category.id, category.ownerId, category.name, category.createdAt,
                )
            }
        }
    }

    override fun rename(id: UUID, ownerId: UUID, name: String): Category? = uniqueName {
        db.withConnection { c ->
            c.queryOne(
                """UPDATE categorias SET nome = ? WHERE id = ? AND usuario_id = ?
                   RETURNING id, usuario_id, nome, criado_em""",
                name, id, ownerId,
            ) { it.toCategory() }
        }
    }

    override fun delete(id: UUID, ownerId: UUID): Boolean = db.withConnection { c ->
        // ON DELETE SET NULL deixa os documentos sem categoria.
        c.update("DELETE FROM categorias WHERE id = ? AND usuario_id = ?", id, ownerId) > 0
    }

    private inline fun <T> uniqueName(block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (e.sqlState == UNIQUE_VIOLATION) throw DuplicateCategoryException()
        throw e
    }
}
