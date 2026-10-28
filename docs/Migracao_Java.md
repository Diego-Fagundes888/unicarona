# Migração completa para Java — inventário e critérios

> Registro histórico anterior à retirada do backend TypeScript.
> Para a estrutura atual, consulte [Retirada_Backend_TypeScript.md](Retirada_Backend_TypeScript.md).

> Atualização de 16/10/2026: os resultados abaixo registram o comportamento das respectivas etapas de migração. A regra histórica que destinava “Sem aula” à plataforma foi substituída nas novas liquidações Java por crédito integral ao passageiro. Ver [regra revisada](privacy/Sem_Aula_Regra_Revisada.md); os testes novos e a situação de implantação constam no [plano de privacidade](privacy/Plano_Implementacao.md).

Objetivo: substituir o backend TypeScript por Java 21/Spring Boot sem modificar
o contrato do frontend ou perder regras. Trabalho em andamento; compilação de
uma etapa não significa equivalência ou conclusão. O TypeScript permanece em
`backend/` até todas as verificações finais. A implementação nova fica em
`backend-java/`. Nenhuma delegação ou alteração de dados existentes foi necessária
para este inventário.

## Análise anterior à implementação

Foram examinados os arquivos de `backend/src`, scripts, migrations 001–013,
contratos, rotas, configuração, Docker e organização dos testes existentes.
O inventário de endpoints e modelos de entrada/saída fica em
`docs/java-migration/api-baseline.json`, extraído do OpenAPI atual, e a lista
legível em `docs/java-migration/endpoints.md`. São 93 operações incluindo health.

Arquitetura atual: monólito modular Fastify, PostgreSQL com pool, SQL
parametrizado, transactions explícitas, Zod estrito, static da pasta `dist`,
worker independente com outbox e manutenção. Não existem classes Repository;
consultas estão nos services e funções auxiliares `one/rows`. Migração requer
separar persistência e orquestração, usar Spring Data JPA para entidades e
repositories e consultas nativas explícitas para locks, relatórios e invariantes
que dependem do PostgreSQL. Não substituir triggers por validação superficial
ou usar H2 para provar comportamento PostgreSQL.

| Domínio atual | Responsabilidade e destino Spring |
|---|---|
| auth | Registro, confirmação, recuperação, login, refresh, logout e senha; DTOs, services transacionais e Spring Security |
| identity | Perfil acadêmico, turma, settings e revisão administrativa; user/student |
| drivers | Documentos privados, aplicações, veículo e aprovação; driver/vehicle |
| rides | Publicação, edição otimista, cálculo e busca paginada; ride |
| subscriptions | Rotina, materialização, cotação, contrato, carência, encerramento e espera; subscription |
| reservations | Pedido/aceite, embarque PIN, execução, final, ausências, cancelamento e alteração coletiva; reservation |
| billing | Preços, crédito, pagamento, ledger, transferência e webhooks; payment |
| safety | Relatos, bloqueios, apuração conjunta e decisões divergentes; occurrence |
| admin | Contas, calendário, pricing, métricas e retomada de jobs; admin |
| notifications/jobs | Avisos persistidos, e-mail, lease, retries e manutenção; notification/jobs |
| shared/http | Errors, request ID, CSP, Origin, CSRF, quota/rate limit, idempotência, crypto, DTOs; shared/config/security |

Entidades físicas (incluindo tabelas associativas e operacionais): universities,
courses, classes, regions, users, student_profiles, sessions, auth_tokens,
rate_limits, stored_files, driver_profiles, vehicles, driver_applications,
pricing_versions, ride_routes, routines, rides, calendar_exclusions,
subscriptions, ride_requests, request_lines, payments, ride_reservations,
subscription_schedule, parcels, ledger_accounts, ledger_transactions,
ledger_entries, transfers, occurrences, occurrence_parcels, user_blocks,
waitlist, notifications, ratings, audit_logs, idempotency_keys, webhook_events,
outbox_jobs e schema_migrations. Ratings existe no schema e não possui endpoint
no MVP; a migração não deve inventar uma funcionalidade de estrelas.

DTOs de entrada vêm das schemas estritas das rotas; saída vem de
`shared/contracts.ts`. Não retornar entidades JPA. Preservar camelCase, campos
nulos, campos opcionais como PIN/foto, integer cents, datas de campus, timestamps,
paginação por nextOffset e respostas de erro code/message/details/requestId.
O snapshot OpenAPI inclui parâmetros, corpos, status e schemas por operação.

Autenticação atual usa cookies HttpOnly `uc_access/uc_refresh`, cookie legível
`uc_csrf`, HMAC com pepper e sessões duráveis; não usa JWT. A preferência JWT
do novo objetivo será aplicada ao access token mantendo cookies e consulta da
sessão para revogação imediata. Tokens opacos existentes precisam continuar
válidos até expirar/revogar. Roles físicas são user/admin; autoridades PASSENGER,
DRIVER e ADMIN serão derivadas, DRIVER apenas após aprovação, sem mudar os dados
ou permitir assignment pelo cliente. Conta suspensa mantém leitura segura e
encerramento de compromissos em andamento.

## Regras que não podem mudar

- Argon2id compatível com hashes atuais; reset/change revogam sessões.
  Login revalida hash sob lock; refresh/logout usam lock do usuário antes da
  sessão e replay revoga a família mesmo quando a resposta é erro.
- Idempotência por ator/chave/escopo/corpo no mesmo COMMIT; falha não consome
  chave. Webhook autenticado é persistido antes do processamento de domínio.
- Locks ordenados de usuários, recursos e contas; deadlocks/serialization
  permitem retry limitado da transação inteira. Última vaga só confirma uma
  reserva paga; perdedores pagos recebem crédito integral externo.
- Dinheiro em centavos; preço racional inteiro e alocação mensal determinística.
  Ledger equilibrado e imutável, sem saldo negativo e sem liberar saldo futuro.
- Mensal exige mesmo curso/período/turma e aceite explícito de toda a agenda.
  Desconto congelado 10%, calendário conhecido excluído e parcelas contratadas
  preservadas. Carência 72h retém todos os trechos somente se todos livres;
  períodos novos não pagos não permitem embarque. Renovação é manual e paga.
- PIN só embarca; conclusão abre janela 24h. Ausência mensal não revende vaga,
  três faltas geram revisão. Sem aula posterior retém a parcela integral sem
  comissão; cancelamento do motorista credita parcelas elegíveis.
- Política avulsa indefinida segue análise. Relatos relacionados bloqueiam até
  todos serem resolvidos; divergência exige reconhecer IDs e justificar.
- Bloqueio é bilateral na combinação/contato/foto. Fotos apenas por participação
  paga; documentos por proprietário/admin, quota concorrente e armazenamento
  fisicamente fora da pasta pública. Foto recodificada sem EXIF.
- Aprovação de veículo não pode reduzir abaixo das reservas existentes. Revisão
  de conta não confisca saldo liquidado nem revive contratos ao restaurar acesso.
- Outbox usa SKIP LOCKED, lease, fencing por attempts e oito falhas; retry
  administrativo preserva identidade, payload e tentativas. Envio externo fica
  fora de locks; teste local não move dinheiro nem envia e-mail a pessoas.

## Banco e Flyway

Preservar literalmente as 13 migrations SQL, índices, constraints, triggers,
extensões e tipos. Hibernate não deve criar/alterar schema (`ddl-auto=validate`
ou `none` para projeções parciais justificadas). Banco novo usa Flyway V1–V13.
Banco anterior exige validar todos os SHA256 de schema_migrations e continuidade
das versões antes de baseline explícita; nunca aceitar baseline automático de
qualquer banco não vazio. Transição precisa compartilhar advisory lock antigo.
Testar banco vazio, banco TypeScript existente, repeat, checksum inválido e
migration ausente; nenhum reset de banco real.

## Integrações e testes identificados

Providers payment/transfer development com HMAC canônico, prazo e dedup por ID;
adapter comercial não homologado no original. E-mail development em diretório
privado ou SMTP. Validação acadêmica administrativa, sem integração institucional
alegada. OpenStreetMap pertence ao frontend e não representa GPS/geocoding.

Testes existentes: 15 arquivos de integração/API/segurança/concorrência em
backend/tests, unitários pricing/private-storage, fresh-migrations e performance;
dez jornadas Playwright em backend/e2e. Há wrappers em tests e arquivo histórico
em tests/legacy. Nomes e assertions devem ser migrados para JUnit 5/Spring Boot
Test/Mockito e PostgreSQL real; Testcontainers é preferido. Não contar testes
TypeScript contra Fastify como evidência do Java. Comparações entre servidores
devem normalizar somente IDs/instantes aleatórios, preservando status, valores,
campos ausentes/nulos, permissões e efeitos no banco. Testes Java de banco local
isolado podem avançar enquanto não existe Docker; não substituem gate Docker.

## Ordem e auditoria de conclusão

1. Inventário e contratos congelados; setup Java 21/Maven e dependências verificadas.
2. Configuração, persistência/Flyway, errors/validation, static, health e catálogos.
3. Identidade e autenticação completa com regressões de segurança/concorrência.
4. Documentos, motorista/veículo e publicação/busca.
5. Mensal, pedidos, pagamento/ledger e confirmação concorrente.
6. Execução/cancelamento, segurança, administração, outbox/manutenção/seeds.
7. Controllers/repositories/unit/security/integration e comparação da API.
8. Frontend existente conectado ao Java: cadastro/login, publicar/buscar,
   motorista/passageiro/reservar, mensal/pagar, ocorrências/avisos/histórico.
9. Swagger completo, infraestrutura Java/Docker e testes em containers.
10. Auditoria por todas as 93 operações e regras acima; datas/timezones, SQL,
    JPA/lazy/N+1, serialização, roles, migrations, skips/mocks e ausência de
    dependência runtime TypeScript. Só então mover/remover legado.

## Evidências da primeira etapa

Java 21.0.12.1 e Maven 3.9.11 portáteis configurados em `.local/java-tools`, com
checksums dos archives verificados. Maven Wrapper também foi gerado e fixa o
SHA256. Spring Boot 3.5.16, Flyway 11.20.3 (PostgreSQL 18), JPA/Hibernate,
Security, Web, Validation, Mail, springdoc, JUnit 5, Mockito e Testcontainers
estão no projeto novo. O frontend é reutilizado/empacotado sem mudanças.

`maven-local.ps1 -LocalDatabase verify` passou em 02/10/2026 às 18:44:08 BRT:
8 unitários e 12 de integração, zero failures/errors/skips. Esses testes provam
somente a primeira etapa: preço/alocação, crypto, catálogo, boot/HTTP/static e
migrations. O PostgreSQL real é 18.6 em banco dedicado `unicarona_java_test`.
Testcontainers está configurado como modo padrão; execução em container ainda
não foi feita porque Docker não está disponível. Não há fallback/skip automático.

- Banco vazio recebeu V1–V13 e repeat não duplicou versões.
- Schema TypeScript 001–013 com dados sintéticos recebeu baseline 13, sem
  alteração de dados, definições de colunas/tipos/defaults, constraints, índices,
  funções ou triggers existentes.
- Schema parcial 001–011 recebeu somente V12–V13. Checksum inválido ou gap no
  histórico, assim como versão desconhecida, falhou antes da baseline.
- Hibernate detectou e permitiu corrigir o mapping de `char(2)` sem alterar o
  tipo físico. Flyway foi atualizado depois de uma versão inicial não declarar
  suporte ao PostgreSQL 18.
- Spring serve dashboard e catálogos sem expor entidades; catálogos filtram
  faculdades/cursos inativos. Alterar checksum torna readiness 503 e liveness 200.
  Operação protegida permanece fechada com 401/code/message/details/requestId;
  arquivo inexistente responde 404 no mesmo envelope, sem stack ou credenciais.
- Fixture sintética produzida pelo Node é verificada/decriptada pelo Java.
  Node 0.45 ordena Argon2 como m,p,t; o Java normaliza somente a leitura para
  m,t,p, mantendo hashes armazenados. O verificador reverso valida senha/hash
  Java e AES-GCM no Node. Nenhuma senha/PIN de usuário foi usado na fixture.

## Evidências da autenticação e consulta do perfil

`maven-local.ps1 -LocalDatabase verify` passou em 02/10/2026 às 19:10:59 BRT:
13 unitários e 29 de integração, zero failures/errors/skips, PostgreSQL real
18.6 no banco exclusivo Java. São 42 classes de produção e 15 operações HTTP
implementadas das 93 inventariadas, incluindo as quatro da etapa inicial.

- As dez rotas Auth preservam URLs, métodos, bodies e mensagens: cadastro 202,
  login/perfil, verificar e reenviar e-mail, recuperar/redefinir/trocar senha,
  refresh e logout. GET users/me compartilha o DTO explícito de perfil próprio.
- Senhas Argon2, normalização de e-mail/nome/telefone, idade de 18 anos com
  timezone São Paulo e rollover de 29/02, academic/class/shift, token de uso
  único e resposta genérica de cadastro/recuperação foram migrados. Falha do
  vínculo acadêmico reverte user, profile, token, outbox e audit na transação.
- JWT HS256 valida assinatura, issuer/audience, subject/sid/family. HMAC do
  access continua em sessions e a validade/revogação é consultada em cada
  requisição; a autorização não depende de roles declaradas pelo cliente/JWT.
  Perfis acadêmicos geram PASSENGER, motorista aprovado gera DRIVER e role
  administrativo gera ADMIN. Estado atual do banco prevalece sobre a sessão.
- Cookies HttpOnly/SameSite, Origin obrigatório nas mutações, CSRF vinculado
  à sessão, limites persistentes com bucket HMAC e IP sem confiar em proxy,
  conta restrita ainda podendo gerir segurança, reset/change revogando todas
  as sessões, refresh rotacionando a família e logout foram verificados.
- Duas renovações concorrentes produzem um sucesso e SESSION_REUSED, com
  toda a família revogada. Logout concorrente não deixa um filho ativo. Senha
  alterada entre a verificação Argon2 e o lock do login não emite nova sessão.
  Revogação por reutilização confirma commit antes de produzir HTTP 401.
- @Transactional fica em serviço separado com repository JDBC para locks e
  SQL atômico, compartilhando DataSource/JPA; leitura de credenciais usa Spring
  Data JPA. Argon2 fica fora dos locks. Deadlock/serialization podem repetir a
  transação inteira até três vezes, mantendo lock_timeout=3s, sem repetir erro
  de negócio ou lock timeout. Unitários verificam seleção e limite do retry.
- Bean Validation e Jackson rejeitam unknown/ignored fields, scalars coercidos,
  período numerado sem número, turno ausente, data impossível, e-mail/UUID
  fora do formato original e null explícito em campos opcionais não anuláveis.
  Deserializer distingue ausência de null em records sem obrigar esses campos.
- Teste de referência inicia Fastify original apenas no banco dedicado Java,
  compara status e bodies de login/perfil, cadastro duplicado, recuperação,
  reenvio, refresh e credenciais inválidas. Somente requestId aleatório é
  removido da comparação de erro. Cookies opacos emitidos pelo TypeScript são
  aceitos pelo Java; JWT emitido pelo Java é aceito pelo registro original.
- Swagger das rotas migradas inclui cookieAuth, Origin/CSRF obrigatórios,
  DTOs, status 202 de cadastro e envelopes de erro; foi verificado por HTTP.

Limites desta evidência: links de autenticação foram lidos/decriptados apenas
de jobs sintéticos, sem afirmar entrega real; o worker Java ainda não existe.
Ainda não foi executada a jornada de navegador conectada ao Java, nem Docker.
Não extrapolar esta comparação para as 93 operações. TypeScript continua como
servidor principal, sem remoção, movimentação ou alteração nesta etapa.

Na etapa seguinte foram migradas preferências/verificação administrativa,
documentos/motorista/veículo e a base financeira, conforme evidências abaixo.
Edição acadêmica ainda precisa preservar encerramento financeiro dos mensais.
Comparação integral da API,
frontend/E2E, worker/adapters, infraestrutura Java/Docker e auditoria final
permanecem pendentes. Provedor de pagamento ainda não foi escolhido pelo usuário;
continuar com integração desacoplada em testes. O objetivo permanece ativo.

## Identidade, motorista, arquivos e base financeira

A suíte completa `maven-local.ps1 -LocalDatabase verify` passou em 02/10/2026
às 20:11:59 BRT: 20 unitários e 58 de integração (78 testes), zero
failures/errors/skips. PostgreSQL real 18.6, banco exclusivo Java; 92 fontes de
produção e 29 operações das 93 inventariadas. A validação de Spring/JPA inclui
os novos mappings, com ddl-auto=validate e open-in-view=false.
Após revisar multipart, `verify -Dit.test=FileHttpIT` passou novamente às
20:14:28 BRT (20 unitários e os oito testes HTTP de arquivos). Campos são
contados pelas partes multipart, incluindo nomes repetidos; parâmetros da URL
não substituem purpose nem consomem a quota de campos. Multipart malformado
retorna erro 400 sanitizado. A comparação TypeScript inclui parâmetros extras
na URL do envio.

Operações acrescentadas após autenticação:

| Grupo | Operações migradas |
|---|---:|
| Settings, solicitar verificação acadêmica, fila e decisão administrativa | 4 |
| Notificações próprias e marcar como lida | 2 |
| Solicitar motorista, listar próprias, fila administrativa e revisar | 4 |
| Enviar e baixar arquivo privado | 2 |
| Carteira própria e conciliação administrativa | 2 |

- Preferências preservam os nulls/admin/perfil ausente e continuam acessíveis
  em conta restrita. Adapter acadêmico fica fora da transação; snapshot do
  vínculo é revalidado sob lock. Decisão concorrente altera o estado apenas
  uma vez e grava audit/aviso na mesma transação. Avisos essenciais não são
  suprimidos quando notices=false. Consulta/marcação de avisos não expõe
  notificações alheias, e readAt não muda ao repetir a operação.
- Motorista usa documentos próprios com finalidade correta. A chave/corpo
  normalizado preserva o idempotency hash e o replay TypeScript→Java e inverso.
  Solicitação posterior substitui somente a pendente. Aprovação troca veículo
  de rotinas/viagens planejadas, preserva veículo de viagem ativa e rejeita
  capacidade menor que reservas ou vagas retidas, com rollback integral.
- Arquivos mantêm magic bytes, finalidade, limites de 2/5 MB e 16 milhões de
  pixels, PDF somente para documentos, original do documento e foto WebP até
  512 × 512 sem ampliar. Orientação EXIF é aplicada e removida da foto; todos
  os oito sentidos são verificados. JPEG/PNG/WebP não usam processo Node no
  runtime Java. Codec native WebP foi exercitado neste Windows. Diretório
  privado não pode resolver para dentro do frontend, mesmo via junction.
- Upload locka o dono antes de deduplicar/aplicar quota; quota inclui arquivos
  apagados. Duas cópias simultâneas produzem um registro e um arquivo. Falha
  limpa o candidato sem apagar o arquivo anterior. Documento só é entregue a
  dono/admin; foto depende de participação vinculada sem bloqueio bilateral.
  Conta suspensa conserva leitura própria, mas não pode enviar novo arquivo.
  Cache privado, anexo e CSP de documentos foram verificados por HTTP.
- O TypeScript de referência compartilha apenas DB Java e storage temporário
  sintéticos. Documento conserva bytes/ID/headers entre os dois servidores.
  Uma foto de referência sem resize produz o mesmo WebP/dedup/ID e pode ser
  lida por ambos. Isso não prova identidade binária de todo o corpus de fotos
  redimensionadas/transparências/perfis ICC; ampliar essa comparação na auditoria.
- Ledger usa Spring Data JPA para o header imutável e SQL atômico para contas,
  locks e entries; toda gravação exige @Transactional externo. Valores são
  centavos inteiros, limite seguro do contrato JS, journal equilibrado e sem
  saldo negativo (exceto clearing). Um evento antigo conserva o body_hash,
  inclusive as cinco ordens de campos usadas por pagamento, parcela e repasse.
  Replays reais dos cinco formatos foram comparados com o post TypeScript.
- Locks do evento/contas tornam replay simultâneo uma única movimentação.
  Duas operações que disputam o último saldo resultam em uma confirmação e
  INSUFFICIENT_BALANCE. Hash alterado retorna IDEMPOTENCY_CONFLICT. Triggers
  continuam impedindo editar/apagar histórico ou confirmar distribuição
  incompatível. Soma fora do limite seguro falha explicitamente; isso evita
  apresentar dinheiro arredondado (o contrato antigo também rejeita overflow).
- Parcelas são primitives internos com row lock: block conserva idempotência;
  future/awaiting podem ser encerradas, blocked exige revisão; estado terminal
  não admite decisão diferente. Released separa comissão inteira/repasse;
  credited retorna tudo ao crédito; retained conserva tudo sem comissão.
  Duas decisões opostas concorrentes encerram uma vez. Os testes incluem
  pagamento/request/reserva/parcelas sintéticos sujeitos aos triggers reais,
  sem afirmar que checkout ou lifecycle Java já estejam completos.
- Wallet inclui future/blocked por parcelas para os dois participantes e
  available/credit/processing/withdrawn/returned pelo ledger próprio. Conciliação
  é administrativa, soma clearing/alocação e conserva difference=0. Ambos foram
  comparados com HTTP do TypeScript; conta restrita continua vendo a carteira.
- Swagger mostra os novos DTOs, multipart/binary, paginação, status, Origin/CSRF
  e Idempotency-Key obrigatório. A contagem 29 é verificada no registry Spring.

### Repasses e adapters de testes

Em 02/10/2026 às 20:36:05 BRT, a suíte completa passou com 92 testes
(25 unitários e 67 integrações reais), sem failures/errors/skips. Após acrescentar
três testes do adapter de pagamento, a verificação selecionada de foundation e
transfer passou às 20:37:56 BRT: 28 unitários e 15 integrações. O conjunto tem
95 testes; ainda não houve execução integral dos 95 nessa etapa. São 32 de 93
operações, verificadas no registry Spring.

- POST/GET transfers e POST webhooks/transfers preservam o contrato antigo.
  Saque usa driver_available; devolução usa passenger_credit. Valores futuros
  não podem ser usados. Falha na reserva desfaz transferência, chave e outbox.
  Conta restrita conserva acesso ao seu dinheiro e histórico.
- Evento autenticado é registrado antes da transação de negócio, com hash
  imutável. Falha conserva recibo retryable e código sanitizado. Aprovação/falha
  encerra o saldo reservado uma única vez; evento alterado ou decisão oposta
  falha sem movimentar dinheiro. Concorrência e replay nos dois sentidos entre
  TypeScript e Java foram exercitados por HTTP em PostgreSQL real.
- Envio usa ID estável, timeout e adapter fora da transação. Resposta perdida
  não cria outro ID; timeout interrompe a chamada e conserva o saldo reservado.
  O worker/outbox ainda não foi migrado: os testes invocam submit explicitamente,
  mantendo o job pendente. Não há alegação de repasse real.
- Adapters usam auto-configuration que recua diante de bean fornecido pela
  aplicação. Assinaturas de pagamento e transferência preservam seus diferentes
  prefixos, ordem de campos e tolerância temporal. Fixtures são sintéticas.
  Provedor ainda não escolhido; a versão de desenvolvimento gera um link para
  test-payment.html, mas checkout e webhook de pagamento seguem pendentes.

### Publicação, edição e leitura de viagens

Em 02/10/2026 às 21:05:40 BRT, verify integral passou com 109 testes:
30 unitários e 79 integrações reais, sem failures/errors/skips. A aplicação tem
129 fontes Java de produção e 37 das 93 operações. Os 12 testes HTTP novos de
viagens exercitam PostgreSQL real e o backend antigo como referência isolada.

- POST rides, PATCH rides/:id e GET busca/mine/detail preservam o contrato.
  Curso, período e turma nulos são mantidos; placas dependem de vínculo pago
  ou propriedade. Vínculo histórico permite consulta em conta restrita;
  publicação/edição/busca exigem conta ativa. Busca respeita faculdade, acentos,
  direção, hora, filtros, bloqueio bilateral, aprovação e vagas retidas.
- Publicação usa preço inteiro, capacidade do veículo aprovado e instante
  futuro no fuso da faculdade. Edição locka usuário e viagem; versão concorrente
  confirma uma alteração. Idempotência simultânea publica uma vez. Falha desfaz
  rota/chave/audit. Preços de requests, reservas e parcelas não são recalculados.
  Copia-se apenas a rota da viagem editada; rotina e viagens irmãs a conservam.
- Campos opcionais ausentes continuam ausentes no fingerprint; null explícito
  é rejeitado. Números normalizados usam o formato JSON.stringify, incluindo
  inteiros escritos como 1.0, notação científica e zero negativo. Um corpus
  sintético de 1200 doubles aleatórios mais limites foi comparado com Node.
  Node é somente referência de teste, sem dependência no runtime Java.
- Horários locais usam JDBC 4.2 para evitar deslocamento causado por Calendar.
  Datas/horários gravados, lidos e editados foram conferidos sob quatro fusos
  padrão da JVM: UTC, Asia/Tokyo, America/Los_Angeles e America/Sao_Paulo.
  A faculdade também foi testada com Pacific/Kiritimati para rejeitar instante
  passado que parece futuro no calendário de São Paulo.
- Replays reais nos dois sentidos conferem criação e edição, inclusive UUID
  maiúsculo na resposta/chave. Busca/histórico/detalhes, paginação e visibilidade
  histórica foram comparados; OpenAPI mostra as cinco operações e seus filtros.

### Rotinas e cotação mensal

Em 02/10/2026 às 21:25:28 BRT, verify integral passou com 124 testes:
30 unitários e 94 integrações reais, sem failures/errors/skips. São 139 fontes
Java de produção e 43 das 93 operações, com a contagem conferida no registry.
Os 15 testes HTTP novos de rotina/cotação usam PostgreSQL real.

- Publicação preserva ordem de weekdays, sentidos/horários opcionais e preço
  inteiro; materializa somente os dias escolhidos até o fim do mês do campus.
  Repetição retorna o mesmo ID. Colisão de saída do mesmo motorista desfaz
  rotina, rota, trechos, chave e audit. Não se inventou edição de preço da rotina.
- Busca ordena por compatibilidade e respeita turma/faculdade, região, sentido,
  hora, bloqueio bilateral e aprovação. Detalhe respeita vínculo histórico,
  inclusive em conta restrita. Mine e novas combinações continuam exigindo
  conta ativa. DTO conserva objetos times/prices sem campos do sentido ausente.
- Pausa/reabertura muda disponibilidade para novas adesões, conservando os
  trechos publicados. Reabertura avisa interessados compatíveis, ativos,
  com notices=true e sem bloqueio. Repetir enabled=true não duplica o aviso.
- Cotação exige curso/período/turma iguais, sentido/dias oferecidos, início
  futuro e renovação válida. Materializa agenda até o fim do mês, elimina
  exclusões ativas do campus/turma e trechos já passados. Preços e comissão
  dos trechos permanecem congelados; desconto vem da versão vigente e é
  alocado em centavos inteiros na ordem da agenda, com soma exata.
- Trechos indisponíveis, estados incompatíveis e reserva própria entram em
  conflicts; holds do contrato anterior autorizado são excluídos da ocupação.
  Preview não cria request/payment/contrato, não retém vaga nem movimenta ledger.
  Rotina com automatic=true ainda dependerá de aceite mensal explícito na
  próxima etapa de requests; isso não foi substituído por aceite automático.
- Publicação e replay nos dois sentidos, leituras, disponibilidade e cotação
  foram comparadas com o backend TypeScript. Horários da rotina/materialização
  foram conferidos sob UTC, Asia/Tokyo e America/Sao_Paulo. OpenAPI inclui as
  seis operações, filtros e headers necessários.
- Melhoria intencional de concorrência: o TypeScript lê a rotina antes de esperar
  os locks de usuários em subscriptions/service.ts:24–25. Java relê depois dos
  locks; uma pausa confirmada durante essa espera impede gerar nova agenda e
  retorna ROUTINE_UNAVAILABLE. Um teste controla esse interleaving em PostgreSQL.
  Outro confirma o lock compartilhado do campus até o COMMIT da cotação, para
  que alterações administrativas do calendário esperem a transação completa.

### Pedidos de reserva e cobrança aguardando checkout

Em 02/10/2026 às 23:59:59 BRT, verify integral passou com 140 testes:
30 unitários e 110 integrações reais, sem failures/errors/skips. São 155 fontes
Java de produção e 48 das 93 operações, conferidas no registry Spring.
Os 16 novos testes HTTP de requests usam PostgreSQL 18.6 no banco separado
unicarona_java_test, preservando todos os triggers e constraints financeiros.

- As cinco operações de criação/lista/detalhe/aceite/cancelamento preservam
  URLs, bodies, status, códigos de negócio e paginação. DTOs de criação são
  uma união avulsa/mensal estrita, com type explícito; campos desconhecidos,
  null opcional explícito, datas inválidas e weekdays repetidos são rejeitados.
  Inteiros escritos como 1.0 preservam a normalização do TypeScript.
- Criação avulsa exige participante ativo/verificado, motorista aprovado,
  mesma faculdade e ausência de bloqueio bilateral. A viagem fica sob lock;
  assentos pagos e retidos por mensalidade entram na disponibilidade. Os
  valores do trecho e a comissão são copiados para request_lines. Edição
  posterior da oferta conserva o preço e a comissão que serão cobrados.
- Avulsa automática começa accepted e cria pagamento awaiting; manual começa
  pending. Mensal sempre começa pending, inclusive com rotina automática:
  o motorista precisa aceitar a agenda inteira. Criação mensal com conflicts
  desfaz pedido, linhas, chave e trechos materializados pela tentativa.
- Aceite exige pedido pendente ainda válido. Revalida turma congelada no mensal,
  estado futuro e disponibilidade de cada trecho, incluindo proposta de horário.
  Pedido mensal pode ficar pendente durante a proposta, como no TypeScript;
  o aceite fica impedido até sua resolução. Rejeição não cria cobrança.
- Pagamento awaiting conserva o total das linhas, provider configurado e prazo
  menor entre vencimento do pedido e CHECKOUT_EXPIRY_MINUTES. Crédito/external
  começam em zero; não há referência/link do gateway, journal, reserva ou
  contrato. Pedidos aceitos concorrentes podem compartilhar a oferta: a vaga
  só será efetivamente disputada na confirmação do pagamento da próxima etapa.
- Cancelamento pending/accepted expira cobrança awaiting/pending. Operações
  preservam notificações essenciais e audit na mesma transação. Usuários são
  bloqueados em ordem antes de request/viagens; repetição simultânea cria um
  pedido, uma cobrança e um audit. Falhas não persistem a chave de idempotência.
- Lista conserva lado, filtro e campos nulos da avulsa/mensal. Detalhe primeiro
  autoriza o participante e depois lê a agenda ordenada em outra query fixa,
  sem relacionamentos lazy ou consultas por linha. Conta suspensa pode consultar
  seu detalhe histórico, mas não listar novas combinações ou mudar pedidos.
- Criação/replay nos dois sentidos entre Java e TypeScript foram comparados
  para avulsa e mensal, inclusive renovação opcional e weekdays em float inteiro.
  Aceite, recusa, cancelamento e leituras também foram comparados; UUID maiúsculo
  no parâmetro continua preservado nas respostas e fingerprints de mutação.
  OpenAPI identifica os dois schemas da união e todos os headers necessários.
  A referência tsx ganhou deadline de readiness de 45 s para imports frios no
  Windows; chamadas de negócio mantêm timeout de 15 s e falham sem skips.

O PostgreSQL local estava parado na primeira execução desta etapa e foi
reativado antes da prova. Os testes continuaram confinados ao banco separado;
nenhuma configuração privada foi incluída nos arquivos ou na evidência.
Comando da prova: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
O modo padrão segue Testcontainers e falha quando Docker não está disponível.
Naquela prova, checkout, webhook de pagamento e gateway local ainda não tinham
rotas Java abertas. A etapa abaixo registra sua implementação posterior.

### Checkout, pagamentos e confirmação de reservas

Em 03/10/2026 às 17:13:41 BRT, verify integral passou com 159 testes:
30 unitários e 129 integrações reais, sem failures/errors/skips. São 169 fontes
Java de produção e 52 das 93 operações, conferidas no registry Spring.
Os 19 novos testes PaymentHttpIT usam PostgreSQL 18.6 no banco separado,
preservando os triggers de pagamento, contrato, parcela e ledger existentes.

- Checkout/detalhe/webhook e resultado local preservam bodies, status, erros,
  headers e autorização. PIX/cartão, escolha de crédito e idempotência usam
  os mesmos fingerprints do TypeScript, inclusive UUID maiúsculo no parâmetro.
- Preparação, envio externo e confirmação têm transações separadas. O adapter
  recebe ID estável e timeout; perda de resposta pode ser retomada. Cancelamento
  consegue confirmar enquanto o adapter ainda responde, sem manter locks de rede.
- Confirmação cria reservas com PIN cifrado e hash compatíveis, parcelas futuras
  e journal balanceado. Mensal cria contrato e agenda com preços/comissão congelados.
  Renovação libera holds anteriores e marca o contrato anterior como renewed.
- Duas confirmações concorrentes da última vaga produzem uma reserva. O recebimento
  externo da tentativa sem vaga vira crédito, com reconciliação conservada. Expiração,
  cancelamento, bloqueio, reprovação do motorista, falta de crédito prometido e perda
  de elegibilidade mensal também impedem ocupar vaga e preservam o dinheiro recebido.
- HMAC é validado antes de persistir recibo; conflito de payload é rejeitado. Falha
  de processamento fica registrada e admite reenvio do mesmo evento após recuperação.
  Evento repetido ou resultado oposto após pagamento confirmado não movimenta novamente.
- Checkout/leitura/replays e confirmação foram comparados com o backend original.
  Contratos e PINs criados em Java foram lidos por ele sob UTC, Tokyo e São Paulo.
  Inteiro em float, ordem do JSON e trim da referência mantêm assinatura canônica;
  identificador com espaços e referência longa continuam rejeitados como no original.
  Essa última comparação detectou e corrigiu uma diferença antes da prova integral.
- OpenAPI identifica os quatro contratos e os headers HMAC/Origin/CSRF/idempotência.
  O adapter development e a página local simulam resultados; não houve cobrança real
  nem escolha de provedor comercial. A integração permanece desacoplada.

Comando da prova: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
O modo padrão segue Testcontainers; Docker e E2E completos permanecem pendentes.

### Processador de outbox, e-mail e retomada administrativa

Em 03/10/2026 às 18:46:14 BRT, verify integral passou com 178 testes:
34 unitários e 144 integrações reais, sem failures/errors/skips. São 181 fontes
Java de produção e 54 das 93 operações, conferidas no registry Spring.
A etapa acrescenta 15 testes JobHttpIT e quatro EmailProviderTest; os testes
anteriores continuam incluídos na prova integral.

- GET admin/jobs e POST admin/jobs/{id}/retry preservam paginação, ordenação,
  contagens, erros públicos, roles, restrição de conta, Origin/CSRF e idempotência.
  A leitura não seleciona payload, destinatário, links cifrados nem event_key.
  Tipo desconhecido aparece como unknown e erro não autorizado como TASK_FAILED.
- Retomada exige tarefa dead e expectedAttempts atual. Preserva ID, event_key,
  payload, número de tentativas e código anterior; audit e chave são confirmados
  na mesma transação. Duas retomadas concorrentes só liberam uma tentativa.
  Falha não consome chave. Replay continua disponível mesmo depois do envio.
- O processador usa FOR UPDATE SKIP LOCKED, lease de 60 segundos e incremento
  monotônico de attempts. Tarefa vencida pode ser reclamada; callback antigo
  não encerra nem falha a tentativa nova. Falhas retornam pending com backoff
  exponencial limitado a uma hora e chegam a dead a partir da oitava tentativa.
- Envio ocorre depois do COMMIT da aquisição. O processador rejeita transação
  ambiente; SMTP e o adapter de repasse não mantêm locks de banco durante rede.
  E-mails são decifrados somente para o provider, conservando o payload cifrado.
  Repasse recebe referência estável e permanece pending até webhook assinado;
  terminar o job de submissão não libera nem devolve dinheiro.
- DevelopmentEmailProvider escreve JSON privado por ID da tarefa, reaproveitado
  no reenvio. Diretório público e alias/junction para ele são rejeitados antes de
  gravar o link. Arquivos não seguem symlink; permissões POSIX são 0700/0600.
- SMTP preserva assunto, texto UTF-8 e Message-ID baseado no job. Timeouts de
  conexão/leitura/escrita são explícitos; TLS verifica a identidade do servidor
  conforme a configuração do [Angus Mail](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).
  O teste usa somente servidor SMTP em loopback e destinatários sintéticos;
  não comprova entrega real, TLS de um servidor comercial nem deduplicação pelo SMTP.
- Listagem e fingerprints de retry foram comparados com o TypeScript nos dois
  sentidos, inclusive UUID maiúsculo e inteiro escrito como float. O worker
  original também processou o mesmo payload, falha e lease como referência real.
  Sua importação fica restrita a essas chamadas no helper de teste; não há Node
  no runtime Java. A suite final repetiu as comparações financeiras anteriores.

O PostgreSQL local estava parado antes dos testes desta etapa. O processo foi
verificado, reativado e sua recuperação terminou antes da execução integral.
Reset/fixtures continuaram restritos a unicarona_java_test; não houve reset no
banco principal ou envio a pessoas. Comando da prova:
./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.

Esta etapa migra o processador processOne e as duas rotas administrativas.
Ele é invocado explicitamente nos testes. O loop contínuo do worker, a manutenção
periódica (expiração, confirmação final e carência mensal), shutdown e seeds
Java ainda precisam ser implementados e verificados. Não contar essa etapa
como migração completa do worker, E2E ou Docker.

### Contratos mensais, lista de espera e consulta de reservas

Em 03/10/2026 às 19:18:28 BRT, verify integral passou com 202 testes:
34 unitários e 168 integrações reais, sem failures/errors/skips. São 195 fontes
Java de produção e 64 das 93 operações, conferidas no registry Spring e no
inventário de métodos/rotas. A etapa acrescentou 24 testes ContractHttpIT:
17 para contratos/espera e sete para leitura de reservas. Os testes anteriores
continuam incluídos na prova integral.

- Sete operações de contratos/espera preservam DTOs, datas, contagem de faltas,
  agenda retida, paginação, participantes, conta restrita, Origin/CSRF,
  justificativa e idempotência. Cancelar renovação libera somente as retenções
  não pagas; reservas confirmadas, parcelas e ledger do período pago permanecem.
- Encerrar o vínculo exige motorista proprietário aprovado. As parcelas de
  reservas confirmadas em trechos planejados realmente futuros, segundo o fuso
  do campus, viram crédito integral, sem comissão. Trechos passados, iniciados,
  embarcados ou em revisão mantêm evidências e valores. Parcela já bloqueada
  continua em análise, mesmo se sua reserva futura elegível for cancelada.
  O interesse do passageiro recebe prioridade sem duplicar uma espera existente.
- A operação interna closeFuture também suporta interrupção para análise:
  transfere valores elegíveis de future para blocked e retorna seus IDs para
  vinculação posterior a ocorrências. O aviso essencial independe de notices.
  Usuários, contrato, viagens, reservas, parcelas e contas são bloqueados na
  ordem definida pelo backend original, dentro da mesma transação.
- Foram testados remoção concorrente com chaves iguais/diferentes, cancelamento
  de renovação simultâneo à remoção, ausência de trechos futuros elegíveis e
  falha de banco posterior ao crédito. Esta última reverte saldo, journal,
  reservas, contrato, aviso, audit e chave; repetir a mesma chave pode concluir.
  Nenhum trigger original foi desativado; a falha adicional existe só no banco
  dedicado de testes e é removida no finally.
- Espera exige participantes ativos com e-mail confirmado, mesma turma/curso/
  universidade e ausência de bloqueio bilateral. Como no TypeScript, não exige
  rotina ativa nem aprovação vigente do motorista. Não cria reserva, contrato,
  agenda retida ou dinheiro. UPSERT mantém um único interesse waiting, inclusive
  sob concorrência. Saída é própria, repetível e não exige idempotency-key;
  reentrada após sair cria um novo interesse.
- As três consultas de reservas preservam filtros de viagem/contrato, ordenação
  de próximos trechos/histórico, offset arbitrário, veículo, campos nulos e
  histórico de conta restrita. Projeções SQL unidas evitam entidades JPA na API,
  relações lazy e consultas individuais por item. Apenas o passageiro recebe
  PIN em reserva confirmed/boarded e viagem planned/active. Foto aparece somente
  no detalhe e exige arquivo photo próprio, vigente, estado elegível e ausência
  de bloqueio bilateral. Contato aplica os mesmos participantes e bloqueios.
- Listas/detalhes/contato e replays de mutações foram comparados com o backend
  TypeScript real, nos dois sentidos para cancelamento, espera e remoção.
  UUIDs maiúsculos preservam fingerprints de entrada e IDs de resposta das
  mutações. Correção explícita de bug: detalhe de reserva agora compara UUID
  canônico; no TypeScript, SQL encontrava o UUID maiúsculo mas a comparação de
  strings posterior devolvia 404 ao proprietário. O teste confirma essa diferença
  intencional e mantém isolamento de participantes.
- Datas e graceUntil foram comparados sob UTC, Tokyo e São Paulo. O teste de
  encerramento coloca trechos antes/depois de now no campus Pacific/Kiritimati
  e comprova que só o futuro elegível é creditado. OpenAPI documenta as dez
  operações acrescentadas, filtros, DTOs e headers obrigatórios.

Prova: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
Todos os resets/fixtures usam somente unicarona_java_test em loopback. Dados
reais, frontend e backend TypeScript foram preservados. O provedor comercial
de pagamentos ainda não foi escolhido; os adapters continuam desacoplados em
ambiente de testes, sem cobrança real ou envio a pessoas.

### Execução de viagens e reservas

Em 03/10/2026 às 19:46:05 BRT, verify integral passou com 224 testes:
34 unitários e 190 integrações reais, sem failures/errors/skips. São 205 fontes
Java de produção e 76 das 93 operações, conferidas no registry Spring.
A etapa acrescentou dez fontes de produção e 22 testes LifecycleHttpIT.

- As 12 operações preservam validação estrita, autorização dos participantes,
  estado, datas de campus, PIN, audit, avisos essenciais e idempotência. Início
  e embarque exigem o dia do campus. PIN só registra embarque; conclusão abre
  a janela de 24 horas e confirmação final libera comissão/repasse uma vez.
- Ausência mensal mantém a vaga e a parcela; a terceira falta cria revisão
  sem punição ou movimentação automática. Trecho sem comprovação de embarque
  e cancelamento avulso pelo passageiro bloqueiam valores para análise.
  Contestação reutiliza caso pendente; relato histórico não muda saldo liquidado.
- Sem aula exige viagem planejada sem avulsa confirmada. A confirmação mensal
  retém integralmente a parcela, sem comissão. Cancelamento pelo motorista
  credita parcelas future/awaiting elegíveis, conserva blocked em análise e
  encerra pedidos/cobranças não pagos e retenções de agenda do trecho.
- Proposta coletiva só muda o horário após o consenso dos participantes ainda
  elegíveis. Recusa avulsa credita integralmente e cancela a reserva; recusa
  mensal conserva o vínculo e o horário. Retirada da proposta marca respostas
  withdrawn. Sem participantes, a alteração é imediata.
- Conta restrita e motorista sem aprovação vigente ainda podem concluir ou
  cancelar compromissos existentes; passageiro restrito pode confirmar final
  ou contestar. Novas combinações, início e embarque mantêm suas exigências.
  Limite persistente de embarque é dez tentativas/15 minutos; fingerprint inclui
  HMAC do PIN, e snapshots/toString não expõem PIN nem hash em diagnósticos.
- Comparações e replays nos dois sentidos com o TypeScript cobrem todas as
  12 operações, inclusive UUID maiúsculo. Correção explícita: mutação de reserva
  normaliza UUID antes de procurar no snapshot; o original devolvia 500 após
  encontrar a reserva pelo SQL. ID bruto continua na resposta e no fingerprint.
- Locks seguem usuários, viagem, reservas e parcelas ordenadas. Confirmação de
  pagamento de teste de um novo participante durante a espera força retry da
  transação inteira; teste comprova novo timestamp de transação, audit único
  e preservação financeira do passageiro recém-confirmado. Corridas de embarque,
  final/contestação e replays não duplicam journal. Falha adicional de ocorrência
  reverte parcela, saldo, estado, audit e chave na mesma transação.
- Fuso Pacific/Kiritimati no campus foi testado sob JVM UTC, Tokyo e São Paulo.
  OpenAPI documenta bodies, respostas, segurança e headers das 12 operações.

Prova: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
Os resets/fixtures continuam restritos a unicarona_java_test em loopback.
Frontend, dados reais e TypeScript foram preservados; pagamentos continuam
desacoplados em desenvolvimento, sem provedor comercial escolhido.

### Segurança, bloqueios e decisões financeiras relacionadas

Em 03/10/2026 às 20:17:52 BRT, verify integral passou com 250 testes:
34 unitários e 216 integrações reais, sem failures/errors/skips. São 213 fontes
Java de produção e 84 das 93 operações, conferidas no registry Spring e no
inventário de métodos/rotas. A etapa acrescentou oito fontes de produção e
26 testes SafetyHttpIT; a regressão anterior continua incluída.

- As oito operações preservam bodies estritos, campos opcionais/nulos, paginação,
  UUID bruto nos fingerprints/respostas, códigos, propriedade, role administrativo,
  Origin/CSRF e idempotência. Remover bloqueio é próprio, repetível e não exige
  chave. OpenAPI documenta DTOs, parâmetros de paginação e headers obrigatórios.
- Relato exige outro aluno do mesmo campus com viagem planejada visível ou
  histórico de pedidos entre os participantes. Reserva informada deve pertencer
  exatamente à dupla. Administrador não relata como aluno. Conta restrita pode
  relatar, acompanhar e remover bloqueio; decisão administrativa mantém requisito
  de conta ativa. Relato histórico não exige elegibilidade para uma nova combinação.
- Relato sem gravidade/bloqueio conserva reserva e dinheiro. Relato grave bloqueia
  a parcela future/awaiting selecionada, vincula blocked existente sem duplicar
  journal e preserva reserva já cancelada. Valor liquidado não é reaberto nem
  vinculado ao novo caso. Gravidade também cria bloqueio pessoal, mesmo se block
  for false, como no backend original.
- Bloqueio interrompe contratos e cancela somente reservas confirmadas em viagens
  planejadas realmente futuras. Novos bloqueios financeiros são ligados ao caso;
  trechos iniciados, embarcados, já em revisão ou liquidados preservam evidências
  e valores. Holds são liberados e pedidos pending/accepted da dupla são cancelados,
  com cobranças awaiting/pending expiradas. Avulsa paga sem relato grave não é
  cancelada automaticamente. Contato e novas combinações respeitam ambos os lados.
- Remover o bloqueio não reativa contratos, reservas, pedidos ou valores. Listagem
  própria conserva privacidade; fila administrativa inclui pending/financial_pending
  com participantes. Detalhe pagina valores congelados e carrega casos relacionados
  em uma consulta adicional por página, sem relações lazy ou queries por item.
  Campos nulos, participantes ausentes, datas e timestamps permanecem compatíveis.
- Revisão resolve o caso com justificativa, mas conserva cada parcela blocked
  enquanto existir outro caso pendente ligado a ela. pendingOtherCases conta as
  parcelas ainda aguardando. A última decisão coerente liquida uma única vez e
  preserva cancelamento anterior da reserva/viagem.
- Resultado divergente exige exatamente os IDs resolvidos com decisões diferentes
  para parcelas ainda bloqueadas. Ausência ou IDs incorretos devolvem
  FINANCIAL_DECISION_CONFLICT, incluindo conflictingDecisionIds, sem consumir a
  chave ou mudar saldo. Duplicatas são normalizadas somente na comparação/audit;
  fingerprint conserva o body original validado. Retenção exige Sem aula em todas
  as parcelas afetadas; caso misto é rejeitado. Sem parcelas exige no_financial_change.
- Relatos concorrentes com a mesma chave criam um caso/hold. Revisões coerentes
  de casos distintos liquidam uma vez; revisões opostas mantêm saldo bloqueado
  até reconhecimento explícito. Duas chaves para o mesmo caso confirmam uma
  decisão. Corrida entre relato grave e final nunca libera valor já bloqueado.
- Falha adicional de audit após decisão financeira reverte ocorrência, reserva,
  journal, saldo, aviso e chave. A mesma chave conclui depois de retirar a falha.
  Triggers/constraints originais permanecem ativos; o trigger sintético é exclusivo
  do banco de testes e removido no finally.
- Relatos, bloqueios e revisões têm comparação/replays Java→TypeScript e
  TypeScript→Java, inclusive UUID maiúsculo. As quatro leituras e o envelope de
  conflito financeiro foram comparados com o servidor original. Datas e casos
  relacionados foram conferidos sob JVM UTC, Tokyo e São Paulo.

Prova: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
Os resets/fixtures usam somente unicarona_java_test em loopback. Frontend,
TypeScript e dados reais foram preservados. Provedor de pagamento comercial
continua não escolhido; adapters desacoplados e valores sintéticos de teste.

Naquela etapa restavam nove operações: oito administrativas e PATCH do perfil.
A etapa seguinte implementa essas rotas; os demais critérios continuam pendentes.

### Perfil e fechamento das 93 operações HTTP

Em 03/10/2026 às 21:02:41 BRT, verify integral passou com 299 testes:
34 unitários e 265 integrações reais, sem failures/errors/skips. São 230 fontes
Java de produção. O teste do registry compara exatamente os conjuntos de método
e URL com api-baseline.json, além da contagem: as 93 operações originais existem
no Spring. Os 49 novos testes são ProfileHttpIT (14), AdminAccountHttpIT (14)
e AdminCatalogHttpIT (21); toda a regressão anterior também passou.

- PATCH do perfil valida corpo estrito, normaliza contato e preserva a diferença
  entre omissão e null nos campos acadêmicos opcionais do fingerprint. Omitir
  telefone limpa o contato, como no original. Preferências e verificação são
  preservadas em mudanças de nome, telefone ou região. Mudança de turno reinicia
  a verificação acadêmica, sem encerrar os mensais.
- Troca de curso/período/turma exige reconhecer o impacto quando há mensais
  vigentes. Sem confirmação, perfil, contratos, dinheiro e chave permanecem
  intactos. Com confirmação, apenas trechos futuros elegíveis são encerrados e
  creditados integralmente, sem comissão; rotinas incompatíveis são pausadas.
  Trechos iniciados, embarcados e já bloqueados conservam evidências e valores.
  Falha tardia de audit reverte todas as alterações e permite repetir a chave.
- O perfil relê participantes após os locks e repete a transação inteira se
  surgir um novo contrato. O teste controlado confirma evidência paga pela
  primitiva financeira enquanto o PATCH aguarda, sob locks de participantes
  que permitem verificações de FK; ambos os contratos e passageiros são incluídos
  no encerramento posterior. A chave vencedora pertence à transação nova.
- Diretório administrativo é privado, paginado e restrito a contas de alunos
  não excluídas. Busca trata acentos; status e parâmetros são estritos. Suspensão
  e restauração exigem situação anterior e justificativa. Chaves repetidas
  reproduzem a resposta; decisões concorrentes confirmam apenas uma versão.
- Suspensão encerra mensais futuros para análise, bloqueia avulsas não iniciadas,
  cancela ofertas planejadas, pausa rotinas e expira pedidos/cobranças não pagos,
  notificando participantes. Apenas novos valores afetados são ligados a um caso
  financial_pending. Dinheiro liquidado, parcelas já bloqueadas e trechos
  embarcados são preservados. O motorista restrito ainda pode concluir a viagem
  já ativa; restauração não reativa contratos/ofertas nem liquida parcelas.
  Uma revisão financeira posterior pode devolver integralmente os trechos.
- Administração de contas também relê participantes após os locks. O teste
  cria um pedido com outro passageiro durante a espera e confirma retry da
  transação, expiração do pedido/cobrança e avisos sem duplicação. Falha de audit
  reverte conta, contratos, ofertas, parcelas, caso, avisos e chave.
- Preços são versões imutáveis. Campus é bloqueado antes de conferir a versão
  anterior; dois administradores com versões iguais produzem uma alteração e
  um conflito. Desconto vigente é preservado; o cliente não o altera. CreatedAt
  usa clock_timestamp; ofertas e parcelas pagas conservam seus valores. Novas
  ofertas usam os novos parâmetros. Números JSON seguem a grafia do original.
- Calendário tem escopo discriminado e estrito por faculdade ou curso/período/
  turma. Data segue o fuso do campus, independentemente da JVM. Exclusões ativas
  da mesma data/escopo são únicas; desativação mantém autor/histórico e permite
  novo evento. Cotação mensal considera exclusões; avulsas permanecem visíveis.
  Mudança após pagamento conserva cada ID, centavo, comissão e estado contratado.
- Adição ou desativação entre checkout e recibo assinado revalida a agenda:
  uma agenda indisponível ou incompleta não é confirmada. O dinheiro externo
  recebido volta integralmente como crédito, sem consumir crédito antecipado.
  As duas corridas foram exercitadas pelas novas rotas HTTP, com conciliação zero.
- Métricas contam respostas concluídas e erros 5xx no processo atual; tempo usa
  relógio monotônico. O pool expõe apenas total/idle/waiting. HTTP real confere
  acesso privado, incremento após erro controlado e ausência de dados de alunos.
  O contrato foi comparado com o original; valores absolutos são específicos
  de cada runtime e não são tratados como resultados iguais entre servidores.
- Perfil, preços, calendário e contas têm replays cruzados com o TypeScript;
  listas e histórico também foram comparados. OpenAPI documenta DTOs, union de
  escopos, filtros, controle de versão, segurança, headers e respostas de erro.
  Comparação canônica de UUID corrige três bugs claros do original: falso impacto
  acadêmico/perda de verificação, falso conflito de versão de preço e erro 500
  no alvo de revisão informado em maiúsculas. Resposta/fingerprint conservam o
  identificador bruto onde o contrato exige; replays com o original foram provados.

Prova: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
Relatórios raiz: 12 suítes unitárias e 18 de integração. Banco exclusivo
unicarona_java_test em loopback, PostgreSQL 18.6; Java 21 e Spring Boot reais.
Nenhuma migration, interface do frontend ou arquivo TypeScript foi modificado
nesta etapa. Nenhum pagamento ou envio de e-mail real foi realizado.

Restam manutenção periódica, loop/shutdown do worker, seeds Java, auditoria da
comparação integral das 93 operações, navegador/E2E integral, Docker e auditoria
final. O provedor comercial permanece não escolhido. Não remover ou mover o
TypeScript; implementação de todas as rotas não conclui a migração.

Referências de ecossistema verificadas:
[Spring Boot](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[springdoc](https://springdoc.org/),
[Flyway baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline),
[Adoptium archives](https://adoptium.net/installation/archives).
Imagens: [webp-imageio](https://github.com/usefulness/webp-imageio),
[TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys),
[metadata-extractor](https://github.com/drewnoakes/metadata-extractor).
Datas locais: [Hibernate JDBC Java Time](https://docs.jboss.org/hibernate/orm/6.6/javadocs/org/hibernate/cfg/MappingSettings.html).

### Manutenção periódica e processo worker Java

Em 04/10/2026 às 13:24:36 BRT, verify integral passou com 332 testes:
46 unitários e 286 integrações reais, sem failures/errors/skips. São 235 fontes
Java de produção. Os 33 testes novos são MaintenanceServiceTest (4),
WorkerLifecycleTest (8), MaintenanceHttpIT (18) e WorkerModeIT (3). A validação
direcionada também passou, às 13:10:54 BRT; toda a regressão anterior foi
executada novamente, incluindo o registry exato das 93 operações HTTP.

- Pedidos são rechecados sob locks dos participantes e do pedido. Vencimento
  do pedido ou de uma cobrança awaiting/pending expira as cobranças não pagas,
  cria aviso essencial e registra audit sem ator. Contas restritas e preferência
  de avisos desativada não impedem o encerramento. Cobrança aprovada e candidato
  cancelado são preservados. O lote limita cada categoria independentemente.
- Confirmação final vencida usa os mesmos locks de usuários, viagem, reservas e
  parcelas das operações HTTP. Só liquida a parcela awaiting: Sem aula retém o
  valor inteiro, sem comissão; demais trechos elegíveis liberam com a comissão
  congelada. Contestação permanece bloqueada. Dois workers concorrentes liquidam
  e auditam uma única vez, mantendo conciliação zero.
- Cada item usa uma transação independente. Uma falha tardia não desfaz um item
  anterior já confirmado e permite repetir o item que falhou. Serialization e
  deadlock repetem a transação inteira; a contagem só avança após o retorno do
  commit. A orquestração rejeita transação externa e limpa limites vencidos ao
  fim da rodada, preservando os ainda vigentes.
- Carência segue o fim do período e a meia-noite do campus mais 72 horas.
  Fuso da JVM não altera o prazo; Tokyo foi exercitado com JVM em Los Angeles.
  Preview materializa a agenda, bloqueia as viagens e relê a capacidade atual.
  Um único trecho ocupado ou com proposta de horário impede reter qualquer vaga.
  Reservas pagas de outra pessoa nunca são deslocadas para acomodar a carência.
- A preparação da renovação possui savepoint JDBC. Erro esperado de negócio
  desfaz as viagens especulativas e confirma renew_conflict com prazo e aviso.
  O teste remove apenas viagens sintéticas sem vínculos no banco isolado e deixa
  o campus sem preço vigente, provocando erro após a materialização real.
  Uma falha de banco na materialização reverte a transação completa: contrato
  continua active, sem prazo, agenda, avisos ou audit novos, e pode ser repetido.
  O caminho específico de renovação não marca rollback-only por ApiException;
  a cotação normal conserva seu comportamento transacional anterior.
- Holds não criam reservas, PINs ou movimentação financeira. Renovação manual
  via pedido, aceite e checkout Java pago com crédito sintético transforma a
  nova agenda em reservas e libera os holds do contrato anterior. Vencimento da
  carência ou ausência de renovação libera apenas vagas não pagas; parcelas,
  saldos e histórico já pagos são preservados.
- O teste de referência executa maintenance do TypeScript em banco Java isolado,
  comparando contagens e efeitos de expiração, confirmação final e carência com
  as respectivas execuções Java. A ponte de teste recebeu a ação maintenance;
  nenhuma implementação TypeScript de produção foi alterada ou usada pelo jar.
- O mesmo jar inicia como api (padrão) ou worker por UNICARONA_MODE. A escolha
  ocorre depois do ConfigData e funciona também por arquivo importado. Worker
  não inicia servidor HTTP; a configuração web de segurança só existe na API.
  API não consome a fila automaticamente. Intervalos são configuráveis e
  limitados; os padrões conservam manutenção imediata/a cada 15 s e espera de
  1 s para fila vazia ou falha. O relógio dos intervalos é monotônico.
- SmartLifecycle sinaliza parada, desperta a espera ociosa, observa a parada
  entre itens da manutenção e aguarda a tarefa atual antes de sinalizar conclusão.
  O teste real fecha o contexto durante uma entrega sintética bloqueada:
  datasource continua aberto, tarefa seguinte permanece pending, entrega ativa
  termina/commit e só então o pool fecha. Logs próprios de erro incluem somente
  código seguro; mensagens privadas e cadeias cíclicas são exercitadas na unidade.
  Lease, fencing, retries e recuperação após interrupção abrupta seguem o outbox
  migrado anteriormente.
- A regressão também revelou uma preparação antiga dependente do dia corrente:
  ContractHttpIT movia uma viagem paga para uma data já materializada da mesma
  rotina, violando a unicidade original. O cenário agora amostra o relógio uma
  vez, prefere o trecho pago da data alvo e remove somente materializações
  sintéticas sem reservas, linhas de pedido ou agenda vinculada. Nenhuma constraint
  ou regra de produção foi alterada. Os 24 testes de contratos passaram após a
  correção, preservando contagem paga, saldos e assertions entre fusos da JVM.

Prova integral: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
Relatórios raiz: 14 suítes unitárias e 20 de integração, todos atualizados pela
execução integral desta etapa. Prova direcionada adicional:
./backend-java/scripts/maven-local.ps1 -LocalDatabase
-Dtest=WorkerLifecycleTest,MaintenanceServiceTest
-Dit.test=MaintenanceHttpIT,WorkerModeIT verify.
PostgreSQL 18.6 em unicarona_java_test de loopback; fixtures e entrega de e-mail
são sintéticas. Migrations, frontend e backend TypeScript original preservados.
Nenhum provedor comercial foi escolhido e nenhum dinheiro real foi movimentado.

Restam seeds Java, auditoria da comparação integral das 93 operações,
frontend/navegador E2E integral, infraestrutura/testes Docker e auditoria final.
O backend antigo permanece disponível até todos esses critérios serem provados.

### Catálogo e cenários sintéticos em Java

Em 04/10/2026 às 13:52:54 BRT, verify integral passou com 354 testes:
54 unitários e 300 de integração, sem failures/errors/skips. São 241 fontes
Java de produção; todas as 93 operações HTTP continuam no registry esperado.
Os 22 testes novos são SeedCatalogTest (5), SeedSettingsTest (3) e SeedIT (14).
A execução direcionada dos seeds também passou, às 13:45:14 BRT.

- O catálogo usa os mesmos dados literais do frontend: 21 cursos e 68 regiões,
  incluindo as regiões administrativas do DF e as cidades atendidas. Java lê
  arrays como JSON, valida campos e não executa o JavaScript. Comparação de
  teste com a avaliação original confirma IDs, nomes e estados. O jar inclui
  o catálogo e funciona sem o diretório externo do frontend.
- O seed cria as quatro contas sintéticas anteriores, perfis acadêmicos, preço
  inicial e dois motoristas aprovados com veículos e documentos fictícios.
  Argon2id e login HTTP das quatro contas foram exercitados. Repetição conserva
  IDs, senhas, restrições, perfis, preferências, decisões, preços e arquivos.
- A base usa uma transação com lock consultivo e retry inteiro. Duas execuções
  concorrentes criam apenas um conjunto de contas, documentos e preço inicial.
  Falha real de banco após gravação dos arquivos desfaz linhas e remove somente
  arquivos sintéticos criados pela transação que sofreu rollback.
- Cenários usam os serviços Java de viagem, rotina, pedidos, aceite, checkout e
  webhook autenticado. Produzem pedido avulso pendente, reserva avulsa paga e
  contrato mensal pago. Não criam funding artificial no ledger. Recibos têm
  IDs estáveis; repetição mantém contratos, reservas, parcelas e journal.
- Replays com o seed TypeScript foram executados nos dois sentidos. Estado
  financeiro e documentos anteriores foram preservados; conciliação permanece
  zero. O original é apenas referência de teste, sem chamada pelo jar Java.
- UNICARONA_MODE aceita seed e seed-scenarios. O main inicia contexto sem HTTP,
  executa o comando e fecha o processo. Dois testes lançam o jar real e verificam
  saída bem-sucedida, banco e conciliação. Não há dependência de runtime Node.
  SEED_PASSWORD vem do ambiente privado, sem padrão, com mínimo de 16 caracteres.
  Production é proibido; cenários exigem gateway development antes de semear.

Prova integral: ./backend-java/scripts/maven-local.ps1 -LocalDatabase verify.
Relatórios raiz: 16 suítes unitárias e 21 de integração, todos atualizados pela
execução integral. Banco dedicado unicarona_java_test em loopback, PostgreSQL
18.6. Nenhuma migration ou implementação TypeScript de produção foi alterada.
Não houve envio real de e-mail nem movimentação de dinheiro real.

Restam auditoria da comparação integral, frontend/navegador E2E integral,
infraestrutura/testes Docker e auditoria final. As dez jornadas do navegador
estão sendo adaptadas para usar serviços Java por controle no classpath de
testes, sem rotas HTTP de controle nem imports do backend TypeScript original.
Preparação de testes não equivale à prova de execução no navegador.

### Dez jornadas do frontend conectadas ao Java

Em 04/10/2026, aproximadamente às 13:59 BRT, as dez jornadas passaram no Chrome
com PostgreSQL 18.6 e Spring Boot: 10 pass, 0 fail, 0 cancelled, 0 skipped, 0 todo.
A execução completa durou 234939 ms. A jornada auth também passou separadamente.
Typecheck dos onze arquivos TypeScript do runner/jornadas passou sem emitir JS.

O frontend original não foi alterado. O suporte inicia API Spring em loopback,
aplica Flyway no banco dedicado, chama SeedService e fecha contexto/pool ao
concluir. As dez jornadas mantêm as assertions anteriores e usam serviços Java
reais para manutenção, outbox, conciliação, notificações, decriptação e assinatura.
Não importam backend/src. Node executa Playwright e SQL de fixtures/assertions.
As falhas sintéticas de providers ficam no classpath de testes e são controladas
por stdin; não há rotas HTTP de controle nem suporte de teste no jar publicado.

Foram exercitados cadastro/confirmação/login/reset, navegação com respostas
atrasadas, perfis e documentos privados, aprovação administrativa de motorista,
preços/calendário versionados, retry de e-mail, avulsa paga com embarque por PIN,
conclusão/liberação, mensal pago e revisão conjunta de ocorrências divergentes.
Carência sem pagamento não permite embarque nem movimenta dinheiro. Renovação
exige aceite/pagamento; conflito e vencimento preservam os trechos já pagos.
Ausência, Sem aula, crédito, saque e devolução seguem os serviços Java; os
webhooks são autenticados e idempotentes, e a conciliação final permanece zero.

Falha de provider preserva a cobrança; resposta perdida preserva a chave de
idempotência. Offline, loading, estados vazios, paginação além da primeira página,
histórico/reload, validação acadêmica e escolhas de turma foram exercitados.
Layout de 320 a 1440 pixels conserva foco/teclado e não apresenta overflow,
inclusive com strings longas. Todas as jornadas exigem ausência de pageerror.
Foram produzidos 45 PNGs em docs/previews/java-connected, preservando os previews
anteriores. Nenhum diretório de execução permaneceu em .local/java-e2e-runs.

Comando: ./backend-java/scripts/e2e-local.ps1 -LocalDatabase.
As fixtures usam somente unicarona_java_test em loopback. Arquivos/mailbox são
privados e exclusivos por execução. Sem -LocalDatabase, o suporte está preparado
para Testcontainers; a execução dessa modalidade ainda depende de Docker.
Não houve uso de dados reais, envio real de e-mail ou movimentação de dinheiro
real. O provedor comercial continua não escolhido.

Restam auditoria da comparação integral das 93 operações, infraestrutura/testes
Docker e auditoria final. O TypeScript original permanece até todos os critérios
da Definition of Done serem comprovados.

Após incluir o suporte E2E no classpath de testes, a regressão Maven foi repetida
integralmente e passou em 04/10/2026 às 14:07:36 BRT: 54 unitários e 300 integrações,
sem falhas, erros ou testes ignorados. Essa execução inclui os testes anteriores,
seeds e o registry exato das 93 operações, com as 41 fontes de teste atuais.

### Ativação do WSL 2 para a validação Docker — 04/10/2026

A ativação foi autorizada pelo usuário. `VirtualMachinePlatform` e
`Microsoft-Windows-Subsystem-Linux` estão habilitados. A inspeção elevada
confirmou `hypervisorlaunchtype Off`; a inicialização foi alterada para `Auto`.
A virtualização do processador está disponível, mas `HypervisorPresent` segue
falso nesta sessão. A importação do Ubuntu para o ambiente isolado
`UniCaronaDocker` ainda retorna `HCS_E_HYPERV_NOT_INSTALLED` antes do reinício.
Não foi executada reinicialização automática. Docker e Testcontainers continuam
pendentes de execução real; a análise do Compose não comprova esses requisitos.

### Auditoria HTTP e ordem de validação — 04/10/2026

A regressão integral passou às 15:05:43 BRT: 367 testes, sendo 64 unitários
e 303 de integração, zero failures/errors/skips. Os 39 relatórios XML foram
verificados separadamente: 17 suítes unitárias e 22 de integração, todos
atualizados nesta execução. São 242 fontes de produção e 43 fontes de teste.

`CompatibilityAuditIT` compara as 93 operações para requisições anônimas e as
38 consultas para passageiro e administrador, totalizando 169 pares. Compara
status/código e os payloads das consultas bem-sucedidas, exceto métricas do
processo e readiness. As mutações continuam nos replays de cada domínio.
Refresh/logout não reutilizam uma sessão consumida para uma segunda mutação.

Foi corrigida uma diferença real: Fastify valida o schema antes do preHandler
de autenticação; o filtro Java anteriormente autenticava antes da validação MVC.
O Java agora compila schemas locais antes de atender requisições e valida corpo,
parâmetros e query depois de rate limit/Origin, antes da sessão/role/CSRF.
Bean Validation e as rechecagens transacionais permanecem nos DTOs/serviços.
Há 93 contratos, 54 schemas de corpo e nenhuma referência remota. Uma inspeção
independente confirmou igualdade com o inventário exportado, incluindo parâmetros.

JSON fica limitado a 256 KiB e o corpo é preservado para o controller. Sintaxe
inválida, tamanho excedido e mídia não suportada recebem INVALID_REQUEST;
schema inválido recebe VALIDATION_ERROR. GET sem corpo e multipart preservam
seus caminhos de parsing. Campos adicionais e coerção continuam recusados.

A primeira execução detectou diferenças do validador de biblioteca e do formato
exportado pelo Swagger. Os padrões originais de email/UUID substituem verificações
extras de TLD; `.test` volta a ser aceito. `nullable` de enum recupera a união
enum-ou-null do Zod. Replays de perfil com turma omitida/nula passaram novamente.
Dez testes unitários específicos verificam esses limites e o cache do corpo.

Duas expectativas antigas dos testes Java foram corrigidas após comparação
direta com a implementação original: versão 1e30 é recusada com 422, antes do
conflito de versão; conta suspensa com formulário de viagem inválido recebe 422,
enquanto o formulário válido continua recebendo 403 ACCOUNT_RESTRICTED.
Não foi afrouxado o schema para manter essas expectativas anteriores.

O jar foi inspecionado: inclui os contratos, SeedCommand e frontend; exclui
controle E2E, referência TypeScript e `.env`. A matriz de requisitos e pendências
fica em `docs/java-migration/verification.md`. Docker/Testcontainers ainda exigem
reinício do Windows e execução real. O backend original permanece disponível.

A prova do frontend foi repetida depois da correção do filtro: aproximadamente
às 15:10 BRT, as dez jornadas passaram no Chrome, zero fail/cancelled/skipped/todo,
em 252791,9844 ms. Todas as 45 capturas foram atualizadas nesta execução.
Comando: ./backend-java/scripts/e2e-local.ps1 -LocalDatabase. O suporte de testes
continua usando os serviços Java reais, sem imports do backend TypeScript.

### Rotas desconhecidas e HEAD — 04/10/2026

A auditoria adicional encontrou duas diferenças de infraestrutura HTTP. Rota
inexistente sob `/api/v1/` retornava 401 antes da resolução; o original retornava
404. HEAD pulava a validação GET e algumas permissões HTTP, apesar do suporte
implícito dos controllers. A resolução agora rejeita operações desconhecidas
com NOT_FOUND após os checks globais de Origin/rate limit e usa o contrato GET
para HEAD. Spring Security mantém autenticação e role administrativa em HEAD.

Foram comparados 133 pares adicionais: 13 casos de rotas/métodos inexistentes
e Origin ausente, 114 consultas HEAD (38 para cada perfil), três queries duplicadas
e três acessos HEAD ao documento privado sintético (proprietário, terceiro e
anônimo). As respostas HEAD têm corpo vazio. O suporte TypeScript de teste
agora aceita essa resposta vazia sem tentar interpretá-la como JSON.
Somados aos 169 pares anteriores, são 302 pares de requisições comparadas.

Prova direcionada: 12 unitários de contrato e cinco testes de auditoria HTTP
passaram às 15:15:47 BRT. Após incluir o arquivo privado real, verify integral
passou às 15:23:58 BRT: 371 testes, 66 unitários e 305 integrações, zero
failures/errors/skips. Os 39 relatórios XML são desta execução. Controllers,
migrations, frontend e serviços de domínio não foram alterados nesta etapa.

A revisão dos repositórios principais de leitura encontrou projeções e joins
nas listagens, sem consultas adicionais no mapper por linha. Pedido detalhado
usa duas consultas; ocorrência detalhada usa até três, com casos relacionados
de toda a página em lote. A inspeção não alega benchmark sob carga.

Depois dessas mudanças, as dez jornadas do Chrome foram repetidas e passaram
aproximadamente às 15:29 BRT: 10 pass, zero fail/cancelled/skipped/todo,
243427,6902 ms. Todas as 45 capturas são desta execução. Comando:
./backend-java/scripts/e2e-local.ps1 -LocalDatabase. Nenhum diretório de execução
permaneceu em .local/java-e2e-runs. Docker/Testcontainers continuam dependentes
do reinício do Windows; HypervisorPresent segue falso nesta sessão.


### WSL 2 liberado após reinicialização — 04/10/2026

O Windows foi reiniciado às 18:32:30 BRT. A leitura atual de
`Win32_ComputerSystem.HypervisorPresent` retornou `true`. A distribuição
isolada `UniCaronaDocker` foi importada com sucesso como WSL 2, usando a imagem
Ubuntu 24.04.5 previamente conferida por SHA256. O systemd está ativo.
A instalação do Docker Engine pelo repositório oficial está em execução.
Os testes Docker/Testcontainers ainda não estão comprovados nesta etapa.

### Docker Engine e aplicação Linux — 04/10/2026, aproximadamente 18:51 BRT

Docker Engine 29.8.2 e Compose 5.6.0 foram instalados no Ubuntu WSL 2 pelo
repositório oficial. `hello-world` foi executado. O build multi-stage real
compilou as 242 classes Java e empacotou o frontend sem Node na imagem final.
O `compose.java.yaml` iniciou banco PostgreSQL 18.6, seed, API e worker.
API e banco ficaram healthy; seed terminou com exit 0.

`docker-smoke.mjs` comprovou as 93 operações OpenAPI iguais ao inventário,
frontend estático, login, CSRF, arquivo privado, isolamento entre usuários e
conversão nativa Linux PNG -> WebP de 512 x 341. Após `compose down` sem remover
volumes e `up -d`, os arquivos mantiveram seus hashes e os logins funcionaram.
O banco manteve 13 migrations, quatro usuários sintéticos e seis arquivos.
O processo executa como uid/gid 10101; não existe executável Node nem árvore
TypeScript na imagem final. O worker não escuta HTTP e concluiu um job de
recuperação de senha com o adapter local, gravando um e-mail privado de teste.

A primeira tentativa Testcontainers foi interrompida pelo encerramento da
sessão WSL: serviços systemd sozinhos não mantiveram a distribuição ativa.
Um keeper local com `sleep infinity` corrigiu essa execução; o helper
`start-docker-local.ps1` mantém a sessão e configura DOCKER_HOST somente no
processo atual. O TCP do Docker está limitado ao loopback do WSL/Windows.
Essa tentativa falhou e não é contada como aprovação. A repetição integral
com Testcontainers está em andamento.

### Suíte integral com Testcontainers aprovada — 04/10/2026 às 18:58:42 BRT

`maven-local.ps1 verify`, sem `-LocalDatabase` e sem TEST_JAVA_DATABASE_URL,
terminou em BUILD SUCCESS (9m40s): 66 unitários e 305 integrações,
371 testes no total, zero falhas, erros ou skips. PostgreSQL 18.6 foi criado
por Testcontainers, com Ryuk ativo e portas efêmeras via Docker no WSL.
Inclui os 302 pares de compatibilidade, todos os 22 conjuntos de integração,
concorrência e execução real do jar nos seeds. O keeper manteve o Docker ativo.
O novo modo CLI `migrate` foi preparado após a compilação dessa execução;
sua validação e a execução final com 372 testes ainda estão pendentes.

### Conclusão da migração — 04/10/2026

A execução final pelo comando principal `npm test` terminou em BUILD SUCCESS
às 19:12:16 BRT (11m28s). Foram 372 testes: 66 unitários e 306 integrações
em PostgreSQL 18.6 via Testcontainers; zero falhas, erros e skips.
Os 39 XMLs foram conferidos separadamente e todos são da execução final.
SeedIT passou com 15 testes, incluindo o novo CLI migrate sem seed/HTTP.

As dez jornadas Chrome via Testcontainers passaram em 416063,2708 ms,
zero falhas/cancelamentos/skips/todo. Todas as 45 capturas pertencem à
execução final. O pool PostgreSQL Node agora fecha antes de o Java remover
seu contêiner de teste; isso corrige o erro de teardown que só aparecia
na modalidade Docker. Nenhuma regra da aplicação foi alterada por essa correção.

Dockerfile/Compose principais e scripts npm agora selecionam Java. O original
foi preservado para referência em backend/, com scripts legacy:*,
backend/legacy.Dockerfile, compose.typescript.yaml e README anterior.
As dependências Node foram movidas para devDependencies, com lock atualizado.
O jar e a imagem final não incluem TypeScript ou Node em execução.

`npm run build` passou às 19:13:11 BRT. `npm run db:migrate` aplicou as 13
migrations em um PostgreSQL descartável exclusivo e encerrou com exit 0,
sem servidor HTTP ou seed. O mesmo comando Java foi executado via Compose.
O contêiner temporário CLI foi parado e removido; o banco principal não foi
alterado por essa verificação. O Compose principal passou pelo helper
`npm run docker -- up`, com build real e serviços independentes.
`docker-smoke.mjs` e sua fase de persistência foram repetidos com sucesso.

O jar final contém 813 entradas, dashboard e contratos de validação,
sem .env, suporte E2E, referência TypeScript ou fontes .ts. A busca de
mocks/Testcontainers/ProcessBuilder/Node/TODO/FIXME em src/main e infraestrutura
principal não encontrou ocorrências. Permanecem 0 processos Java de teste
Windows e 0 diretórios E2E ativos. Os fixtures sintéticos da tentativa
interrompida foram arquivados em .local/java-e2e-aborted, fora do runtime.

A matriz final em docs/java-migration/verification.md registra todos os
critérios aprovados. Não há pendência de Docker, Testcontainers, frontend,
contratos ou troca dos comandos principais. O provedor comercial de pagamentos
continua não escolhido, como antes da migração; adapters locais não movimentam
dinheiro real. Nenhum benchmark Java de produção é alegado.
