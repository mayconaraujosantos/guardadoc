# GuardaDoc

O GuardaDoc é uma POC de um sistema web PWA para guardar documentos pessoais e
compartilhá-los por links com validade. O repositório contém os artefatos de
arquitetura e uma POC funcional: backend em Kotlin/Javalin e PWA em Vue.

O PNG em `docs/` é uma exportação anterior do diagrama e ainda contém uma
referência visual à conformidade com a LGPD e ao Java 25 (a POC usa Kotlin).
A referência à LGPD não é válida: o
diagrama é conceitual e não comprova conformidade. Use esta especificação e o
arquivo editável `.excalidraw` como referência atual até que o PNG seja
exportado novamente.

## Escopo da POC

- Autenticação por código OTP enviado por e-mail, sem senha.
- Upload privado, listagem, download e exclusão dos documentos do usuário.
- Links de compartilhamento somente leitura, com expiração obrigatória e
  revogação pelo proprietário.
- PWA responsiva em Vue. O service worker pode armazenar apenas a interface
  estática; não pode armazenar documentos, respostas autenticadas da API ou
  tokens.
- Monólito modular com portas e adaptadores.

Fora do escopo da primeira POC: acesso offline aos documentos, armazenamento
público, criptografia ponta a ponta, links protegidos por senha e alegações de
conformidade legal ou com a LGPD.

## Stack da POC

- Frontend: Vue 3, JavaScript, Vite e `vite-plugin-pwa`.
- Backend: **Kotlin 2.4 (JVM 21) e Javalin 7**. A especificação original citava
  Java 25; a POC foi implementada em Kotlin e compila para JVM 21 (LTS
  disponível no ambiente). Para subir para JVM 25, altere `jvmToolchain(21)` em
  `backend/build.gradle.kts` (o plugin foojay baixa o JDK automaticamente) e a
  imagem do `Dockerfile`.
- Banco de dados: PostgreSQL (migrações com Flyway, acesso via JDBC/HikariCP).
- Arquivos: armazenamento privado em disco local (`LocalFileSystemStorage`),
  acessado somente pelo backend; trocável por um bucket privado implementando a
  porta `DocumentStorage`.
- E-mail: SMTP (`SmtpEmailSender`) ou, em desenvolvimento, outbox em memória
  consultável em `/dev/outbox`.
- Implantação: frontend e API servidos no mesmo site pelo backend (sem CORS).
  Em produção, usar HTTPS (proxy TLS) com `COOKIE_SECURE=true`.

## Estrutura

```text
backend/
  src/main/kotlin/guardadoc/
    auth/        núcleo: RequestOtp, VerifyOtp/CreateSession + portas
    documents/   núcleo: Upload, List, Download, Delete + portas
    sharing/     núcleo: CreateShareLink, AccessSharedDocument, RevokeShareLink
    shared/      criptografia, erros de aplicação, porta de auditoria
    adapters/
      http/         adaptador de entrada (Javalin): rotas, CSRF, cabeçalhos
      persistence/  adaptadores PostgreSQL de todas as portas de repositório
      storage/      armazenamento privado de arquivos
      email/        SMTP e outbox de desenvolvimento
      ratelimit/    limitador em memória
    GuardaDocApp.kt  composition root (liga portas a adaptadores)
  src/main/resources/db/migration/   migrações Flyway
  src/dev/kotlin/    DevMain: PostgreSQL embarcado para desenvolvimento
  src/test/kotlin/   testes de integração (PostgreSQL embarcado, HTTP real)
frontend/            PWA Vue/Vite
docs/                artefatos de arquitetura e fluxos de dados
```

## Como executar

Pré-requisitos: JDK 21 e Node 20+. Docker é opcional.

### Desenvolvimento (sem Docker)

```bash
# 1. Build do PWA (o backend serve ../frontend/dist)
cd frontend && npm install && npm run build && cd ..

# 2. API + PostgreSQL embarcado (dados em backend/data/)
cd backend && ./gradlew runDev
```

Acesse <http://localhost:7070>. O código OTP aparece em
<http://localhost:7070/dev/outbox> (somente em modo dev; nada é logado).

Para desenvolver o frontend com hot reload, mantenha `runDev` rodando e execute
`npm run dev` em `frontend/` (<http://localhost:5173>, com proxy para a API).

### Com Docker

```bash
docker compose up --build
```

### Testes

```bash
cd backend && ./gradlew test
```

### Variáveis de ambiente

| Variável | Padrão | Descrição |
|---|---|---|
| `APP_ENV` | — | `dev` habilita `/dev/outbox` e segredo aleatório |
| `APP_SECRET` | obrigatório fora de dev | segredo mestre (≥ 32 caracteres); deriva as chaves de OTP e CSRF |
| `PORT` | `7070` | porta HTTP |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/guardadoc`, `guardadoc`, `guardadoc` | PostgreSQL |
| `PUBLIC_BASE_URL` | `http://localhost:$PORT` | base dos links de compartilhamento |
| `ALLOWED_ORIGINS` | `PUBLIC_BASE_URL`, `http://localhost:5173` | allowlist de `Origin` para requisições que alteram dados |
| `COOKIE_SECURE` | `true` (`false` no `runDev`) | atributo `Secure` do cookie de sessão |
| `TRUST_PROXY` | `false` | usa `X-Forwarded-For` para limitar por IP |
| `STORAGE_DIR` | `./data/storage` | diretório privado dos arquivos |
| `STATIC_DIR` | `../frontend/dist` | build do PWA |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_STARTTLS` | — | envio de e-mail real (obrigatório fora de dev) |

### API

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/otp` | solicita código (resposta genérica) |
| POST | `/api/auth/otp/verify` | valida código, cria sessão e devolve token CSRF |
| GET | `/api/me` | sessão atual e token CSRF |
| POST | `/api/auth/logout` | revoga a sessão |
| GET/POST | `/api/documents` | lista / envia (multipart, campo `file`) |
| GET | `/api/documents/{id}/download` | baixa como anexo |
| DELETE | `/api/documents/{id}` | exclui documento, revoga links e remove arquivo |
| GET/POST | `/api/documents/{id}/shares` | lista / cria link (`expiresInMinutes` obrigatório, `downloadLimit` opcional) |
| DELETE | `/api/shares/{id}` | revoga link |
| GET | `/s/{token}` | página pública do link (sem recursos externos) |
| GET | `/s/{token}/download` | download pelo link |

Requisições autenticadas que alteram dados exigem o cabeçalho `X-CSRF-Token`.

### Limitações conhecidas da POC

- Limitador de requisições em memória: adequado a uma única instância.
- Armazenamento em disco local; em produção usar bucket privado com
  criptografia em repouso gerenciada pelo provedor.
- Sem limpeza automática de objetos órfãos gerados por falhas fora do fluxo de
  upload (o upload já compensa falhas ao gravar metadados).

## Requisitos de segurança da POC

### OTP e sessões

- Gerar código de seis dígitos com aleatoriedade criptograficamente segura;
  expirar em cinco minutos, permitir no máximo cinco tentativas e aceitar uma
  única utilização.
- Armazenar HMAC do OTP usando segredo mantido no servidor, nunca o código.
  Invalidar desafios anteriores ainda ativos ao emitir um novo.
- Verificar e consumir o desafio em uma única operação transacional/atômica
  para que requisições concorrentes não aceitem o mesmo OTP mais de uma vez.
- Limitar requisições e tentativas por e-mail normalizado e IP de origem.
  Retornar respostas genéricas para não revelar se o e-mail está cadastrado.
  Nunca registrar códigos ou segredos de autenticação em logs.
- Após a verificação, criar token de sessão aleatório e opaco. Enviá-lo somente
  em cookie `Secure`, `HttpOnly`, `SameSite=Lax` e armazenar apenas seu hash.
  Proteger operações que alteram dados contra CSRF e exigir HTTPS.

### Upload e acesso aos documentos

- Limites da POC: PDF, JPEG e PNG; máximo de 10 MiB por arquivo. Validar no
  backend tanto o tamanho declarado quanto o tamanho efetivamente recebido.
- Validar assinatura/conteúdo, além do MIME e extensão informados. Tratar nomes
  e caminhos enviados pelo cliente como não confiáveis; gerar chaves opacas de
  armazenamento e nunca usar caminhos fornecidos pelo usuário.
- Manter os arquivos em bucket/container privado, sem acesso público. Autorizar
  a propriedade no backend em toda listagem, download ou exclusão. Servir
  downloads como anexos com `X-Content-Type-Options: nosniff`.
- Na exclusão, marcar o documento como excluído e revogar os links em uma
  transação antes de remover o objeto. Se a remoção falhar, manter o documento
  inacessível e repetir a limpeza; não deixar o arquivo acessível após remover
  seus metadados.
- Usar criptografia em repouso gerenciada pelo provedor para arquivos, banco e
  backups; usar HTTPS em trânsito. Manter chaves e segredos fora do código e
  dos dados armazenados. Restringir acesso às chaves e definir procedimentos de
  rotação e recuperação.
- Esta POC não fornece criptografia ponta a ponta: o backend/provedor de
  armazenamento pode acessar o conteúdo dos documentos. Não a descrever como
  tal.
- Tratar falhas entre banco e armazenamento de objetos (inclusive arquivos
  órfãos) e testar que excluir um documento também remove seu arquivo.

### Links de compartilhamento

- Gerar token bearer aleatório de alta entropia e persistir somente seu hash
  criptográfico. Exigir expiração, permitir revogação pelo proprietário e
  usar acesso somente leitura por padrão.
- Validar hash do token, expiração, revogação e eventual limite de downloads
  e se o documento ainda está ativo antes de ler o arquivo. Atualizar
  contadores atomicamente se houver limite.
- Remover tokens de logs. Nas páginas de compartilhamento, usar
  `Referrer-Policy: no-referrer`, evitar recursos de terceiros e impedir cache
  de páginas com tokens e respostas com arquivos no navegador/service worker.

### PWA, privacidade e operação

- Armazenar em cache somente recursos estáticos versionados e o shell da
  aplicação. Excluir respostas da API, documentos, OTPs, dados de sessão e
  tokens de compartilhamento do cache do service worker e de armazenamento
  persistente no navegador; responder com `Cache-Control: no-store` em
  endpoints autenticados e de compartilhamento.
- Manter eventos de auditoria mínimos: tipo do evento, identificadores internos
  relevantes e horário. Não coletar IP ou user agent por padrão. Se uma
  necessidade de segurança justificar a coleta, definir finalidade, controle
  de acesso e retenção antes de habilitá-la. Nunca incluir OTPs, tokens
  completos ou conteúdo de documentos.
- Definir retenção, exclusão, recuperação de backups, resposta a incidentes e
  avisos de privacidade antes de receber documentos de usuários reais. Esses
  controles, isoladamente, não comprovam conformidade com a LGPD.

## Esquema PostgreSQL inicial

O esquema está implementado em
`backend/src/main/resources/db/migration/V1__schema_inicial.sql`, com a coluna
adicional `documentos.arquivo_removido_em` para reprocessar remoções de
arquivos que falharem. A aplicação deve normalizar e-mails consistentemente. O segredo
HMAC usado nos OTPs deve ficar fora do PostgreSQL. Tokens de sessão e
compartilhamento são segredos aleatórios; somente seus hashes SHA-256 são
armazenados.

```sql
CREATE TABLE usuarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_usuarios_email_normalizado
    ON usuarios (lower(email));

CREATE TABLE desafios_otp (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL,
    digest_hmac BYTEA NOT NULL,
    expira_em TIMESTAMPTZ NOT NULL,
    tentativas SMALLINT NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
    max_tentativas SMALLINT NOT NULL DEFAULT 5 CHECK (max_tentativas > 0),
    consumido_em TIMESTAMPTZ,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_desafios_otp_email_criado
    ON desafios_otp (lower(email), criado_em DESC);

CREATE TABLE sessoes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE,
    expira_em TIMESTAMPTZ NOT NULL,
    revogada_em TIMESTAMPTZ,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sessoes_usuario ON sessoes (usuario_id);

CREATE TABLE documentos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    storage_key TEXT NOT NULL UNIQUE,
    nome_original TEXT NOT NULL,
    mime_type TEXT NOT NULL
        CHECK (mime_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    tamanho_bytes BIGINT NOT NULL
        CHECK (tamanho_bytes > 0 AND tamanho_bytes <= 10485760),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deletado_em TIMESTAMPTZ
);

CREATE INDEX idx_documentos_usuario_criado
    ON documentos (usuario_id, criado_em DESC)
    WHERE deletado_em IS NULL;

CREATE TABLE links_compartilhamento (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_id UUID NOT NULL REFERENCES documentos(id) ON DELETE CASCADE,
    criado_por UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE,
    expira_em TIMESTAMPTZ NOT NULL,
    revogado_em TIMESTAMPTZ,
    limite_downloads INTEGER CHECK (limite_downloads IS NULL OR limite_downloads > 0),
    downloads INTEGER NOT NULL DEFAULT 0 CHECK (downloads >= 0),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_links_documento_expiracao
    ON links_compartilhamento (documento_id, expira_em);

CREATE TABLE eventos_auditoria (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    evento TEXT NOT NULL,
    usuario_id UUID REFERENCES usuarios(id) ON DELETE SET NULL,
    documento_id UUID REFERENCES documentos(id) ON DELETE SET NULL,
    link_id UUID REFERENCES links_compartilhamento(id) ON DELETE SET NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_eventos_auditoria_criado ON eventos_auditoria (criado_em);
```

## Critérios de aceite da POC

Cobertos por testes de integração em `backend/src/test/kotlin` (exceto o cache
do PWA, garantido pela configuração do Workbox em `frontend/vite.config.js`):

- OTP expira, só pode ser consumido uma vez, é rejeitado após cinco tentativas
  inválidas e está sujeito a limite de requisições, inclusive com tentativas
  concorrentes de verificação.
- Um usuário não pode listar, baixar, excluir ou compartilhar documentos de
  outro usuário.
- O upload rejeita arquivos acima de 10 MiB e arquivos cujo conteúdo real não
  seja PDF/JPEG/PNG permitido, independentemente do MIME declarado pelo cliente.
- Um compartilhamento funciona somente antes da expiração e revogação; links
  inválidos ou associados a documentos excluídos não provocam leitura no
  armazenamento. Limites de download, se habilitados, não podem ser
  ultrapassados por requisições concorrentes.
- API, páginas de compartilhamento e respostas de documentos não entram no
  cache do PWA.
- Excluir documento remove o arquivo do armazenamento privado; logs não contêm
  OTP, token de sessão, token de compartilhamento ou conteúdo dos documentos.
