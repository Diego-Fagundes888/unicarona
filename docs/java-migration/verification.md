# Verificação da migração Java

> Registro histórico anterior à retirada do backend TypeScript.
> Para a estrutura atual, consulte [Retirada_Backend_TypeScript.md](../Retirada_Backend_TypeScript.md).

Migração concluída em 04/10/2026. Java 21/Spring Boot é o backend principal;
o TypeScript permanece como referência de testes e recuperação, fora do jar
e da imagem de produção. A troca conserva o frontend e o modelo de dados.

Prova final: `npm test` terminou em BUILD SUCCESS às 19:12:16 BRT (11m28s),
com 372 testes — 66 unitários e 306 integrações — em PostgreSQL 18.6 via
Testcontainers, zero falhas, erros ou skips. Os 39 relatórios XML foram
conferidos independentemente; todos pertencem à execução final.
As dez jornadas Chrome/Testcontainers passaram, zero falhas/cancelamentos/skips,
em 416,063 segundos, por volta de 19:12 BRT. Todas as 45 capturas foram
atualizadas nessa execução. A modalidade PostgreSQL local havia passado
anteriormente com 371 testes e dez jornadas.

## Contratos e regras

| Requisito | Implementação | Evidência |
| --- | --- | --- |
| 93 operações e URLs existentes | Controllers por domínio, inventário `api-baseline.json` | Registry em `IdentityDriverHttpIT`; `CompatibilityAuditIT` percorre 93 operações sem login e todas as consultas como passageiro/admin |
| HEAD, método inexistente e rota inexistente sob `/api/v1/` | Contrato GET resolve HEAD antes da autorização; operações desconhecidas retornam NOT_FOUND | `CompatibilityAuditIT`: 133 comparações adicionais, incluindo arquivo privado e query inválida; `RequestContractValidationTest` |
| Validação antes da autenticação, como no original | `RequestContractValidation` no filtro de segurança, antes da sessão; Bean Validation nos DTOs | `RequestContractValidationTest`, `AuthHttpIT`, `CompatibilityAuditIT`; esquemas de entrada locais empacotados, sem executar TypeScript |
| Corpo JSON limitado, sem coerção ou descarte de campos | Limite de 256 KiB, cache do corpo, JSON Schema estrito e configuração Jackson | Testes de sintaxe, tamanho, campos extras, tipos, query repetida e leitura posterior pelo controller |
| Login, cadastro, JWT, refresh, logout, senha | Spring Security, sessões persistidas, JWT, Argon2id, tokens de ação | `AuthHttpIT`, `AuthBoundaryTest`, jornadas `auth` e `auth-navigation` |
| Roles, suspensão, Origin, CSRF, privacidade | `ApiSecurityFilter`, autorização Spring e rechecagem transacional | Testes HTTP de cada domínio, `AdminAccountHttpIT`, leitura autenticada na auditoria |
| Schema e dados existentes | 13 migrations Flyway, validação Hibernate, baseline com checksums originais | `FlywayCompatibilityIT`: banco novo, existente e parcial; divergência/gap/versão desconhecida recusados antes da alteração |
| Perfil acadêmico, turma e verificação | Domínios student/user/driver, DTOs e serviços transacionais | `ProfileHttpIT`, `IdentityDriverHttpIT`, jornadas `signup-controls`, `admin-driver` |
| Arquivos privados, formatos e quotas | Domínio file, armazenamento privado, inspeção de conteúdo | `FileHttpIT`, `PrivateFileStorageTest`, `ImageProcessorTest`, jornada `admin-driver` |
| Viagens e última vaga concorrente | Locks no PostgreSQL/JPA e transações de reserva/confirmacão | `RideHttpIT`, `RequestHttpIT`, `PaymentHttpIT`; comparação com o original e concorrência real |
| Rotinas, cotação e mensalidade | Agenda congelada, calendário/turma, alocação inteira de centavos | `RoutineHttpIT`, `ContractHttpIT`, jornadas `monthly-usage`, `monthly-grace` |
| Pagamentos, crédito, repasses e ledger | Serviços transacionais, journal imutável, adapters e webhooks autenticados/idempotentes | `LedgerHttpIT`, `PaymentHttpIT`, `TransferHttpIT`, `LifecycleHttpIT`; perda de resposta, retry e conciliação |
| Ocorrências, bloqueios e análise financeira | Domínio occurrence, decisões relacionadas e liquidação protegida | `SafetyHttpIT`, `AdminAccountHttpIT`, jornada `monthly-usage` |
| Notificações, outbox e manutenção | Domínios notification/job, leases, retry, worker independente | `JobHttpIT`, `MaintenanceHttpIT`, `WorkerModeIT`, `WorkerLifecycleTest`; falhas sintéticas e encerramento real do processo |
| Preços, calendário e métricas | Domínio admin; versões imutáveis e consultas privadas | `AdminCatalogHttpIT`, jornada `admin-driver` |
| Datas, fusos e serialização | Fuso do campus no banco, UTC na persistência, DTOs de resposta | Testes de fusos nos domínios payment/subscription/admin/job; replays e igualdade de payloads nas consultas |
| Seeds sem servidor Node | `SeedService`, `SeedScenarios` e modos sem HTTP do jar | `SeedIT`: execução do jar, replays em ambos os sentidos, repetição, concorrência e rollback dos arquivos |
| OpenAPI e Swagger | springdoc, DTOs e customização de headers/security/erros | Assertions de Swagger nas suítes HTTP; registry de 93 operações |
| Frontend existente conectado a Java | Spring serve `dist`; runner inicia serviços Java reais | Dez jornadas em `backend-java/e2e`, Chrome real, 45 capturas, sem exceções JavaScript |

Os replays de mutações usam cenários próprios de cada domínio, respeitando
idempotência e efeitos persistidos. A auditoria de consultas pode reutilizar o
mesmo estado. Refresh/logout não são comparados executando duas mutações
sequenciais sobre uma única sessão já consumida pela primeira implementação.

O schema de entrada foi exportado do inventário anterior. Os padrões originais
de email e UUID são preservados; não há consulta a listas de TLDs que rejeitaria
domínios sintáticos válidos como `.test`. Os quatro `nullable` são normalizados
para JSON Schema; enums anuláveis recuperam a união enum-ou-null do Zod original.
Query/defaults não alteram
os dados da requisição. Não há referências remotas de schema.

## Auditoria de execução

- `spring.jpa.open-in-view=false` e `ddl-auto=validate`; não há recriação
  automática de tabelas. Clean do Flyway está desabilitado.
- Entidades de domínio usam IDs escalares; não há coleções JPA carregadas
  implicitamente durante a serialização. Respostas usam DTOs e consultas explícitas.
  Isso elimina o carregamento de relações lazy pela API; não constitui uma prova
  de desempenho de todas as consultas sob carga.
- A leitura dos repositórios de viagens, pedidos, reservas, rotinas, contratos,
  ocorrências, repasses, contas, preços/calendário e motoristas confirmou que
  os mappers das listagens não fazem chamadas adicionais ao banco por linha.
  Detalhe de pedido usa duas consultas; detalhe de ocorrência usa até três,
  carregando os casos relacionados de toda a página em lote. São contagens
  extraídas do código, sem alegação de benchmark ou plano SQL sob carga.
- Nenhum mock, Testcontainers, processo Node ou controle E2E foi encontrado em
  `src/main`. Providers de desenvolvimento são implementações explicitamente
  selecionadas pela configuração. O provedor comercial ainda não foi escolhido.
- Os testes recusam reset fora de `unicarona_java_test`. Ausência de Docker não
  produz skip silencioso; `-LocalDatabase` seleciona explicitamente PostgreSQL real.
- O suporte de comparação TypeScript e de controle por stdin existe somente no
  classpath de testes. Chaves, PINs, tokens e senhas reais não entram no jar.

## Critérios finais aprovados

- Build Linux multi-stage real: Java 21/Maven, frontend no jar e usuário uid/gid 10101.
- Docker Engine 29.8.2/Compose 5.6.0 no Ubuntu 24.04.5 WSL 2: banco e API healthy,
  seed exit 0, worker ativo sem HTTP e job de e-mail concluído pelo adapter local.
- Persistência após `compose down` sem remover volumes e nova criação:
  dados, login e hashes dos documentos/fotos preservados.
- `docker-smoke.mjs`: 93 operações Swagger iguais ao inventário, frontend,
  login, CSRF, privacidade e WebP nativo Linux de 512 × 341.
- Modo `migrate`: sem HTTP e sem seed, aplica/valida as 13 migrations e encerra
  com exit 0. Verificado pelo jar no JUnit, pelo Compose e pelo comando npm
  contra um banco descartável exclusivo.
- `npm run build`, `npm test`, comandos de API/worker/seed/migrate e Dockerfile/
  Compose principais selecionam Java. Definições antigas foram preservadas
  como `legacy:*`, `backend/legacy.Dockerfile` e `compose.typescript.yaml`.
- Todas as dependências Node são de desenvolvimento/testes. O jar conferido
  contém dashboard e contratos de entrada, sem TypeScript, `.env`,
  `JavaE2EServer` ou `LegacyAuthReference`. `src/main` não contém mocks,
  Testcontainers, processos Node ou controles de teste.
- 0 processos Java de teste e 0 diretórios ativos E2E ficaram após a execução.
  Os fixtures da tentativa interrompida foram arquivados somente em `.local`.

A execução Docker revelou duas diferenças do ambiente de testes: WSL encerrou
quando ficou sem uma sessão ativa; o keeper corrigiu isso. No encerramento E2E,
o pool Node deve fechar antes de o Java remover o PostgreSQL Testcontainers.
A ordem foi corrigida e todas as dez jornadas foram repetidas com sucesso.
As tentativas anteriores com falha não são usadas como evidência de aprovação.

Não restam pendências dos critérios da migração. A escolha e homologação de
um provedor comercial de pagamentos continuam sendo decisões do MVP; a migração
preserva os adapters atuais e não afirma que o gateway local movimenta dinheiro.
As conclusões sobre consultas vêm da leitura do código e dos testes, sem
alegação de benchmark Java sob carga.

Os registros detalhados estão em `docs/Migracao_Java.md` e
`backend-java/README.md`. Nenhum banco principal foi apagado ou reconstruído.