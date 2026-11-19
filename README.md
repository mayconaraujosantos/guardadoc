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

### Experiência no celular (PWA)

- Instalável (Android/Chrome com convite próprio; iPhone com instrução para
  "Adicionar à Tela de Início"), abre em tela cheia, ícone *maskable* e atalhos
  no ícone ("Tirar foto", "Enviar").
- Navegação por abas inferiores, *bottom sheets* para ações e confirmações, e o
  botão "voltar" do celular fecha a camada aberta em vez de sair do app.
- Envio pela câmera, galeria ou arquivos (vários de uma vez), com progresso;
  arrastar e soltar no desktop.
- Compartilhamento pela folha nativa do sistema (Web Share) ou copiar link;
  prazos em *chips* e limite de downloads com *stepper*.
- Código de acesso em 6 caixas com preenchimento automático e envio ao
  completar; atalho para abrir o webmail.
- Puxar para atualizar, busca, *skeleton* de carregamento, toasts, aviso de
  offline, vibração leve, modo escuro e áreas seguras (notch/barra de gestos).
- Prévia de imagens apenas em memória (nunca em cache ou armazenamento).

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
- Arquivos: armazenamento privado acessado somente pelo backend — disco local
  (`LocalFileSystemStorage`) ou bucket S3/compatível privado com SSE
  (`S3DocumentStorage`, `STORAGE_BACKEND=s3`).
- Antivírus: ClamAV via protocolo `INSTREAM` (`CLAMAV_HOST`), opcional.
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
    account/     núcleo: exclusão da conta pelo titular
    maintenance/ retenção de dados, varredura de órfãos, lock entre instâncias
    shared/      criptografia, erros de aplicação, porta de auditoria
    adapters/
      http/         adaptador de entrada (Javalin): rotas, CSRF, cabeçalhos
      persistence/  adaptadores PostgreSQL de todas as portas de repositório
      storage/      armazenamento privado: disco local ou S3
      scan/         antivírus ClamAV
      email/        SMTP e outbox de desenvolvimento
      ratelimit/    limitador em PostgreSQL (multi-instância) ou em memória
    GuardaDocApp.kt  composition root (liga portas a adaptadores)
  src/main/resources/db/migration/   migrações Flyway
  src/dev/kotlin/    DevMain: PostgreSQL embarcado para desenvolvimento
  src/test/kotlin/   testes de integração (PostgreSQL embarcado, HTTP real)
frontend/            PWA Vue/Vite (e2e/: testes Playwright)
docs/                artefatos de arquitetura e fluxos de dados
docs/politicas/      segredos e chaves; retenção e exclusão
.github/workflows/   CI: testes do backend, E2E e build da imagem
```

## Como executar

Pré-requisitos: JDK 21 e Node 20+. Docker é opcional.

### Atalhos com `make`

```bash
make install          # dependências do frontend + Chromium do Playwright
make dev              # build do PWA + API em http://localhost:7070 (OTPs em /dev/outbox)
make dev-frontend     # Vite com hot reload em :5173 (com `make dev` em outro terminal)
make test             # testes do backend + E2E
make test-e2e         # só E2E; filtre com E2E_ARGS="-g 'logout'"
make test-e2e-headed  # E2E com navegador visível (ou test-e2e-ui para o modo interativo)
make docker-up-full   # Docker com S3 local e ClamAV
make email-test TO=voce@exemplo.com  # testa o envio SMTP configurado no .env
make help             # lista todos os alvos
```

Os comandos equivalentes, sem `make`, estão abaixo.

### Envio de e-mails (Gmail)

Sem `SMTP_HOST`, o modo dev não envia e-mails: os códigos ficam em
`/dev/outbox`. Para receber os códigos de verdade pelo Gmail:

1. Na conta Google, ative a verificação em duas etapas e crie uma
   [senha de app](https://myaccount.google.com/apppasswords). A senha normal da
   conta não funciona por SMTP.
2. `cp .env.example .env` e preencha `SMTP_USERNAME`, `SMTP_PASSWORD` (entre
   aspas; os espaços da senha de app são ignorados) e, se quiser, `SMTP_FROM`.
   O `.env` é ignorado pelo git.
3. `make email-test TO=voce@exemplo.com`. Em caso de falha, a mensagem diz a
   causa: autenticação, conexão, TLS ou remetente recusado.
4. `make dev` (ou `make docker-up`) passa a enviar os códigos por e-mail, e o
   `/dev/outbox` fica desativado.

Os testes E2E não leem o `.env` e continuam usando o outbox. O Gmail gratuito
limita os envios a cerca de 500 por dia; em produção, prefira um serviço
transacional (SES, Postmark, Mailgun...), configurado com as mesmas variáveis.

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

Ambiente mais próximo de produção, com bucket S3 privado local (s3proxy) e
antivírus ClamAV (a primeira subida do ClamAV leva alguns minutos):

```bash
docker compose -f docker-compose.yml -f docker-compose.full.yml up --build
```

### Testes

```bash
cd backend && ./gradlew test        # integração: PostgreSQL embarcado, S3 em memória, clamd falso
cd frontend && npm run test:e2e     # E2E (desktop e celular emulado): build do PWA + backend real na porta 7071
```

Os testes E2E sobem o próprio backend com um banco embarcado descartável
(`backend/build/e2e-pg`), sem tocar nos dados de `backend/data/`. Na primeira
vez, instale o navegador com `npx playwright install chromium`.

### Variáveis de ambiente

| Variável | Padrão | Descrição |
|---|---|---|
| `APP_ENV` | — | `dev` habilita `/dev/outbox` e segredo aleatório |
| `APP_SECRET` | obrigatório fora de dev | segredo mestre (≥ 32 caracteres); deriva as chaves de OTP, CSRF e limitador |
| `APP_SECRET_PREVIOUS` | — | segredo anterior aceito durante a rotação (ver `docs/politicas/segredos-e-chaves.md`) |
| `PORT` | `7070` | porta HTTP |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/guardadoc`, `guardadoc`, `guardadoc` | PostgreSQL |
| `PUBLIC_BASE_URL` | `http://localhost:$PORT` | base dos links de compartilhamento |
| `ALLOWED_ORIGINS` | `PUBLIC_BASE_URL`, `http://localhost:5173` | allowlist de `Origin` para requisições que alteram dados |
| `COOKIE_SECURE` | `true` (`false` no `runDev`) | atributo `Secure` do cookie de sessão |
| `REQUIRE_HTTPS` | `true` fora de dev | recusa HTTP puro (exceto `/api/health`); GETs são redirecionados para `PUBLIC_BASE_URL` |
| `TRUST_PROXY` | `false` | confia em `X-Forwarded-For`/`X-Forwarded-Proto` do proxy TLS |
| `RATE_LIMIT_STORE` | `postgres` | `postgres` (compartilhado entre instâncias) ou `memory` |
| `RATE_LIMIT_OTP_PER_IP` | `30` | Pedidos de código por IP a cada 15 min (o E2E usa um valor alto) |
| `STORAGE_BACKEND` | `local` | `local` (disco) ou `s3` |
| `STORAGE_DIR` | `./data/storage` | diretório privado dos arquivos (backend `local`) |
| `S3_BUCKET` | obrigatório com `s3` | bucket privado |
| `S3_REGION` / `S3_ENDPOINT` / `S3_PATH_STYLE` | `us-east-1` / — / `false` | região; endpoint e path-style para S3 compatível (R2, MinIO, B2...) |
| `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY` | — | credenciais; se ausentes, usa a cadeia padrão da AWS (IAM) |
| `S3_SSE` | `true` | criptografia em repouso no servidor (SSE) |
| `CLAMAV_HOST` / `CLAMAV_PORT` | — / `3310` | antivírus; sem host, uploads não são verificados (aviso no log fora de dev) |
| `RETENTION_SHARE_LINKS_DAYS` | `90` | retenção de links expirados/revogados |
| `RETENTION_DELETED_DOCUMENTS_DAYS` | `30` | retenção de metadados de documentos excluídos |
| `RETENTION_AUDIT_DAYS` | `180` | retenção de eventos de auditoria |
| `STATIC_DIR` | `../frontend/dist` | build do PWA |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_STARTTLS`, `SMTP_SSL` | — / `587` (`465` com SSL) | envio de e-mail real (obrigatório fora de dev). `SMTP_FROM` padrão = `SMTP_USERNAME`; `SMTP_SSL=true` para TLS implícito (porta 465) |

### API

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/otp` | solicita código (resposta genérica) |
| POST | `/api/auth/otp/verify` | valida código, cria sessão e devolve token CSRF |
| GET | `/api/me` | sessão atual e token CSRF |
| POST | `/api/auth/logout` | revoga a sessão |
| DELETE | `/api/me` | exclui a conta, todos os documentos, arquivos e links |
| GET | `/api/health` | verificação de saúde (dispensa HTTPS) |
| GET/POST | `/api/documents` | lista / envia (multipart, campo `file`) |
| GET | `/api/documents/{id}/download` | baixa como anexo |
| DELETE | `/api/documents/{id}` | exclui documento, revoga links e remove arquivo |
| GET/POST | `/api/documents/{id}/shares` | lista / cria link (`expiresInMinutes` obrigatório, `downloadLimit` opcional) |
| DELETE | `/api/shares/{id}` | revoga link |
| GET | `/s/{token}` | página pública do link (sem recursos externos) |
| GET | `/s/{token}/download` | download pelo link |

Requisições autenticadas que alteram dados exigem o cabeçalho `X-CSRF-Token`.

Uploads também podem ser recusados com `422` (rejeitado pelo antivírus) ou
`503` (antivírus indisponível — nada é armazenado).

### Operação

- **Várias instâncias:** o limitador de requisições fica no PostgreSQL, e as
  rotinas agendadas (limpeza de arquivos pendentes a cada 5 min; retenção e
  varredura de órfãos a cada hora) rodam em uma única instância por vez via
  advisory lock.
- **Políticas:** `docs/politicas/segredos-e-chaves.md` (inventário e rotação) e
  `docs/politicas/retencao-e-exclusao.md` (prazos, exclusão, backups).

### Limitações conhecidas da POC

- Backups, aviso de privacidade e resposta a incidentes ainda precisam ser
  definidos (checklist em `docs/politicas/retencao-e-exclusao.md`).
- Sem antivírus configurado, os uploads passam apenas pela validação de tipo e
  conteúdo.

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

Cobertos por testes de integração em `backend/src/test/kotlin` e, para o cache
do PWA e os fluxos na interface, pelos testes E2E em `frontend/e2e`:

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
