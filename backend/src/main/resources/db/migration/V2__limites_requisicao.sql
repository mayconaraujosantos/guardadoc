-- Limitador de requisições compartilhado entre instâncias.
-- A chave é um HMAC de "<limitador>:<e-mail ou IP>": e-mails e IPs nunca são
-- armazenados em claro. Linhas antigas são removidas pela rotina de retenção.
CREATE TABLE limites_requisicao (
    chave BYTEA PRIMARY KEY,
    inicio_janela TIMESTAMPTZ NOT NULL,
    contagem INTEGER NOT NULL CHECK (contagem > 0)
);

CREATE INDEX idx_limites_requisicao_janela ON limites_requisicao (inicio_janela);
