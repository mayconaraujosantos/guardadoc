package guardadoc.shared

/** Erros de aplicação, traduzidos para HTTP pelo adaptador de entrada. */
sealed class AppException(message: String) : RuntimeException(message) {
    class Invalid(message: String) : AppException(message)
    class Unauthorized(message: String = "Não autenticado") : AppException(message)
    class Forbidden(message: String = "Operação não permitida") : AppException(message)
    /** Usado também para recursos de outros usuários, para não revelar existência. */
    class NotFound(message: String = "Não encontrado") : AppException(message)
    class TooManyRequests(message: String = "Muitas requisições; tente novamente mais tarde") : AppException(message)
    class PayloadTooLarge(message: String) : AppException(message)
    class UnsupportedMedia(message: String) : AppException(message)
}
