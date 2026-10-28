# Referência histórica encerrada

O backend TypeScript foi retirado em 28/10/2026. O conteúdo abaixo documenta
a implementação anterior; seus caminhos e comandos não representam a estrutura
atual. Os fontes completos permanecem no checkpoint Git `d361849`.
As migrations equivalentes continuam no backend Java, sem alteração de bytes.

---

# UniCarona

Sistema de mobilidade universitária com caronas avulsas e planos mensais. Backend em TypeScript, Fastify e PostgreSQL, organizado como monólito modular. O frontend existente usa HTML, CSS e JavaScript, servido pela mesma aplicação com sessão em cookies HttpOnly.

`dist/` contém arquivos-fonte. O login e o painel principais carregam `api.js` e `backend-ui.js` e consultam a API. O domínio em memória e a interface do protótipo anterior estão preservados em `tests/legacy`, fora dos arquivos públicos. As entradas antigas de testes agora executam as suítes conectadas; consulte `tests/README.md`.

## Estado

Está em andamento a migração completa para Java 21/Spring Boot em `backend-java/`.
O inventário e os critérios de equivalência estão em `docs/Migracao_Java.md`;
as instruções da etapa Java estão em `backend-java/README.md`. O TypeScript
permanece como servidor principal até validar todos os fluxos no Java.

A implementação e a auditoria de conclusão estão em andamento. Há uma interface de pagamentos e transferências e um gateway local assinado para testes. Nenhum gateway real foi escolhido ou homologado. A configuração de produção rejeita provedores de desenvolvimento; o adaptador Mercado Pago ainda não está implementado.

Confirmar o e-mail não comprova identidade, idade ou vínculo acadêmico. O perfil pode ser submetido à validação administrativa, sem alegação de integração oficial com a faculdade. O mapa do campus depende do OpenStreetMap e não rastreia estudantes nem calcula preços ou distância automaticamente.

## Requisitos e instalação

Use Node.js 24 LTS, npm e PostgreSQL 18, ou Docker Engine com Docker Compose.

1. Execute `npm ci` na raiz.
2. Copie `.env.example` para `.env` e preencha os valores privados. Nunca versione esse arquivo.
3. Crie os bancos `unicarona` e `unicarona_test`, pertencentes a um usuário PostgreSQL sem privilégios de superusuário. Ajuste as URLs para sua instalação.
4. Execute `npm run db:migrate` e `npm run db:seed`.
5. Execute `npm run dev` e, em outro terminal, `npm run worker`.
6. Abra `http://127.0.0.1:3000`. Frontend, API e cookies devem usar a mesma origem definida em `APP_ORIGIN`.

O seed é exclusivo de desenvolvimento. Ele cria catálogos e contas sintéticas: `ana@example.com`, `carolina@example.com`, `lucas@example.com` e `admin@example.com`. A senha vem de `SEED_PASSWORD`; não há senha publicada no código. Reexecutar o seed preserva senhas e usuários existentes. Veículos e documentos são fictícios.

Para dados de uso, execute `npm run db:seed:scenarios`. Esse comando usa os serviços
do domínio para criar oferta, solicitação pendente, carona paga e agenda mensal paga.
Os registros são identificados como sintéticos e usam somente o gateway local.
Reexecutar preserva decisões existentes e não duplica recebimentos ou contratos.

Com `EMAIL_PROVIDER=development`, o worker entrega mensagens a arquivos privados em `DEV_MAIL_PATH`, por padrão `.local/mail`. Abra o link da mensagem local para testar confirmação e recuperação. Esses arquivos contêm tokens: não publique seus conteúdos. Configure SMTP para entrega externa.

Com `PAYMENT_PROVIDER=development`, o checkout abre uma página identificada como pagamento de teste. Ela permite produzir um resultado assinado para a cobrança do próprio usuário. O backend verifica identidade, valor, referência, prazo, idempotência e vagas. Esse endpoint só é registrado fora de produção com o provedor de desenvolvimento.

## Docker

Preencha no `.env` também `PG_SUPERUSER_PASSWORD`, `PG_APP_PASSWORD`, `SEED_PASSWORD`, `TOKEN_PEPPER`, `PIN_ENCRYPTION_KEY` e `PAYMENT_WEBHOOK_SECRET`. Use senhas longas e uma senha do banco adequada para uso em URL.

Execute `docker compose up --build`. A configuração inclui banco, migrations, seed de desenvolvimento, API e worker, com volumes para banco, arquivos privados e mensagens locais. A API fica em `http://localhost:3000`; a origem configurada pelo Compose corresponde a esse endereço.

Dockerfile e Compose ainda precisam ser validados com um Docker Engine disponível neste ambiente. Sua presença não comprova a execução dos containers.

## Variáveis de ambiente

Consulte `.env.example` e `backend/src/config.ts` para valores e validações.

| Grupo | Variáveis principais |
|---|---|
| Aplicação | `NODE_ENV`, `HOST`, `PORT`, `APP_ORIGIN` |
| Banco | `DATABASE_URL`, `TEST_DATABASE_URL`, `DB_POOL_MAX`, `DB_STATEMENT_TIMEOUT_MS` |
| Segurança | `TOKEN_PEPPER`, `PIN_ENCRYPTION_KEY`, prazos de sessão |
| Pagamentos | `PAYMENT_PROVIDER`, `PAYMENT_WEBHOOK_SECRET`, `PAYMENT_PROVIDER_TIMEOUT_MS` (10 s por padrão) |
| E-mail | `EMAIL_PROVIDER`, `DEV_MAIL_PATH`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `MAIL_FROM` |
| Arquivos | `STORAGE_PATH`, `STORAGE_USER_QUOTA_BYTES`, `STORAGE_USER_MAX_FILES` |
| Desenvolvimento | `SEED_PASSWORD`, `PG_SUPERUSER_PASSWORD`, `PG_APP_PASSWORD` |

Pepper e segredo do webhook exigem pelo menos 32 caracteres. A chave de criptografia exige 32 bytes em 64 caracteres hexadecimais. Trocar chaves depois de começar a usar o banco exige planejamento: pode invalidar sessões e impedir a leitura de PINs existentes.

## Arquitetura e banco

Migrations versionadas ficam em `backend/migrations`. O executor registra versão e checksum. Não edite migrations aplicadas: crie outra migration. O servidor HTTP não cria tabelas automaticamente.

`.gitattributes` preserva LF nas migrations e nos scripts de container, evitando
mudança de checksum ou quebra do shell ao alternar entre Windows e Linux.

Serviços concentram regras e transações. A API valida entrada e saída, autenticação, autorização, CSRF e limites. PostgreSQL fornece locks, constraints, auditoria e ledger imutável. Jobs persistidos usam leases e retries. Não há Redis, Kafka ou microsserviços no MVP.

Arquivos pessoais ficam fora do diretório público e exigem autorização para leitura. O ledger separa valores futuros, bloqueados, créditos, taxas, saldo disponível e transferências. PIN de embarque não libera o repasse.

Uploads iguais são reutilizados somente para o mesmo proprietário e finalidade.
Os limites iniciais por conta são 50 MiB e 100 arquivos, incluindo histórico
preservado. Esses limites operacionais são configuráveis. A política de retenção
e a limpeza de arquivos órfãos ainda precisam de validação antes do lançamento.

- [Especificação](../CaronaUni_Especificacao_MVP.md)
- [System Design](../System_Design.md)
- [Modelo ER e estados](../Modelo_Dados.md)
- [Decisões e políticas pendentes](../Backend_Decisoes.md)
- [Matriz do objetivo](../Backend_Plano.md)
- [Auditoria por requisito](../Backend_Auditoria.md)
- [Verificação executada e trabalho restante](../Backend_Verificacao.md)

## API e observabilidade

Rotas versionadas usam `/api/v1`. Swagger fica em `/api/docs`. `npm run api:export` atualiza `docs/api/openapi.json`.

`/health` informa se o processo responde; `/health/ready` verifica banco e migrations. Métricas e conciliação exigem acesso administrativo. Logs possuem identificador de requisição e não devem registrar senhas, tokens, cookies, documentos ou PINs.

## Testes

Configure `TEST_DATABASE_URL` para um banco descartável chamado **unicarona_test**. Os testes verificam nome e host antes de limpar dados. Nunca use um banco real nessa variável.

Execute em sequência: as suítes compartilham o banco isolado e reconstroem seus dados.

```text
npm run build
npm run test:unit
npm test
npx playwright install chromium
npm run test:e2e
npm run test:migrations
npm run test:performance
```

O E2E usa a porta 3001, servidor real, PostgreSQL e contextos de navegador separados. Verifica cadastro/teclado acadêmico, confirmação, recuperação, respostas tardias, sessão, foto privada, carona avulsa até liquidação final, contratação mensal, ausência, Sem aula, renovação desativada ou paga, carência, devolução e saque pelo gateway local, apuração conjunta, novo motorista, aprovação, calendário, parâmetros, suspensão/restauração e retomada de envios. Pode usar Chrome local ou um executável definido em `E2E_BROWSER_PATH`. Capturas ficam em `docs/previews/connected`, pasta ignorada pelo Git. Datas antigas são fixtures explícitas nos ensaios de carência; não se altera o relógio do computador.

A jornada de falhas também verifica a busca pública passando pelo login, resposta
perdida depois de uma solicitação gravada, repetição com a mesma chave, provedor
indisponível, checkout recuperado após recarregar a página, carteira offline e
retorno da conexão. A busca mantém seus filtros ao recarregar. O destino após
login aceita somente o painel da mesma origem. Uma jornada com 31 registros verifica as páginas seguintes, filtros e isolamento entre contas.

`backend/tests/failures.test.ts`, incluído em `npm test`, usa PostgreSQL real e
falhas controladas de conexão, pool, statement/lock timeout, checkout,
transferências e entregas do worker. Uma resposta financeira desconhecida mantém
a operação pendente. O resultado assinado é que libera ou restaura o saldo.
E-mails usam backoff e param em `dead` após oito tentativas; uma resposta atrasada
de um worker não pode sobrescrever uma nova tentativa. A administração consulta a fila em Tarefas de envio e retoma tarefas esgotadas com justificativa, preservando o identificador, o payload privado e o histórico de tentativas. Essa retomada não aprova pagamentos ou transferências.

`test:migrations` exige `PG_SUPERUSER_PASSWORD` para criar um banco temporário único
no mesmo servidor local indicado por `TEST_DATABASE_URL`. Aplica e repete todas
as migrations, seeds básicos e cenários; usa o papel da aplicação nas verificações
e remove somente esse banco temporário ao terminar.

`test:performance` usa o banco isolado `unicarona_test`, com 5 mil estudantes
sintéticos, 3 mil ofertas e confirmações financeiras reais do gateway de testes.
Mede tempo e quantidade de consultas de login, busca, detalhes, solicitação,
aceite, checkout, confirmação e avisos. O relatório fica em
`docs/performance/latest.json`. É uma medição local da API e do banco, sem rede
ou navegador, e não estima capacidade de produção.

As entradas `tests/*.cjs` executam as suítes atuais por um runner portátil. Os originais do protótipo ficam em `tests/legacy` como referência histórica, fora do frontend servido. O mapeamento da cobertura está em `tests/README.md`.

## Build e estrutura

`npm run build` gera `backend/build`. Use `npm start`, `npm run start:worker`, `npm run db:migrate:compiled` e `npm run db:seed:compiled` para executar o código compilado. Migrations e os arquivos de `dist` precisam acompanhar a distribuição.

```text
backend/src/modules/  auth, identity, drivers, rides, reservations,
                      subscriptions, billing, safety, notifications, jobs, admin
backend/src/shared/   contratos, validação, criptografia, auditoria, idempotência
backend/migrations/   schema versionado e invariantes
backend/tests/        API, PostgreSQL, concorrência, finanças e regras unitárias
backend/e2e/          jornadas de navegador conectadas
dist/                 frontend e cliente da API
docs/                 especificação, arquitetura, decisões, modelo e OpenAPI
```
