CREATE TABLE desafios_passkey (
    id UUID PRIMARY KEY,
    usuario_id UUID REFERENCES usuarios(id) ON DELETE CASCADE,
    finalidade TEXT NOT NULL CHECK (finalidade IN ('registro', 'login')),
    request_json TEXT NOT NULL,
    expira_em TIMESTAMPTZ NOT NULL,
    consumido_em TIMESTAMPTZ,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_desafios_passkey_expiracao
    ON desafios_passkey(expira_em)
    WHERE consumido_em IS NULL;

CREATE TABLE credenciais_passkey (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    credential_id BYTEA NOT NULL UNIQUE,
    user_handle BYTEA NOT NULL,
    public_key_cose BYTEA NOT NULL,
    contador_assinatura BIGINT NOT NULL DEFAULT 0 CHECK (contador_assinatura >= 0),
    nome TEXT,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_credenciais_passkey_usuario ON credenciais_passkey(usuario_id);
