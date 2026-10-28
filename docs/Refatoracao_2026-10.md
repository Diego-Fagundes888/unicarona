# Refatoração aprovada em 27/10/2026

> Registro da primeira etapa. Depois desta entrega, o usuário autorizou a retirada
> do backend TypeScript. O estado posterior está em [Retirada_Backend_TypeScript.md](Retirada_Backend_TypeScript.md).
> As decisões de manter o backend de referência abaixo descrevem aquele checkpoint.

## Proteção e escopo

Branch: `codex/project-refactor`. Checkpoint anterior às alterações: `b49a75e`.
Esse commit preserva os fontes, testes, documentação e configurações públicas
anteriormente modificados ou não rastreados. `.env`, runtime privado, ferramentas,
artefatos gerados e capturas em `.impeccable` não foram incluídos. Nenhum envio
para repositório remoto foi realizado.

A demonstração, o banco, as chaves e a configuração locais foram preservados.
Os testes de integração usaram bancos descartáveis.
Migrations e documentos jurídicos versionados permanecem inalterados.

## Etapas

1. Registrar resultados anteriores de testes, build e jornadas.
2. Integrar verificações de fontes, interface, assets e contratos de API.
3. Remover imports e injeções comprovadamente sem uso e cache Python.
4. Atualizar dependências afetadas, preservando contratos e compatibilidade.
5. Extrair módulos de interface com estado e dependências explícitos.
6. Melhorar legibilidade e responsabilidades do backend em lotes verificáveis.
7. Consolidar configuração, otimizar assets e conferir documentação.
8. Executar regressão, build e auditoria final, registrando limites reais.

## Evidências

Os logs completos de testes ficam no diretório privado `logs/refactor/`, quando
o runtime privado está configurado. Contagens neste documento devem indicar a
execução correspondente; resultados históricos não comprovam o estado atual.

Antes da refatoração: 50 testes isolados de interface aprovados; 13 scripts
JavaScript próprios sem erro sintático; compilação TypeScript sem emissão aprovada.
A análise adicional de símbolos TypeScript identificou cinco imports e dois
parâmetros sem uso.

## Entrega e auditoria

Situação: refatoração e auditoria concluídas em 27/10/2026. O build limpo e as
suítes completas passaram. As limitações de Docker, os dois avisos condicionais
do Spring e a preservação dos diretórios temporários estão registradas abaixo.

### 1. Resumo da refatoração

A execução seguiu a aprovação do plano e manteve Java 21, Spring Boot 3.5,
PostgreSQL e frontend sem bundler. A mudança principal separa as telas das
coordenações de navegação, autenticação e eventos. O backend ganhou um
repositório de projeções financeiras, e arquivos críticos anteriormente
compactados foram formatados para permitir revisão e manutenção.

O sistema continua organizado por domínio: controllers e DTOs definem o
contrato HTTP; serviços e transações executam regras; repositories/JDBC/JPA
persistem dados; Flyway mantém o schema; jobs processam notificações,
pagamentos e retenção. `dist/` é fonte público empacotado no JAR. O backend
TypeScript é referência executada pelos testes de compatibilidade.

| Fluxo | Interface/coordenador | Domínio Java e verificação |
| --- | --- | --- |
| Cadastro, login, e-mail e recuperação | `login.js`, `features/auth/`, `api.js` | auth, security; testes HTTP e jornadas de autenticação |
| Busca, detalhes e mensalidades | `features/rides/`, `features/subscriptions/` | ride, subscription; reservas e uso mensal no navegador |
| Reservas, embarque, faltas e conclusão | `features/reservations/` | reservation; ciclo de vida, concorrência e ledger |
| Checkout, carteira e devoluções | `features/payments/` | payment; development e Mercado Pago com provedor sintético |
| Motorista, documentos e passageiros | `features/driver/` | driver, file; autorização, retenção e aprovação administrativa |
| Conta, notificações e segurança | `features/account/`, `features/safety/` | student, notification, incident; jornadas e testes HTTP |
| Privacidade e administração | módulos `privacy.js`, `admin-privacy.js`, `features/admin/` | privacy, admin; consentimentos, exportação, retenção e restrições |

### 2. Arquivos removidos

Foi removido somente o cache gerado
`backend-java/scripts/__pycache__/mercadopago-webhook-proxy.cpython-312.pyc`
(5.644 bytes). O fonte Python permanece; o interpretador regenera esse cache,
e nenhum build, rota, script ou configuração depende desse caminho. Risco baixo.
O arquivo já não integrava os fontes rastreados do checkpoint.

Foram acrescentados padrões de ignore para `__pycache__/` e `*.py[cod]`.
Nenhum arquivo funcional, asset original, endpoint ou migration foi excluído.

### 3. Dependências removidas e classificação

Removido `@types/nodemailer`: Nodemailer 10.2.1 fornece declarações próprias em
`nodemailer/dist/esm/nodemailer.d.ts`. A resolução TypeScript e a compilação
sem emissão foram verificadas após a remoção. As dependências diretas npm
passaram de 18 para 17; o lockfile foi atualizado.

| Dependências mantidas | Uso confirmado/classificação |
| --- | --- |
| Fastify e seis plugins `@fastify/*` | servidor TypeScript de referência, OpenAPI, cookies, headers, uploads e arquivos estáticos; necessários aos testes de paridade |
| `argon2`, `pg`, `nodemailer`, `zod` | autenticação, persistência, e-mail e validação da referência; necessárias |
| `@types/node`, `@types/pg`, `typescript`, `tsx` | tipos, compilação e execução de ferramentas/testes; desenvolvimento |
| `playwright`, `sharp` | jornadas reais e processamento/verificação de imagens; desenvolvimento |
| starters Spring, Security JOSE, Flyway/PostgreSQL | HTTP, persistência, segurança, e-mail, validação e migrations; necessárias |
| Bouncy Castle, Springdoc, NetworkNT | criptografia, OpenAPI e validação de contratos; necessárias |
| WebP ImageIO, metadata-extractor, TwelveMonkeys | validação/processamento seguro de uploads; necessárias, inclusive dependências carregadas por SPI |
| Spring Boot/Security Test e Testcontainers | testes unitários e de integração; desenvolvimento |

Não foram adicionadas dependências permanentes para a refatoração. Os
formatadores usados pontualmente não integram o build do produto.

### 4. Arquivos modificados

São 94 caminhos públicos adicionados ou modificados. As mudanças abrangem
coordenadores públicos, 11 novos módulos em
`dist/features/`, referências de cache nos HTMLs, projeções Mercado Pago,
melhoria da legibilidade Java (35 arquivos de produção alterados ou adicionados),
testes, scripts de verificação,
Compose, manifests de dependências e documentação. O inventário exato por
caminho está no apêndice ao final e pode ser reproduzido com
`git diff --name-status b49a75e HEAD`.

### 5. Duplicações eliminadas e código removido

`compose.java.yaml` passou de 81 para 4 linhas e inclui `compose.yaml` como
configuração canônica. A expansão dos dois comandos foi comparada com valores
sintéticos: serviços, volumes, portas, variáveis e nome do projeto equivalentes.
Os dois Dockerfiles continuam iguais para preservar os comandos documentados.

Removidos cinco imports TypeScript, 12 imports Java inicialmente identificados
e três dependências de construtor sem uso de `OutboxProcessor`. Parâmetros
obrigatórios de callbacks sem uso foram nomeados com `_`. O envio de e-mail
continua em `AuthEmailDelivery`. Os testes de interface deixam de extrair
funções por cortes de texto e usam as fábricas reais dos módulos.

Estimativa conservadora: aproximadamente 100–130 linhas de código/configuração
redundantes removidas, sem contar formatação, código movido ou lockfile.
Milhares de linhas do diff representam formatação e extração, não código morto.
O total de fontes aumenta com módulos, testes e documentação explícitos.

### 6. Melhorias arquiteturais

`backend-ui.js` passou de 4.221 para 2.001 linhas; `login.js`, de 1.436 para
773. As funções extraídas preservam suas chamadas e recebem dependências
explícitas. O estado conectado pertence a cada `attach`; referências mutáveis
continuam compartilhadas pela mesma instância, inclusive após operações
assíncronas. A autenticação centraliza seu estado em `authState`.

A comparação de árvores sintáticas encontrou 37 de 38 declarações extraídas
equivalentes após normalizar a qualificação do estado e diferenças de sintaxe
como aspas e propriedades abreviadas. A diferença restante é o texto de
“sem aula” da função `reservation`, explicado como BUG na seção 11.

`MercadoPagoReadRepository` reúne as três leituras de gateway, revisão de
devolução e devoluções. As consultas e os filtros de proprietário foram
preservados. `GatewayDetail` reside nos DTOs. Chamadas ao provedor, mutações,
idempotência, transações, autorização e cálculos continuam nos serviços atuais.

A auditoria comparou tokens com o checkpoint: 29 arquivos Java preservam o
código executável, descontando imports, comentários e formatação. As diferenças
estruturais ficam nos cinco arquivos de outbox/configuração/controller/DTO/serviço
descritos acima e no novo repositório. As três consultas extraídas também foram
comparadas com os SQLs originais e permanecem iguais, normalizando espaços.

Convenção aplicada: domínios Java existentes; módulos de interface em
`features/<domínio>/`, fábricas `create` com dependências explícitas e estado
por sessão; coordenadores responsáveis por eventos e navegação. Nenhuma
troca de framework ou nova camada genérica foi introduzida.

### 7. Segurança

Atualizações compatíveis aplicadas: Fastify 5.12.7, Nodemailer 10.2.1, Zod
4.6.7 e versões corrigidas de fast-uri no lockfile; Jackson BOM 2.21.8,
Log4j 2.25.5, Commons Lang 3.21.0, Tomcat 10.1.61, PostgreSQL JDBC 42.7.14,
Commons Compress 1.28.0 e Bouncy Castle 1.86. O gerenciamento explícito de
Commons Compress foi conferido na árvore resolvida, não apenas na propriedade.

`npm audit --json`: zero vulnerabilidades reportadas. A consulta OSV cobriu
144 coordenadas Maven resolvidas, incluindo escopos de teste; restaram dois
avisos em `spring-webmvc:6.2.19`, detalhados na seção de riscos. Isso não é
uma declaração de ausência de vulnerabilidades em Java.

A busca por assinaturas conhecidas de chaves privadas e tokens em 937 arquivos
textuais rastreados não encontrou correspondências. Essa busca tem escopo
limitado e não substitui uma auditoria de credenciais completa. Nenhum valor
privado foi incluído neste relatório ou em commits.

As fixtures novas só publicam documentos sintéticos e aceites das quatro
contas fictícias conhecidas dentro de `unicarona_java_test`. Os triggers de
imutabilidade e controles de consentimento permanecem ativos. Links de e-mail
nos testes agora são lidos pelo suporte criptográfico real, com contexto de
proprietário e evento, em vez de supor conteúdo em texto puro.

### 8. Performance

A ilustração inicial agora usa `<picture>` com WebP de 172.918 bytes e PNG
original de 2.046.647 bytes como fallback. São aproximadamente 91,6% menos
bytes transferidos para esse recurso em navegadores compatíveis, preservando
dimensões, proporção e texto alternativo. O teste de layout confere `currentSrc`.

Não foi alegada redução de latência de queries, de N+1 ou de tempo total de
carregamento: não houve benchmark comparável que sustentasse essas conclusões.
As consultas financeiras extraídas mantêm seu comportamento e seus índices.

### 9. Testes executados

Jornadas de navegador: rodada completa com 13 testes aprovados, zero falhas e
zero skips (`acceptance-e2e.log`); o teste de reservas também passou isoladamente
após corrigir sua expectativa antiga de sobrenome completo. A rodada anterior
com 12/13 foi mantida como diagnóstico, não como evidência de aprovação completa.
Suíte Java final: 191 testes unitários e 746 de integração, totalizando 937,
com zero falhas, erros ou skips (`release-java.log`). O processo Maven terminou
com código zero e `BUILD SUCCESS`.

Verificações já concluídas: 58 scripts próprios com sintaxe válida; TypeScript
sem emissão e com `noUnusedLocals`/`noUnusedParameters`; referências de cache
conferidas; 52 testes de interface/contratos e um teste do resolvedor privado
aprovados. Testes negativos rejeitam rotas ausentes, inesperadas e perfil de
pagamento incorreto. O inventário distingue 132 operações development e 144
Mercado Pago, de um conjunto de 145 contratos.

Antes da refatoração, a suíte completa apresentou 191 testes unitários sem
falhas e 746 testes de integração com três falhas e 16 erros, ligados a fixtures
desatualizadas. A rodada direcionada após ajustes aprovou os 191 unitários e
129 testes de integração. Esses números não substituem a rodada final.

As jornadas usam Chromium/Chrome e Java reais, banco descartável e provedor
Mercado Pago sintético. Não executam cobranças externas. Os logs integrais
ficam em `Private/logs/refactor/`; capturas, em `docs/previews/java-connected/`.

Para reproduzir sequencialmente no ambiente local configurado:

```powershell
npm run lint
npm run test:client
npm run test:tooling
./backend-java/scripts/maven-local.ps1 -LocalDatabase clean verify
./backend-java/scripts/e2e-local.ps1 -LocalDatabase
npm audit
```

Nesta execução, o navegador recebeu um cluster PostgreSQL efêmero próprio,
com credenciais sintéticas, para não compartilhar o banco que a suíte JUnit
recria. Não executar duas suítes sobre o mesmo banco descartável simultaneamente.

### 10. Build executado

O build final limpo `maven-local.ps1 -LocalDatabase clean verify` terminou com
`BUILD SUCCESS` em 32 minutos e 30 segundos, às 22:48:24 de 27/10/2026 (UTC−3).
O JAR `backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar` tem 82.517.277
bytes e SHA-256 `1b8e66fc698f427a4be88b264f3ebf59bfb54c8a0662a503b49e8c9e492f753a`.

Foram conferidos os 69 recursos da aplicação e os 62 recursos públicos dentro
do JAR, todos iguais aos fontes atuais. Estão presentes os 11 módulos novos e
as 47 migrations. O JAR não contém as classes de suporte de teste nem a antiga
classe aninhada `MercadoPagoService$GatewayDetail`.

A configuração Compose foi validada sem iniciar containers. O teste real de
construção/execução da imagem Docker não foi possível nesta sessão, pois o
daemon/WSL local estava indisponível. Resultados Docker históricos não foram
usados como comprovação do estado atual.

Os clusters efêmeros do navegador foram encerrados, sem marcadores de servidor
ativo remanescentes. Seus diretórios privados foram preservados: a revisão
automática bloqueou a tentativa de remoção recursiva, sem informar um motivo
específico. Isso não afeta os fontes nem o resultado dos testes.

### 11. Problemas encontrados e corrigidos

As fixtures de migrations usavam contagens anteriores à V47 e comparavam
objetos novos com snapshots de schemas antigos. Agora verificam a preservação
dos objetos originais e as contagens atuais. Fixtures de seed recriam apenas
o schema descartável pelo helper protegido, em vez de truncar tabelas com
histórico imutável. Pedidos de privacidade de teste usam o serviço real para
gravar conteúdo cifrado válido. A comparação com o legado verifica também
os novos metadados de retenção antes de comparar a projeção histórica.

As jornadas foram alinhadas aos aceites explícitos, ao leitor jurídico,
ao prazo de documentos, à identidade pública abreviada e ao único valor
não sensível armazenado em sessionStorage (prazo de reenvio). Os controles
do produto não foram relaxados para fazer testes passarem.

**BUG de texto, identificado separadamente:** a interface e a especificação
ativa ainda diziam que “sem aula” destinava o valor ao app. O backend e a
regra revisada existente já concediam crédito após confirmação/prazo.
Os textos foram corrigidos para refletir essa regra. Cálculos, ledger,
migrations e documentos jurídicos versionados não foram alterados.

A verificação dos hashes também identificou uma substituição que podia
atingir o sufixo de outro nome de recurso. A atualização agora delimita a
referência pelas aspas; o lint confere os hashes resultantes nos HTMLs.

### 12. Itens mantidos e classificação

| Item | Decisão e evidência |
| --- | --- |
| `backend/` e migrations TypeScript | MANTER: referência real de paridade chamada pelos testes Java e recuperação |
| Todas as 47 migrations Java | MANTER: histórico, upgrades, checksums e integridade; nenhuma alteração destrutiva |
| Recursos jurídicos versionados | MANTER: histórico e publicação coordenada; fixtures não publicam o catálogo do ambiente preservado |
| `dist/`, páginas públicas e módulos de privacidade | MANTER/REFATORAR: fontes servidos pelo JAR, rotas e funções de produto ativas |
| PNG original e assets de marca | MANTER: fallback, links, exportações e variantes documentadas |
| Dockerfiles/ignores alternativos, scripts operacionais e `.env.example` | MANTER: compatibilidade de build e operação, referências indiretas confirmadas |
| Runtime privado, `.env` e `.impeccable/` | MANTER: dados/configurações locais e trabalho anterior do usuário; fora dos commits |
| Campos, tabelas, endpoints e índices sem prova de abandono | INVESTIGAR/MANTER: clientes externos, jobs e retenção impedem remoção por ausência de chamada na tela |

### 13. Riscos restantes

**INVESTIGAR — médio:** CVE-2026-47884 afeta `XsltView` com resolução implícita
de nome de view. O código atual não configura XSLT views/resolvers. A ausência
dessas condições no projeto é uma análise de alcançabilidade, não uma correção
da biblioteca. [Aviso oficial Spring](https://spring.io/security/cve-2026-47884/).

**INVESTIGAR — baixo:** CVE-2026-47890 exige fragments enviados por SSE com
dados controlados por atacante. Não foram encontrados `SseEmitter`,
`ServerSentEvent`, `FragmentsRendering` ou `text/event-stream` na aplicação.
[Aviso oficial Spring](https://spring.io/security/cve-2026-47890/).

Nos dois casos, a correção 6.2.20 exige suporte Enterprise; a alternativa
pública 7.0.9 implica migração principal de framework. Essa migração não foi
incluída silenciosamente nesta refatoração. Reavaliar antes de adicionar SSE
ou renderização XSLT e planejar a atualização com regressão própria.

**Médio:** falta a execução atual da imagem Linux/Docker e de provedores
externos reais. Os testes locais e sintéticos não certificam esses ambientes.

O alias `compose.java.yaml` agora depende de uma CLI Compose com suporte a
`include`; a expansão foi validada com a CLI 5.6.0 nesta sessão. Ambientes
com Compose antigo precisam conferir essa capacidade ou usar `compose.yaml`
diretamente. Os Dockerfiles alternativos foram preservados.

**Alto, fora da execução:** publicar fontes V47 sobre a demonstração V26 exige
o procedimento coordenado de migrations, worker, interface, operações e aceites.
Não foi executado. Exclusões de banco, mudanças de autenticação/pagamentos e
contratos de integração continuam exigindo análise específica.

### 14. Sugestões futuras

Planejar atualização suportada do Spring e reexecutar a auditoria de
dependências; validar a imagem em Docker/Linux disponível; executar as
verificações integradas no CI escolhido pelo projeto. Não existia um pipeline
de CI versionado que pudesse ser considerado aprovado por esta sessão.

Se medições reais indicarem gargalos, capturar planos SQL e tempos das jornadas
antes de modificar queries ou índices. Continuar extrações de serviços grandes
apenas quando houver uma responsabilidade delimitada e testes de regressão.
Não remover tabelas, endpoints, assets ou caminhos operacionais pela aparência
de pouco uso.

## Contagens e rollback

- Um arquivo de cache gerado removido; zero arquivos funcionais excluídos.
- Uma dependência direta removida; 18 → 17 no npm.
- Aproximadamente 100–130 linhas redundantes removidas, descontando movimentos.
- Onze módulos de interface e um repositório de leitura adicionados.
- Ilustração WebP 91,6% menor em transferência; PNG mantido.
- APIs, migrations, dados e regras financeiras preservados.

Para revisar: `git log --oneline b49a75e..HEAD` e `git diff b49a75e HEAD`.
Para desfazer, revisar e reverter os commits da fase em ordem inversa sobre
uma árvore sem alterações conflitantes. Não usar reset destrutivo: o checkpoint
contém o trabalho público anterior, e itens privados/não rastreados continuam
fora dele. Nenhum push ou deploy foi realizado.

## Apêndice: inventário de caminhos

Inventário relativo ao checkpoint público, incluindo adições (`A`) e alterações
(`M`). O cache Python removido era gerado e não rastreado; por isso não aparece
como exclusão Git. Artefatos privados e `.impeccable/` não fazem parte desta lista.

```text
M	.gitignore
M	ASSETS.md
M	backend-java/e2e/admin-driver.test.ts
M	backend-java/e2e/auth-navigation.test.ts
M	backend-java/e2e/auth.test.ts
M	backend-java/e2e/helpers.ts
M	backend-java/e2e/layout.test.ts
M	backend-java/e2e/mercado-pago.test.ts
M	backend-java/e2e/monthly-usage.test.ts
A	backend-java/e2e/privacy.test.ts
M	backend-java/e2e/reservation.test.ts
M	backend-java/e2e/signup-controls.test.ts
M	backend-java/pom.xml
M	backend-java/README.md
M	backend-java/scripts/docker-smoke.mjs
M	backend-java/src/main/java/com/unicarona/auth/repository/AuthRepository.java
M	backend-java/src/main/java/com/unicarona/auth/service/AuthService.java
M	backend-java/src/main/java/com/unicarona/auth/service/AuthTransactions.java
M	backend-java/src/main/java/com/unicarona/driver/controller/DriverController.java
M	backend-java/src/main/java/com/unicarona/file/service/FileTransactions.java
M	backend-java/src/main/java/com/unicarona/file/storage/PrivateFileStorage.java
M	backend-java/src/main/java/com/unicarona/job/service/OutboxProcessor.java
M	backend-java/src/main/java/com/unicarona/notification/controller/NotificationController.java
M	backend-java/src/main/java/com/unicarona/notification/service/NotificationService.java
M	backend-java/src/main/java/com/unicarona/payment/config/MercadoPagoConfiguration.java
M	backend-java/src/main/java/com/unicarona/payment/controller/MercadoPagoController.java
M	backend-java/src/main/java/com/unicarona/payment/dto/MercadoPagoDto.java
A	backend-java/src/main/java/com/unicarona/payment/repository/MercadoPagoReadRepository.java
M	backend-java/src/main/java/com/unicarona/payment/service/DebtPaymentTransactions.java
M	backend-java/src/main/java/com/unicarona/payment/service/LedgerService.java
M	backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoRefundTransactions.java
M	backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoService.java
M	backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoTransactions.java
M	backend-java/src/main/java/com/unicarona/payment/service/PaymentConfirmation.java
M	backend-java/src/main/java/com/unicarona/payment/service/PaymentService.java
M	backend-java/src/main/java/com/unicarona/payment/service/PaymentTransactions.java
M	backend-java/src/main/java/com/unicarona/privacy/ops/PrivacyCopiesCommand.java
M	backend-java/src/main/java/com/unicarona/privacy/service/AdminPrivacyHoldTransactions.java
M	backend-java/src/main/java/com/unicarona/privacy/service/IdentityMail.java
M	backend-java/src/main/java/com/unicarona/privacy/service/LegalDocumentService.java
M	backend-java/src/main/java/com/unicarona/privacy/service/PrivacyErasureJournal.java
M	backend-java/src/main/java/com/unicarona/privacy/service/PrivacyErasureTransactions.java
M	backend-java/src/main/java/com/unicarona/privacy/service/PrivacyExportService.java
M	backend-java/src/main/java/com/unicarona/privacy/service/PrivacyRetentionSnapshot.java
M	backend-java/src/main/java/com/unicarona/reservation/service/LifecycleTransactions.java
M	backend-java/src/main/java/com/unicarona/security/ApiSecurityFilter.java
M	backend-java/src/main/java/com/unicarona/security/RequestContractValidation.java
M	backend-java/src/main/java/com/unicarona/security/SecurityConfiguration.java
M	backend-java/src/main/java/com/unicarona/shared/IdempotencyExecutor.java
M	backend-java/src/main/java/com/unicarona/student/controller/IdentityController.java
M	backend-java/src/test/java/com/unicarona/config/FlywayCompatibilityIT.java
M	backend-java/src/test/java/com/unicarona/privacy/TripDetailsRetentionIT.java
M	backend-java/src/test/java/com/unicarona/seed/SeedIT.java
M	backend-java/src/test/java/com/unicarona/support/CompatibilityAuditIT.java
M	backend-java/src/test/java/com/unicarona/support/JavaE2EServer.java
M	backend-java/src/test/java/com/unicarona/support/LegalTestFixtures.java
M	backend/src/app.ts
M	backend/src/http.ts
M	backend/src/modules/auth/service.ts
M	backend/src/modules/billing/payments.ts
M	backend/src/modules/subscriptions/contracts.ts
M	compose.java.yaml
M	dist/app.js
A	dist/assets/campus.webp
M	dist/backend-ui.js
M	dist/dashboard.html
A	dist/features/account/views.js
A	dist/features/admin/views.js
A	dist/features/auth/catalog.js
A	dist/features/auth/documents.js
A	dist/features/auth/views.js
A	dist/features/driver/views.js
A	dist/features/payments/views.js
A	dist/features/reservations/views.js
A	dist/features/rides/views.js
A	dist/features/safety/views.js
A	dist/features/subscriptions/views.js
M	dist/index.html
M	dist/legal.html
M	dist/login.html
M	dist/login.js
M	dist/test-payment.html
M	docs/CaronaUni_Especificacao_MVP.md
A	docs/Refatoracao_2026-10.md
M	package-lock.json
M	package.json
M	README.md
A	scripts/api-operations.mjs
A	scripts/check-source.mjs
A	scripts/static-assets.mjs
A	scripts/verify.mjs
M	tests/privacy-ui.test.mjs
M	tests/README.md
A	tests/tooling.test.mjs
```
