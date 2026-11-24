package guardadoc.application

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guarda leve da regra de dependência enquanto o projeto é um único módulo
 * Gradle: nem domínio nem aplicação podem importar infraestrutura.
 */
class ArchitectureTest {
    private val forbidden = listOf(
        "guardadoc.adapters",
        "io.javalin",
        "java.sql",
        "javax.sql",
        "software.amazon",
        "jakarta.mail",
        "org.flywaydb",
        "com.zaxxer",
    )

    @Test
    fun `dominio e aplicacao nao dependem de adaptadores`() {
        val roots = listOf(
            "src/main/kotlin/guardadoc/application",
            "src/main/kotlin/guardadoc/auth",
            "src/main/kotlin/guardadoc/documents",
            "src/main/kotlin/guardadoc/sharing",
            "src/main/kotlin/guardadoc/account",
            "src/main/kotlin/guardadoc/maintenance",
            "src/main/kotlin/guardadoc/shared",
        )
        val offenders = roots.flatMap { root ->
            File(root).walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapNotNull { line ->
                        val import = line.trim().takeIf { it.startsWith("import ") }
                        import?.takeIf { value -> forbidden.any { value.contains(it) } }
                            ?.let { "${file.path}: $it" }
                    }
                }
        }
        assertTrue(offenders.isEmpty(), "Dependências indevidas:\n${offenders.joinToString("\n")}")
    }
}
