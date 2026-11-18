plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "guardadoc"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

val dev: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[dev.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[dev.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation("io.javalin:javalin:7.2.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:12.4.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.4.0")
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Bucket privado S3 (ou compatível: R2, MinIO, B2) com cliente HTTP leve.
    implementation(platform("software.amazon.awssdk:bom:2.59.3"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    // PostgreSQL embarcado (sem Docker) apenas para desenvolvimento local e testes.
    "devImplementation"("io.zonky.test:embedded-postgres:2.2.2")
    "devImplementation"(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.11.0"))

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.11.0"))
    // Servidor SMTP em memória para testar o envio de e-mails.
    testImplementation("com.icegreen:greenmail:2.1.3")
    // Servidor S3 em memória para testar o adaptador S3 sem Docker.
    testImplementation("org.gaul:s3proxy:4.2.0")
}

application {
    mainClass.set("guardadoc.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// ./gradlew emailTest -Pto=voce@exemplo.com → envia um e-mail de teste com as variáveis SMTP_*.
tasks.register<JavaExec>("emailTest") {
    group = "application"
    description = "Envia um e-mail de teste usando a configuração SMTP_* do ambiente."
    classpath = dev.runtimeClasspath
    mainClass.set("guardadoc.EmailTestKt")
    args(providers.gradleProperty("to").orElse("").get())
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// ./gradlew runDev → sobe PostgreSQL embarcado + aplicação em modo dev.
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Executa a API com PostgreSQL embarcado e outbox de e-mail de desenvolvimento."
    classpath = dev.runtimeClasspath
    mainClass.set("guardadoc.DevMainKt")
    workingDir = projectDir
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
