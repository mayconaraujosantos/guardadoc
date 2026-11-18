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
    deletado_em TIMESTAMPTZ,
    -- Preenchido quando o objeto foi efetivamente removido do armazenamento.
    -- Documentos com deletado_em e sem arquivo_removido_em são reprocessados.
    arquivo_removido_em TIMESTAMPTZ
);

CREATE INDEX idx_documentos_usuario_criado
    ON documentos (usuario_id, criado_em DESC)
    WHERE deletado_em IS NULL;

CREATE INDEX idx_documentos_limpeza_pendente
    ON documentos (deletado_em)
    WHERE deletado_em IS NOT NULL AND arquivo_removido_em IS NULL;

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
