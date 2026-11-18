# Retenção e exclusão de dados

Este documento descreve por quanto tempo cada dado é mantido e como ele é
apagado. Ele descreve o comportamento implementado na POC e não comprova, por
si só, conformidade com a LGPD.

## Prazos de retenção

A rotina de manutenção roda a cada hora, em uma única instância por vez
(advisory lock no PostgreSQL), e aplica estes prazos
(`MaintenanceService.RetentionPolicy`):

| Dado | Mantido até | Configuração |
|---|---|---|
| Documento ativo e seu arquivo | o titular excluir o documento ou a conta | — |
| Desafios OTP | 1 dia após expirar | fixo |
| Sessões | 1 dia após expirar ou ser revogada | fixo |
| Janelas do limitador de requisições (HMAC de e-mail/IP, nunca em claro) | 1 dia | fixo |
| Links de compartilhamento expirados ou revogados | 90 dias | `RETENTION_SHARE_LINKS_DAYS` |
| Metadados de documento excluído (após o arquivo ser removido) | 30 dias | `RETENTION_DELETED_DOCUMENTS_DAYS` |
| Eventos de auditoria | 180 dias | `RETENTION_AUDIT_DAYS` |
| Objetos no armazenamento sem metadados (órfãos) | 1 hora de carência, depois removidos | fixo |

Os eventos de auditoria guardam somente o tipo do evento, os IDs internos e o
horário. Eles não guardam IP, user agent, OTP, tokens nem conteúdo.

## Exclusão de um documento

1. Em uma única transação, o documento é marcado como excluído e todos os seus
   links são revogados. A partir daí, ele não aparece mais e não pode ser
   baixado.
2. O arquivo é removido do armazenamento. Se a remoção falhar, uma rotina
   tenta de novo a cada 5 minutos; nesse intervalo o documento continua
   inacessível.
3. Os metadados (nome e tamanho) são apagados 30 dias depois.

## Exclusão da conta (`DELETE /api/me`)

O titular pode excluir a conta pela própria interface, na seção "Minha conta".

1. Todos os documentos são marcados como excluídos e seus links revogados, em
   uma transação.
2. Os arquivos são removidos. Se alguma remoção falhar, a varredura de órfãos
   remove o arquivo na próxima execução.
3. O usuário é apagado, junto com sessões, documentos, links e desafios OTP do
   e-mail (`ON DELETE CASCADE`).
4. Os eventos de auditoria ficam, mas sem vínculo com o usuário
   (`ON DELETE SET NULL`), e são apagados no prazo normal de 180 dias.

## Backups

A POC não faz backups por conta própria; isso fica a cargo do provedor do
banco e do bucket. **Defina estes pontos antes de receber documentos reais:**

- [ ] Prazo de retenção dos backups do PostgreSQL e de versões do bucket
      (sugestão: ≤ 30 dias).
- [ ] Se o versionamento do bucket estiver ativo, configure uma regra de ciclo
      de vida que apague versões antigas no mesmo prazo. Sem ela, um arquivo
      "excluído" continua no bucket.
- [ ] Procedimento de restauração. Depois de restaurar um backup, reaplique as
      exclusões feitas depois da data dele (por exemplo, a partir de
      `eventos_auditoria` do tipo `DOCUMENTO_EXCLUIDO` e `CONTA_EXCLUIDA`).
- [ ] Teste de restauração periódico.

## Pendências antes de usuários reais

- [ ] Aviso de privacidade publicado, informando quais dados são tratados, com
      que finalidade, por quanto tempo e como pedir a exclusão.
- [ ] Procedimento de resposta a incidentes: quem é acionado, como os titulares
      são avisados e quais registros são preservados.
- [ ] Revisão destes prazos por quem responde pela proteção de dados.
