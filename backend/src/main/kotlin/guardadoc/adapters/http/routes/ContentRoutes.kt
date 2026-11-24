package guardadoc.adapters.http.routes

import guardadoc.adapters.http.*
import guardadoc.documents.CategoryService
import guardadoc.documents.DocumentService
import guardadoc.sharing.ShareService
import guardadoc.shared.AppException
import io.javalin.config.JavalinConfig
import io.javalin.http.HttpStatus
import java.time.Clock
import java.util.UUID

/** Rotas de documentos do titular (upload, listagem, download, exclusão). */
class DocumentRoutes(
    private val documents: DocumentService,
    private val categories: CategoryService,
) {
    fun register(config: JavalinConfig) {
        val r = config.routes

        r.get("/api/documents") { ctx ->
            ctx.json(documents.list(ctx.requirePrincipal().userId).map(DocumentResponse::of))
        }
        r.post("/api/documents") { ctx ->
            val principal = ctx.requirePrincipal()
            val declaredLength = ctx.header("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_REQUEST_BYTES) {
                throw AppException.PayloadTooLarge("Arquivo excede o limite de 10 MiB")
            }
            val file = try {
                ctx.uploadedFile("file")
            } catch (e: Exception) {
                throw AppException.PayloadTooLarge("Arquivo excede o limite de 10 MiB")
            } ?: throw AppException.Invalid("Envie o arquivo no campo 'file'")
            val categoryId = ctx.formParam("categoryId")?.takeIf { it.isNotBlank() }?.let {
                runCatching { UUID.fromString(it) }.getOrElse { throw AppException.Invalid("Categoria inválida") }
            }
            // Antes de ler/verificar o arquivo: categoria precisa ser do usuário.
            categories.requireOwned(principal.userId, categoryId)
            val document = documents.upload(
                ownerId = principal.userId,
                clientFileName = file.filename(),
                declaredMimeType = file.contentType(),
                declaredSize = file.size(),
                content = file.content(),
                categoryId = categoryId,
            )
            ctx.status(HttpStatus.CREATED).json(DocumentResponse.of(document))
        }
        r.get("/api/documents/{id}/download") { ctx ->
            ctx.sendAttachment(documents.download(ctx.requirePrincipal().userId, ctx.uuidParam("id")))
        }
        r.patch("/api/documents/{id}") { ctx ->
            val body = ctx.parseBody<UpdateDocumentBody>()
            val document = categories.assign(ctx.requirePrincipal().userId, ctx.uuidParam("id"), body.categoryId)
            ctx.json(DocumentResponse.of(document))
        }
        r.delete("/api/documents/{id}") { ctx ->
            documents.delete(ctx.requirePrincipal().userId, ctx.uuidParam("id"))
            ctx.status(HttpStatus.NO_CONTENT)
        }
    }
}

/** Rotas de categorias do titular. */
class CategoryRoutes(private val categories: CategoryService) {
    fun register(config: JavalinConfig) {
        val r = config.routes

        r.get("/api/categories") { ctx ->
            ctx.json(categories.list(ctx.requirePrincipal().userId).map(CategoryResponse::of))
        }
        r.post("/api/categories") { ctx ->
            val principal = ctx.requirePrincipal()
            val created = categories.create(principal.userId, ctx.parseBody<CategoryBody>().name.orEmpty())
            ctx.status(HttpStatus.CREATED).json(CategoryResponse.of(created))
        }
        r.patch("/api/categories/{id}") { ctx ->
            val principal = ctx.requirePrincipal()
            val id = ctx.uuidParam("id")
            val renamed = categories.rename(principal.userId, id, ctx.parseBody<CategoryBody>().name.orEmpty())
            val count = categories.list(principal.userId).firstOrNull { it.category.id == id }?.documentCount ?: 0
            ctx.json(CategoryResponse.of(renamed, count))
        }
        r.delete("/api/categories/{id}") { ctx ->
            categories.delete(ctx.requirePrincipal().userId, ctx.uuidParam("id"))
            ctx.status(HttpStatus.NO_CONTENT)
        }
    }
}

/** Rotas de links de compartilhamento: proprietário e destinatário. */
class ShareRoutes(
    private val shares: ShareService,
    private val settings: HttpSettings,
    private val clock: Clock,
) {
    fun register(config: JavalinConfig) {
        val r = config.routes

        // Proprietário
        r.get("/api/documents/{id}/shares") { ctx ->
            ctx.json(
                shares.list(ctx.requirePrincipal().userId, ctx.uuidParam("id"))
                    .map { ShareLinkResponse.of(it, clock.instant()) },
            )
        }
        r.post("/api/documents/{id}/shares") { ctx ->
            val principal = ctx.requirePrincipal()
            val body = ctx.parseBody<CreateShareBody>()
            val created = shares.create(principal.userId, ctx.uuidParam("id"), body.expiresInMinutes, body.downloadLimit)
            ctx.status(HttpStatus.CREATED).json(
                CreatedShareResponse(
                    url = "${settings.publicBaseUrl.trimEnd('/')}/s/${created.token}",
                    link = ShareLinkResponse.of(created.link, clock.instant()),
                ),
            )
        }
        r.delete("/api/shares/{id}") { ctx ->
            shares.revoke(ctx.requirePrincipal().userId, ctx.uuidParam("id"))
            ctx.status(HttpStatus.NO_CONTENT)
        }

        // Acesso público por link (destinatário)
        r.get("/s/{token}") { ctx ->
            ctx.header("Content-Security-Policy", SharePages.CSP)
            ctx.header("X-Robots-Tag", "noindex, nofollow")
            val token = ctx.pathParam("token")
            val page = try {
                SharePages.valid(token, shares.inspect(token))
            } catch (_: AppException.NotFound) {
                ctx.status(HttpStatus.NOT_FOUND)
                SharePages.invalid()
            }
            ctx.html(page)
        }
        r.get("/s/{token}/download") { ctx ->
            ctx.header("X-Robots-Tag", "noindex, nofollow")
            try {
                ctx.sendAttachment(shares.download(ctx.pathParam("token")))
            } catch (_: AppException.NotFound) {
                ctx.header("Content-Security-Policy", SharePages.CSP)
                ctx.status(HttpStatus.NOT_FOUND).html(SharePages.invalid())
            }
        }
    }
}
