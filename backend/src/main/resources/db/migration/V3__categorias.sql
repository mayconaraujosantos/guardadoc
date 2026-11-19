-- Categorias criadas pelo usuário (ex.: "Escolar", "Trabalho / RH", "Casa").
-- Cada documento fica em no máximo uma categoria; excluir a categoria não
-- exclui documentos, apenas os deixa "sem categoria".
CREATE TABLE categorias (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    nome TEXT NOT NULL CHECK (char_length(nome) BETWEEN 1 AND 40),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_categorias_usuario_nome ON categorias (usuario_id, lower(nome));

ALTER TABLE documentos
    ADD COLUMN categoria_id UUID REFERENCES categorias(id) ON DELETE SET NULL;

CREATE INDEX idx_documentos_categoria ON documentos (categoria_id) WHERE categoria_id IS NOT NULL;
