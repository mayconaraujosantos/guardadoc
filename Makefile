# GuardaDoc — atalhos para rodar e testar o projeto localmente.
# Uso: make <alvo>   (make help lista os alvos)

SHELL := /bin/bash
.DEFAULT_GOAL := help

BACKEND  := backend
FRONTEND := frontend
GRADLE   := cd $(BACKEND) && ./gradlew --console=plain
NPM      := npm --prefix $(FRONTEND)
COMPOSE  := docker compose
COMPOSE_FULL := $(COMPOSE) -f docker-compose.yml -f docker-compose.full.yml

# Testes E2E: porta própria e banco embarcado descartável (não toca em backend/data/).
E2E_PORT ?= 7071
# Repassa filtros ao Playwright, ex.: make test-e2e E2E_ARGS="-g 'exclusão de conta'"
E2E_ARGS ?=

.PHONY: help install install-frontend install-e2e build build-frontend build-backend \
        dev dev-frontend test test-backend test-e2e test-e2e-headed test-e2e-ui e2e-report \
        ci docker-up docker-up-full docker-down docker-logs clean clean-dev-data

help: ## Lista os alvos disponíveis
	@awk 'BEGIN {FS = ":.*## "; printf "Alvos:\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ------------------------------------------------------------------ instalação

install: install-frontend install-e2e ## Instala dependências do frontend e o navegador do Playwright

install-frontend: ## Instala as dependências npm do frontend
	$(NPM) ci --no-audit --no-fund

install-e2e: ## Instala o Chromium usado pelos testes E2E
	cd $(FRONTEND) && npx playwright install chromium

# ------------------------------------------------------------------ build

build: build-frontend build-backend ## Build do PWA e do backend

build-frontend: ## Build do PWA (frontend/dist, servido pelo backend)
	$(NPM) run build

build-backend: ## Compila o backend e gera o pacote de distribuição
	$(GRADLE) installDist

# ------------------------------------------------------------------ execução

dev: build-frontend ## Sobe API + PWA em http://localhost:7070 (PostgreSQL embarcado; OTPs em /dev/outbox)
	$(GRADLE) runDev

dev-frontend: ## Vite com hot reload em http://localhost:5173 (rode `make dev` em outro terminal)
	$(NPM) run dev

# ------------------------------------------------------------------ testes

test: test-backend test-e2e ## Roda todos os testes (backend + E2E)

test-backend: ## Testes de integração do backend (PostgreSQL embarcado, S3 em memória, clamd falso)
	$(GRADLE) test

test-e2e: ## Testes E2E com Playwright (build do PWA + backend real; porta 7071, mude com E2E_PORT=)
	cd $(FRONTEND) && E2E_PORT=$(E2E_PORT) npx playwright test $(E2E_ARGS)

test-e2e-headed: ## E2E com o navegador visível
	cd $(FRONTEND) && E2E_PORT=$(E2E_PORT) npx playwright test --headed $(E2E_ARGS)

test-e2e-ui: ## E2E no modo interativo do Playwright (UI mode)
	cd $(FRONTEND) && E2E_PORT=$(E2E_PORT) npx playwright test --ui $(E2E_ARGS)

e2e-report: ## Abre o último relatório HTML do Playwright
	cd $(FRONTEND) && npx playwright show-report

ci: ## Reproduz a CI localmente: instala, testa backend e E2E
	$(MAKE) install-frontend install-e2e
	CI=true $(MAKE) test

# ------------------------------------------------------------------ Docker

docker-up: ## Sobe PostgreSQL + aplicação com Docker (http://localhost:7070)
	$(COMPOSE) up --build

docker-up-full: ## Sobe também bucket S3 local (s3proxy) e ClamAV
	$(COMPOSE_FULL) up --build

docker-down: ## Derruba os containers (mantém os volumes)
	$(COMPOSE_FULL) down

docker-logs: ## Acompanha os logs da aplicação
	$(COMPOSE) logs -f app

# ------------------------------------------------------------------ limpeza

clean: ## Remove builds e relatórios (mantém os dados de dev)
	$(GRADLE) clean
	rm -rf $(FRONTEND)/dist $(FRONTEND)/test-results $(FRONTEND)/playwright-report

clean-dev-data: ## APAGA o banco e os arquivos de desenvolvimento (backend/data)
	@read -p "Apagar backend/data (banco e arquivos de dev)? [s/N] " ok && [ "$$ok" = "s" ]
	rm -rf $(BACKEND)/data
