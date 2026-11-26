# Deploy no Railway

Produção recomendada: **um único serviço Railway** (o container do `Dockerfile`
serve a API e o PWA no mesmo domínio), **PostgreSQL do Railway**, **bucket S3
privado** para os arquivos e **SMTP** para os códigos de acesso.

O `railway.json` na raiz já configura o build por Dockerfile e o healthcheck em
`/api/health`. Não é necessário configurar Nixpacks nem comando de start: o
`ENTRYPOINT` da imagem cuida disso.

## 1. Preparar o repositório

1. Faça push do branch escolhido (por exemplo `feature/operacao-producao`).
2. No Railway: **New Project → Deploy from GitHub repo** e selecione o repositório.
3. Em **Settings → Build**, confirme `Dockerfile` na raiz (o `railway.json` já
   define `builder: DOCKERFILE` e `dockerfilePath: Dockerfile`).

O build é multi-stage: compila o PWA com `npm ci && npm run build`, gera o
distribuído do backend com `./gradlew installDist` e copia tudo para uma imagem
JRE 21 enxuta. O `STATIC_DIR` já aponta para o PWA dentro da imagem.

## 2. Banco de dados

Adicione o plugin **PostgreSQL** ao projeto Railway. Ele expõe `DATABASE_URL` no
formato `postgresql://usuario:senha@host:porta/banco`. O backend converte essa
URL para JDBC e extrai usuário/senha automaticamente — **não** copie o valor com
prefixo `jdbc:` manualmente.

As migrações Flyway (`backend/src/main/resources/db/migration`) rodam na
inicialização, antes de abrir as rotas. Não é preciso executá-las à mão.

`DATABASE_USER` e `DATABASE_PASSWORD` só devem ser definidos se você quiser
sobrescrever as credenciais da URL. Na maioria dos deploys, deixe-os vazios.

## 3. Variáveis de ambiente

Defina em **Settings → Variables** do serviço (não use `APP_ENV=dev` em produção):

| Variável | Valor sugerido | Por quê |
|---|---|---|
| `DATABASE_URL` | fornecida pelo plugin | já vem com usuario/senha; convertida para JDBC |
| `APP_SECRET` | `openssl rand -base64 48` | deriva as chaves de OTP, CSRF e limitador (≥ 32 caracteres) |
| `APP_SECRET_PREVIOUS` | segredo antigo (opcional) | rotação sem invalidar sessões/códigos |
| `PORT` | não definir | o Railway injeta a porta; a aplicação a utiliza |
| `PUBLIC_BASE_URL` | `https://<seu-dominio>.up.railway.app` | base dos links de compartilhamento e redirecionamentos |
| `ALLOWED_ORIGINS` | `https://<seu-dominio>.up.railway.app` | allowlist de `Origin` para CSRF; defina explicitamente (o padrão também inclui `http://localhost:5173`) |
| `COOKIE_SECURE` | `true` | cookie de sessão `Secure` |
| `REQUIRE_HTTPS` | `true` | recusa HTTP puro; `/api/health` continua livre |
| `TRUST_PROXY` | `true` | lê `X-Forwarded-Proto` do proxy TLS do Railway |
| `STORAGE_BACKEND` | `s3` | arquivos não ficam no filesystem efêmero do container |
| `S3_BUCKET` | nome do bucket privado | obrigatório com `s3` |
| `S3_REGION` | ex.: `us-east-1` | padrão do código |
| `S3_ENDPOINT` | endpoint de R2/MinIO/B2 (opcional) | omita para AWS S3 |
| `S3_PATH_STYLE` | `false` | `true` para MinIO/compatíveis que exigem path-style |
| `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY` | credenciais do bucket | se ausentes, usa a cadeia padrão da AWS (IAM) |
| `S3_SSE` | `true` | criptografia em repouso no servidor |
| `SMTP_HOST` | ex.: `smtp.gmail.com` | obrigatório fora de dev; sem ele a API não sobe |
| `SMTP_PORT` | `587` | `465` com `SMTP_SSL=true` |
| `SMTP_STARTTLS` | `true` | TLS oportunístico na porta 587 |
| `SMTP_SSL` | `false` | TLS implícito na porta 465 |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | conta e senha de app | no Gmail, use [senha de app](https://myaccount.google.com/apppasswords) |
| `SMTP_FROM` | remetente verificado | padrão = `SMTP_USERNAME` |
| `CLAMAV_HOST` / `CLAMAV_PORT` | opcional | antivírus; sem ele, uploads não são verificados (aviso no log) |
| `RATE_LIMIT_STORE` | `postgres` (padrão) | limitador compartilhado entre instâncias |
| `RATE_LIMIT_OTP_PER_IP` | `30` | pedidos de código por IP a cada 15 min |
| `RETENTION_*_DAYS` | ver README | prazos de retenção |

Não defina `STORAGE_DIR` nem `STATIC_DIR`: os valores da imagem
(`/app/data/storage` e `/app/static`) já são corretos, e com S3 o diretório
local não é usado para os arquivos dos usuários.

O endpoint `/dev/outbox` só existe com `APP_ENV=dev`. Em produção, os códigos
vão por e-mail e nunca são logados.

## 4. Publicar e verificar

1. Gere o domínio Railway em **Settings → Networking → Generate Domain** e use
   exatamente essa URL em `PUBLIC_BASE_URL`/`ALLOWED_ORIGINS`.
2. Faça o deploy. Acompanhe os logs: devem aparecer a migração Flyway e o
   `publicBaseUrl` correto. Um erro `APP_SECRET é obrigatório` ou `Configure
   SMTP_HOST` indica variável faltando.
3. Verifique `https://<dominio>/api/health` → `{"status":"ok"}`.
4. Abra o domínio no navegador: o PWA é servido pelo próprio backend.
5. Peça um código de acesso, confirme o e-mail, faça upload e download de um
   PDF e crie um link de compartilhamento.

## 5. Operação

- **Várias instâncias:** o limitador em PostgreSQL e os advisory locks das
  rotinas agendadas já coordenam réplicas; aumente o número de instâncias sem
  mudanças de configuração.
- **Rotação de segredos:** coloque o valor antigo em `APP_SECRET_PREVIOUS`,
  reinicie com o novo `APP_SECRET` e remova a variável antiga depois da janela
  de rotação.
- **Backups:** o plugin PostgreSQL do Railway tem backup conforme o plano; o
  bucket S3 deve ter versionamento/backup configurado no provedor.
- **Rede:** com `TRUST_PROXY=true`, o backend confia apenas em
  `X-Forwarded-Proto`/`X-Forwarded-For` vindos do proxy do Railway. Não exponha
  a porta do container diretamente à internet.
