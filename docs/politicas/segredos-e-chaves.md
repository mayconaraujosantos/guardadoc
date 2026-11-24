# Segredos e chaves

Este documento descreve os segredos do GuardaDoc, onde ficam e como trocá-los.
Ele descreve o comportamento implementado na POC; não é uma certificação de
segurança nem uma comprovação de conformidade.

## Inventário

| Segredo | Onde fica | Para que serve | Efeito do vazamento |
|---|---|---|---|
| `APP_SECRET` | variável de ambiente / cofre de segredos | chave mestre; dela derivam (HMAC-SHA256) a chave dos OTPs, do CSRF e do limitador de requisições | permite validar códigos OTP por força bruta offline (se o banco também vazar) e forjar tokens CSRF |
| `APP_SECRET_PREVIOUS` | idem, só durante a rotação | aceita OTPs e tokens CSRF emitidos com o segredo anterior | idem, até ser removido |
| `DATABASE_PASSWORD` | idem | acesso ao PostgreSQL | acesso aos metadados (e-mails, nomes de arquivos, hashes) |
| `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY` | idem (prefira papel/IAM da instância) | acesso ao bucket privado | acesso ao conteúdo dos documentos |
| `SMTP_PASSWORD` | idem | envio dos códigos de acesso | envio de e-mails em nome do serviço |

O que **não** é segredo de configuração:

- Tokens de sessão e de compartilhamento são aleatórios (gerados por usuário).
  O banco guarda só o SHA-256 deles. Por isso, trocar o `APP_SECRET` não invalida
  sessões nem links.
- Os códigos OTP nunca são armazenados; o banco guarda apenas o HMAC deles.

## Regras

- Nenhum segredo fica no código, no repositório, em imagens Docker ou no banco.
  O `docker-compose.yml` usa valores de exemplo, que só valem para uso local.
- Fora de dev, a aplicação não inicia sem `APP_SECRET` (mínimo de 32 caracteres).
  Gere-o com `openssl rand -base64 48`.
- Somente quem opera a produção tem acesso de leitura ao cofre de segredos.
- Logs nunca incluem OTPs, tokens, cookies ou segredos (coberto por testes).

## Rotação do `APP_SECRET`

Faça a rotação periodicamente (sugestão: a cada 6 meses) e imediatamente se
houver suspeita de vazamento.

1. Gere um novo segredo.
2. Publique com `APP_SECRET=<novo>` e `APP_SECRET_PREVIOUS=<antigo>`. Durante
   essa janela:
   - OTPs pendentes e tokens CSRF antigos continuam válidos;
   - novos OTPs e tokens CSRF já usam o segredo novo;
   - os contadores do limitador de requisições recomeçam do zero, porque a
     chave deles muda.
3. Espere mais que a duração de uma sessão (12 horas). Depois desse prazo,
   todas as sessões ativas já receberam um token CSRF novo pelo `/api/me`.
4. Remova o `APP_SECRET_PREVIOUS` e publique de novo.

Esse comportamento é coberto pelo teste
`OperationsTest.rotacao de APP_SECRET preserva OTPs e sessoes em andamento`.

**Em caso de vazamento**, pule a janela de transição: publique só o segredo
novo, sem `APP_SECRET_PREVIOUS`. As sessões continuam válidas, mas o frontend
precisa obter um token CSRF novo; se for preciso encerrar as sessões, revogue-as
no banco (`UPDATE sessoes SET revogada_em = now() WHERE revogada_em IS NULL`).

## Demais credenciais

- **Banco, S3 e SMTP:** crie a credencial nova, publique, confirme que a
  aplicação funciona e só então revogue a antiga.
- **Criptografia em repouso:** é gerenciada pelo provedor (SSE no bucket,
  criptografia de disco no banco e nos backups). A rotação das chaves segue a
  política do provedor (por exemplo, rotação automática anual no AWS KMS).

## Recuperação

- Se o `APP_SECRET` for perdido, gere outro. O único efeito é invalidar OTPs
  pendentes (que expiram em 5 minutos de qualquer forma) e tokens CSRF; nenhum
  documento fica inacessível.
- Se a chave de criptografia do provedor for perdida, os dados se perdem. Ela
  precisa ter a proteção contra exclusão do provedor ativada.
