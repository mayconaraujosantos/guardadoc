# Arquitetura do backend (Kotlin)

O backend é um monólito modular que segue **clean architecture / portas e
adaptadores** dentro de um único módulo Gradle. A regra de dependência aponta
sempre para dentro: camadas externas conhecem as internas, nunca o contrário.

## Camadas

```text
guardadoc/
  auth/ documents/ sharing/ maintenance/ shared/   domínio: entidades, regras e portas
  application/                                     casos de uso + portas consumidas
    auth/ categories/ documents/ sharing/ account/ maintenance/ ports/
  adapters/                                        infraestrutura e adaptadores de entrada
    http/ (routes/) persistence/ storage/ email/ scan/ ratelimit/
  GuardaDocApp.kt                                  composition root
  AppConfig.kt                                     configuração de ambiente
```

### Domínio (`guardadoc.auth`, `.documents`, `.sharing`, `.maintenance`, `.shared`)

Contém entidades (`Document`, `User`, `ShareLink`, `Category`...), regras puras
(`FileType`, `ContentValidator`, `EmailNormalizer` equivalente, `Crypto`) e as
portas de saída implementadas pelos adaptadores (`DocumentRepository`,
`SessionRepository`, `ShareRepository`, `AuditRepository`, `DocumentStorage`,
`MalwareScanner`, `EmailSender`, `RateLimiter`, `JobLock`, `RetentionRepository`).

Não imports Javalin, JDBC, AWS SDK, SMTP nem classes de `adapters/`.

### Aplicação (`guardadoc.application`)

Cada operação relevante é um caso de uso explícito com um comando de entrada:

```kotlin
val document = upload(UploadDocumentCommand(ownerId, nome, mime, tamanho, conteudo))
```

Um caso de uso implementa `UseCase<C, R>`, `CommandUseCase<C>` ou `Query<R>`,
orquestra entidades e portas e não conhece HTTP, banco ou armazenamento.
As fachadas públicas antigas (`AuthService`, `DocumentService`, `CategoryService`,
`ShareService`, `AccountService`, `MaintenanceService`, `PasskeyService`)
permanecem nos pacotes originais e apenas delegam aos casos de uso, mantendo a
API usada pelos testes e pelo adaptador HTTP.

Portas declaradas **por quem consome** evitam acoplamento entre áreas:

- `DocumentFileRemoval` — usada por exclusão de conta;
- `PendingFilePurger` — usada pela manutenção;
- `DocumentContentOpener` — usada pelo download via link.

Assim `account` e `maintenance` não dependem da classe concreta
`DocumentService`, e `sharing` não depende de `DocumentStorage`.

### Adaptadores (`guardadoc.adapters`)

- `http/` — Javalin. `HttpApi` configura servidor, filtros e erros; `routes/`
  contém um registrador por área (`AuthRoutes`, `DocumentRoutes`, ...). DTOs e
  tradução de erros ficam aqui.
- `persistence/`, `storage/`, `email/`, `scan/`, `ratelimit/` — implementam as
  portas de saída (PostgreSQL/JDBC, disco/S3, SMTP/outbox, ClamAV, limitadores).

### Composition root (`GuardaDocApp`)

É o único lugar que escolhe implementações concretas e injeta portas. Também
controla ciclo de vida (migrações, agendamento, servidor HTTP). Os testes usam
`GuardaDocApp` com sobrescritas de e-mail, armazenamento, antivírus e relógio.

## Onde colocar código novo

| Mudança | Local |
|---|---|
| Nova regra de negócio pura | Domínio (ou arquivo de regras do módulo) |
| Nova operação da API | Novo caso de uso em `application/` + comando; ligue na fachada e na rota |
| Nova integração externa | Nova classe em `adapters/` implementando uma porta do domínio |
| Nova porta usada por um caso de uso | `application/ports/` ou pacote do caso de uso consumidor |
| Novo endpoint HTTP | Registrador em `adapters/http/routes/` |

## Testes

- Integração: `src/test/kotlin/guardadoc/*Test.kt` sobem PostgreSQL embarcado e
  Javalin real via `TestApp`, cobrindo API, segurança e adaptadores.
- Unidade de casos de uso: `src/test/kotlin/guardadoc/application/UseCasesTest.kt`
  usa fakes em memória e não precisa de banco nem de servidor.

Para adicionar um teste de caso de uso, implemente apenas as portas necessárias
com um fake; não é preciso iniciar o `GuardaDocApp`.
