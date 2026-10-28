# Retirada do backend TypeScript

Data da entrega: 28/10/2026. Checkpoint anterior: `d361849`, na branch
`codex/project-refactor`. O usuário autorizou expressamente retirar o backend
antigo depois da primeira etapa de refatoração.

## Escopo executado

A pasta `backend/`, seus 105 arquivos rastreados, o Compose TypeScript, oito
wrappers de testes antigos e a ponte `auth-reference.ts` foram retirados.
O README anterior foi preservado em
[README.typescript-historico.md](java-migration/README.typescript-historico.md).
O histórico Git mantém os fontes completos no checkpoint indicado.

Os 102 arquivos do build TypeScript gerado foram arquivados fora do projeto,
em `Private/data/retired-typescript-build-*`. A revisão automática bloqueou a
tentativa de exclusão recursiva, sem detalhar o motivo; o arquivamento reversível
e a retirada dos fontes pelo Git foram aceitos. Não ficaram diretórios nem comandos ativos
do backend antigo dentro do projeto.

Foram removidos os 16 comandos `legacy:*` e dez dependências diretas:
Fastify, seus seis plugins, Argon2, Nodemailer e Zod. O npm retirou 97 pacotes
instalados; as dependências diretas passaram de 17 para sete. Permanecem
Playwright, pg, sharp, tsx, TypeScript e os tipos de Node/pg, necessários aos
testes e ferramentas. O TypeScript remanescente é código de testes de navegador,
não um servidor ou worker da aplicação.

## Cobertura substituta

`ApiContractIT` substitui a execução do servidor antigo por expectativas fixas
e revisadas em `src/test/resources/api/development-status-contract.json`:
132 operações anônimas e 59 leituras para cada papel autenticado, somando 250
verificações de status/código. A suíte compara também o inventário OpenAPI real
com o conjunto completo, métodos inexistentes, origem e respostas HEAD.
O arquivo de expectativas não é regenerado pelos testes.

`JavaApplicationPeer` inicia uma segunda instância Java, com contexto, sessão,
cliente HTTP e provedor sintético próprios, sobre o banco descartável comum.
Isso verifica replays de idempotência, sessões, seeds, processamento e entrega
entre instâncias. Esse helper não é um oráculo independente. As expectativas
de domínio, saldos, auditoria, lançamentos, permissões e estados permanecem
explícitas nos testes, junto dos contratos fixos.

Cinco vetores de serialização em `LedgerFingerprintTest` protegem os bytes dos
formatos históricos dos lançamentos. O adaptador que alterava as respostas
TypeScript para a projeção atual de privacidade foi removido; as duas instâncias
Java agora devem produzir diretamente a mesma resposta minimizada.

Foram atualizadas expectativas que só existiam por causa do runtime anterior:
os quatro casos de UUID maiúsculo passam a conferir diretamente o comportamento
Java corrigido; ambos os executores de “sem aula” devem conceder crédito.
Os testes também exigem cooldown de e-mail compartilhado e que um job concluído
não seja reclamado por outra instância.

## Banco e contratos preservados

Nenhuma classe Java de produção, migration Java ou documento jurídico versionado
foi alterado. Antes da retirada, foi conferido que cada uma das 13 migrations
SQL antigas possuía cópia byte a byte idêntica em `src/main/resources/db/migration`.
As 47 migrations Java e o manifesto de checksums continuam presentes.

As classes Java de compatibilidade com schemas e tokens históricos continuam
necessárias para dados já persistidos. Seus nomes não representam dependência
do backend TypeScript. Nenhum banco da demonstração foi migrado ou substituído;
os testes usam `unicarona_java_test` em clusters próprios de loopback.

## Validação

Resultado final: 196 testes unitários e 747 cenários de integração possuem
aprovação, totalizando 943 testes Java únicos, sem falhas pendentes. A cobertura
foi concluída em rodadas integral e dirigida, descritas abaixo.

A rodada integral após `clean verify` aprovou os 196 unitários e executou os
747 testes de integração. Quatro fixtures ainda exigiam o comportamento do
TypeScript: checkout sem confirmação do trajeto, remoção de metadados de
retenção antes da comparação, leitura de UUID maiúsculo com 404 e exposição de
rotina a um participante bloqueado. Nenhum desses ajustes alterou o produto.

Após corrigir somente essas fixtures, as quatro suítes afetadas foram
reexecutadas integralmente: `PaymentHttpIT` (24), `IdentityDriverHttpIT` (11),
`ContractHttpIT` (39) e `RoutineHttpIT` (16). Os 90 testes passaram sem falhas,
erros ou skips, junto dos cinco vetores de fingerprints. Os outros 657 cenários
de integração já haviam passado na rodada integral e não foram modificados.
O último Maven `verify`, dirigido a essas classes, terminou com `BUILD SUCCESS`.

As 13 jornadas completas de navegador também passaram, sem falhas ou skips,
com as dependências reduzidas e a pasta antiga ausente. Logs:
`Private/logs/refactor/typescript-final-java.log`,
`typescript-final-focused-java.log` e `typescript-final-e2e.log`.

Também aprovados: compilação Java das adaptações, sintaxe de 48 scripts, compilação
TypeScript das ferramentas com verificação de símbolos não usados, 52 testes
de interface/contratos e um teste do resolvedor privado. `npm audit` após a
remoção reportou zero vulnerabilidades.

A porta original do PostgreSQL local não pôde ser aberta após o reinício do
computador. A validação usou clusters efêmeros em portas livres, com credenciais
sintéticas, mantendo a configuração preservada. As extensões nativas foram
preparadas no cluster descartável antes da execução; os testes de migrations
continuaram recriando e validando o schema completo. Os servidores de teste
foram encerrados ao final.

O JAR foi novamente empacotado e conferido: 69 recursos da aplicação, 62
recursos públicos e as 47 migrations presentes; os helpers e contratos de teste
não integram o artefato de produção. Nenhum fonte Java de produção mudou em
relação ao checkpoint `d361849`. O artefato final tem 82.517.277 bytes e SHA-256
`fb1e8085825e7aa3a2a8516d7234421a12f981a7df2df0847c69d610a2a1def5`.

## Limites e acompanhamento

A ampliação da auditoria HEAD registrou uma diferença existente: os endpoints
de configuração de pagamentos e estado do serviço de e-mail permitem GET
anônimo, mas HEAD retorna 401 inclusive quando o cliente envia uma sessão válida. O contrato de teste registra esse
comportamento; a retirada do backend não modificou a autorização do produto.

A imagem Docker/Linux e provedores externos reais continuam dependendo de
validação própria. Os dois avisos condicionais de Spring MVC documentados na
primeira etapa não foram alterados por esta retirada. Nenhum deploy foi feito.

Para revisar a fase: `git diff d361849 HEAD` após os commits. A recuperação do
backend anterior deve ocorrer em uma cópia separada do checkpoint, sem reset
destrutivo do workspace atual nem restauração de bancos reais.
