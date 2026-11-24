package guardadoc.application

/**
 * Caso de uso: uma operação da aplicação por unidade.
 *
 * A camada de aplicação conhece apenas tipos de domínio e portas. Ela não
 * depende de Javalin, JDBC, S3, SMTP nem de qualquer outro adaptador.
 */
interface UseCase<in C, out R> {
    operator fun invoke(command: C): R
}

/** Caso de uso sem valor de retorno. */
interface CommandUseCase<in C> {
    operator fun invoke(command: C)
}

/** Caso de uso sem parâmetros de entrada. */
interface Query<out R> {
    operator fun invoke(): R
}
