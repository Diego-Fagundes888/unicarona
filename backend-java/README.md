# Backend Java do UniCarona

Para instalar um clone novo, siga o [README principal](../README.md) e a [demonstração local](../docs/EXECUCAO.md). As seções abaixo incluem registros operacionais históricos e ferramentas específicas da instalação privada original; esses dados e credenciais não acompanham o repositório público.

Backend único da aplicação, em Java. Os testes usam contratos HTTP fixos e
instâncias Java independentes. O Java tem health/readiness, catálogo, runtime,
frontend estático, OpenAPI, cálculo inteiro de preços, compatibilidade
criptográfica e as dez operações de autenticação, além da consulta/edição de perfil.
Inclui preferências, verificação acadêmica/revisão, notificações, documentos
privados, cadastro/revisão de motorista e veículo, carteira/conciliação e os
serviços internos de ledger e encerramento de parcelas. Inclui solicitar/listar
repasses e receber seus webhooks assinados, com adapters de desenvolvimento.
Inclui consulta/retomada administrativa de envios e processador de outbox,
com adapters de e-mail development/SMTP, manutenção periódica e worker contínuo
em processo independente, com encerramento pelo ciclo de vida do Spring.
Viagens têm publicação/edição, busca, detalhe e histórico próprios; preços
contratados e vagas confirmadas/retidas são preservados ao editar.
Rotinas têm publicação, materialização, busca, detalhe, listagem própria e
pausa/reabertura. A cotação mensal preserva calendário, turma e alocação de
centavos; a cotação sozinha não cria contrato nem confirma uma reserva.
Pedidos têm criação avulsa/mensal, aceite/recusa, cancelamento, lista e detalhe.
Avulsa automática cria cobrança awaiting; mensal exige aceite explícito da
agenda inteira. Preço/comissão ficam congelados nas linhas do pedido. Cobrança
pendente ainda não ocupa vaga nem movimenta dinheiro.
Checkout aceita PIX/cartão e crédito opcional. A confirmação autenticada cria
reservas, PINs e parcelas futuras; no mensal, cria também contrato e agenda.
Se a confirmação chega após perda de disponibilidade, o valor externo recebido
vira crédito do passageiro, sem ocupar a vaga. O gateway local é apenas de testes.
Contratos têm listagem/detalhe, cancelamento de renovação e encerramento pelo
motorista com crédito integral dos trechos futuros elegíveis. A lista de espera
preserva compatibilidade acadêmica e prioridade, sem reservar assentos.
Reservas têm consulta paginada, histórico, detalhe e contato autorizado. O PIN
aparece somente para o passageiro enquanto o trecho permite embarque; contato
e foto respeitam bloqueios bilaterais. A execução inclui início, embarque por PIN,
conclusão, confirmação/contestação final, ausências, cancelamentos, Sem aula e
acordo coletivo de horário. PIN não libera dinheiro; parcelas em análise não
recebem encerramento automático. Segurança inclui relatos, bloqueios pessoais,
consulta própria, fila/detalhe administrativos e análise de decisões relacionadas.
Bloqueio interrompe mensal futuro para análise; remover não reativa vínculos.
Parcelas só são liquidadas após todos os casos relacionados estarem resolvidos;
decisões divergentes exigem reconhecer os IDs anteriores e justificar.
Perfil permite editar contato, região e vínculo acadêmico. Mudança de turma
exige confirmação antes de encerrar mensais incompatíveis e creditar trechos
futuros; alteração de turno reinicia a verificação, preservando os contratos.
Administração inclui diretório privado, suspensão/restauração de contas,
preços versionados, calendário por faculdade/turma e métricas agregadas.
Suspensão preserva dinheiro liquidado e viagens embarcadas; valores futuros
ficam em análise financeira separada. Restauração não revive ofertas ou valores.
As 93 operações do inventário original permanecem implementadas. O perfil de
desenvolvimento atual tem 132 operações; o Mercado Pago tem 144. O inventário
conjunto tem 145, incluindo as operações condicionais de cada provedor.
Os comandos principais e a infraestrutura Docker executam Java.

Java 21, Spring Boot 3.5.16, Maven 3.9.11, Spring Data JPA/Hibernate, Spring
Security, PostgreSQL, Flyway 11.20.3, Bean Validation, JUnit 5, Mockito,
Testcontainers e springdoc. O frontend original é empacotado no jar a partir de
`../dist`; também pode vir de `FRONTEND_PATH`. Não é necessário Node para
executar o jar. O script de comparação criptográfica usa Node somente como
referência nos testes da migração. A comparação de autenticação também executa
o backend original como referência de teste, em um processo isolado; isso exige
as dependências Node/tsx do projeto apenas para essa comparação.

## Compilar e testar

Em `backend-java`, com JAVA_HOME apontando para Java 21:

```powershell
./mvnw.cmd verify
```

No Linux/macOS, usar `./mvnw verify`. O wrapper fixa Maven e SHA256 do archive.
`verify` executa unitários e integração em PostgreSQL Testcontainers; Docker
precisa estar disponível. Os testes não são ignorados quando falta Docker.

Alternativa local explícita, na raiz `D:\UniCarona`:

```powershell
./backend-java/scripts/maven-local.ps1 -LocalDatabase verify
node backend-java/scripts/check-crypto-compatibility.mjs
```

Esse script usa o Java/Maven portátil da pasta privada configurada ou, no modo
legado, de `.local/java-tools`. Para testes locais, lê somente a conexão necessária
e deriva `unicarona_java_test`; não carrega credenciais de SMTP/Mercado Pago/chaves
do runtime no processo de testes. O banco dedicado `unicarona_java_test`
deve existir, pertencer ao usuário de aplicação e estar em localhost. O reset
de schema só ocorre nesse banco verificado. Não utiliza `unicarona` nem
`unicarona_test`. `TEST_JAVA_DATABASE_URL` permite configurar essa conexão local
explicitamente; não se aceita outro nome de banco. Testes de integração são
sequenciais.

No ambiente Windows migrado, `.env` contém apenas um ponteiro sem credenciais.
As configurações efetivas e os dados ficam em `%LOCALAPPDATA%/UniCarona/Private`,
com permissões de NTFS verificadas. `backend-java/scripts/private-environment.ps1`
e `.mjs` resolvem essa configuração; manifesto incompleto/redirecionado falha
sem retornar ao antigo arquivo de segredos. Testes locais de restauração física
usam `TEST_PG_TOOLS_PATH` (configurado automaticamente pelo launcher local quando
o runtime privado está ativo); no Testcontainers usam as ferramentas do próprio
container. Falta de ferramenta não ignora o teste.

`docs/privacy/Armazenamento_Backup_Local.md` distingue o runtime de demonstração
preservado das alterações de privacidade ainda em implementação. Não publicar o
jar atual sem concluir a interface, os aceites e a validação coordenada.

A V33 implementa catálogo independente de cópias, expurgo limitado, anéis de
assinatura, fim controlado do diário e barreira de restauração antiga. O operador
pode usar o comando instalado de arquivos sem iniciar Spring/Flyway; o expurgo
do diário pertence ao worker V33 depois da publicação. Ver
`docs/privacy/Retencao_Backups_Diario.md`. Não reutilizar o mesmo armazenamento
de controle para outra base/ambiente nem restaurar uma cópia antiga desse controle
sobre o estado independente atual.

## Banco e compatibilidade

Migrations SQL são cópias byte a byte de 001–013, nomeadas V1–V13. Banco novo
é criado pelo Flyway. Um banco TypeScript existente só recebe baseline depois
de validar o SHA256 e a continuidade de todo seu histórico; versões desconhecidas
ou gaps impedem a transição. O executor usa o advisory lock antigo, conserva
`schema_migrations` e não recria tabelas de negócio. `clean` está desativado e
Hibernate valida as entidades mapeadas sem alterar o schema.

Ao migrar dados reais, preserve as chaves privadas e valide os backups.
Os testes exercitam um schema anterior e conservam seus dados,
constraints, índices e triggers.

## Configuração

`application.yml` aceita environment variables e um `.env` privado no diretório
de execução. `DATABASE_URL` precisa ser uma URL PostgreSQL com usuário/senha.
Não imprimir esse valor em comandos de diagnóstico. HOST, PORT,
DB_POOL_MAX, DB_STATEMENT_TIMEOUT_MS e FRONTEND_PATH são configuráveis.
STORAGE_PATH deve ficar fora do frontend público, inclusive ao resolver links
e junctions. STORAGE_USER_QUOTA_BYTES (padrão 50 MB) e STORAGE_USER_MAX_FILES
(padrão 100) incluem documentos apagados; duplicata ativa da mesma pessoa e
finalidade reutiliza o ID antes de aplicar quota. Fotos de até 2 MB são WebP
de até 512 × 512; documentos de até 5 MB conservam os bytes originais. Os tipos
permitidos são verificados pelo conteúdo. WebP usa o codec nativo distribuído
por webp-imageio, sem Node no runtime; a execução nativa foi verificada no
Windows e na imagem Docker/Linux, incluindo WebP nativo.
Autenticação requer TOKEN_PEPPER (mínimo 32 caracteres), PIN_ENCRYPTION_KEY
(64 caracteres hexadecimais) e JWT_SECRET (mínimo 32 bytes UTF-8), fornecidos
por configuração privada. Não há valores secretos padrão no código. APP_ORIGIN
é a origem exata permitida nas mutações; SESSION_ACCESS_MINUTES (1–60) e
SESSION_REFRESH_DAYS (1–90) mantêm os defaults 15/30 do servidor original.
NODE_ENV=production exige origem HTTPS e aplica Secure aos cookies. Os testes
usam fixtures públicas sintéticas, com precedência sobre a configuração local.
PAYMENT_WEBHOOK_SECRET também é obrigatório (mínimo 32 caracteres); reutilizar
o segredo privado existente na transição. PAYMENT_PROVIDER_TIMEOUT_MS aceita
100–20000 ms, padrão 10000. O adapter development não pode operar em produção.
REQUEST_EXPIRY_HOURS aceita 1–72 horas, padrão 24; CHECKOUT_EXPIRY_MINUTES
aceita 5–120 minutos, padrão 30. O prazo da cobrança nunca ultrapassa o pedido.
EMAIL_PROVIDER aceita development/resend/smtp. Resend usa EMAIL_PROVIDER_API_KEY
privada e EMAIL_FROM. Confirmação vale 60 minutos (EMAIL_VERIFICATION_MINUTES,
5–60); APP_ORIGIN define o link e exige HTTPS em produção. EMAIL_SUPPORT_URL e
EMAIL_PRIVACY_URL devem apontar para destinos reais em produção. SMTP usa
SMTP_HOST (obrigatório), SMTP_PORT (padrão 587), SMTP_SECURE, SMTP_USER e SMTP_PASS.
EMAIL_FROM aceita MAIL_FROM como alias legado. O transporte SMTP exige TLS fora
de loopback. Configuração, DNS, prova real e testes atualizados de e-mail:
[Verificacao_Email.md](../docs/Verificacao_Email.md).
Credenciais vêm da configuração privada. DEV_MAIL_PATH (padrão .local/mail)
precisa ficar fisicamente fora de FRONTEND_PATH; aliases para o frontend são
rejeitados antes de gravar links. O ambiente development escreve um JSON privado
por ID estável da tarefa, sem entregar e-mail pela rede.
Documentação completa e checklist de todos os módulos:
[Migracao_Java.md](../docs/Migracao_Java.md).

Os testes HTTP sobem/fecham seu próprio Spring Boot e exercitam autenticação,
cookies, erros e banco. A suíte final passou com 372 testes (66 unitários e
306 de integração), sem failures/errors/skips, em 04/10/2026 às 19:12:16 BRT.
Usou PostgreSQL 18.6 via Testcontainers/Docker e o comando principal npm test.
O registry Spring foi comparado por método e rota com o inventário original:
93 de 93 operações. Essa igualdade ainda não comprova todas as jornadas.
Na migração de 04/10 houve comparação com o TypeScript de autenticação/perfil, settings, revisão
acadêmica, motorista, documentos/foto de referência, carteira, conciliação e
fingerprints financeiros, transferências, viagens, rotinas, cotação mensal e
pedidos de reserva, checkout, cobranças, webhooks de pagamento, fila administrativa
e processador de envios, contratos/lista de espera, consultas de reservas e as
12 operações de execução, relatos, bloqueios e decisões administrativas de
ocorrências. Essas mutações tiveram replays nos dois sentidos. Leituras de segurança
e conflitos financeiros também foram comparados com o servidor original.
Edição do perfil, administração de contas, preços e calendário têm comparação
de respostas e replays cruzados. Métricas conservam o contrato agregado;
valores dos contadores e do pool pertencem ao processo consultado.
Manutenção também foi comparada com o original; testes concorrentes exercitam
confirmação final, retenção de agenda e preservação dos valores pagos. Worker
sem HTTP e encerramento durante entrega foram validados com contexto Spring e
PostgreSQL reais.
A consulta de uma reserva aceita UUID maiúsculo: corrige a comparação textual
posterior ao SQL que causava um 404 indevido no TypeScript para o proprietário.
As mutações de reserva também normalizam esse UUID: corrigem o 500 original,
preservando o identificador bruto na resposta e no fingerprint de idempotência.
Um teste controla pagamento de outro participante durante a espera por locks;
a operação repete a transação inteira para bloquear todos os usuários atuais.
Datas/horários de viagens
foram conferidos sob UTC, Tokyo, Los Angeles e São Paulo, sem deslocamento na
gravação ou edição. A entrega dos links
está enfileirada e cifrada no outbox compatível. O worker Java executa manutenção
e entregas continuamente; os testes usam PostgreSQL real e entrega sintética.
Nenhuma entrega real de e-mail foi alegada. As dez jornadas
completas do navegador passaram conectadas ao Java em 04/10/2026.

`CompatibilityAuditIT` compara status/código das 93 operações sem login e
status/payload das 38 consultas como passageiro e administrador: 169 pares.
Inclui mais 133 pares: rotas/métodos inexistentes, origem em escrita inválida,
HEAD das 38 consultas, query duplicada e documentos privados como proprietário,
outro passageiro e anônimo. Total: 302 pares. HEAD usa o contrato GET existente
e as mesmas regras de acesso; operações desconhecidas sob `/api/v1/` retornam
404 depois da proteção global de Origin/rate limit.
As mutações são comparadas nos replays de domínio, com idempotência e estados
persistidos. Validação de entrada usa os esquemas locais originais antes da
autenticação, preservando o limite JSON de 256 KiB, formatos, nullables e tipos.
Os DTOs continuam usando Bean Validation para validação semântica.

A migração original foi concluída em 04/10/2026; suas evidências históricas estão
em [verification.md](../docs/java-migration/verification.md). O backend TypeScript foi retirado posteriormente; as expectativas HTTP fixas,
os testes de domínio/banco e os replays entre instâncias Java substituem sua
execução. [Retirada e validação](../docs/Retirada_Backend_TypeScript.md). O adapter
Mercado Pago Orders está implementado para Pix/cartão e devoluções, com
ensaios locais sintéticos; a homologação oficial e a operação de repasse
continuam pendentes. O estado atual e os requisitos de lançamento estão em
[MercadoPago_Checklist_Lancamento.md](../docs/MercadoPago_Checklist_Lancamento.md).

JWT usa HS256 com issuer/audience, sujeito, sessão e família. A API também
consulta o registro vivo da sessão a cada requisição; logout, reset e reutilização
de refresh revogam acesso imediatamente. O refresh continua opaco, com rotação
atômica e revogação da família confirmada antes da resposta de erro. Tokens
opacos anteriores permanecem válidos até seu vencimento/revogação. PASSENGER,
DRIVER e ADMIN vêm dos vínculos atuais no banco; aprovação do motorista é
necessária para ROLE_DRIVER. Cookies continuam uc_access/uc_refresh HttpOnly
e uc_csrf legível, todos SameSite=Strict. Origem, CSRF, limites persistentes,
rechecagem da senha sob lock e ordem user→session foram preservados.

Carteira e conciliação calculam centavos inteiros a partir do journal imutável.
O ledger exige transação externa, rejeita desequilíbrio/saldo insuficiente e
preserva os hashes dos eventos existentes. A chave do evento e as contas são
bloqueadas antes de gravar; repetição não cria outra movimentação. Parcelas
futuras ou aguardando podem ser bloqueadas; parcela bloqueada exige análise
para encerrar. Liberação divide comissão/repasse; crédito e retenção não cobram
comissão. Transferências reservam apenas saldo disponível e só encerram mediante
webhook autenticado; replays e recibos de falha são persistidos. O envio ao
adapter usa ID estável e timeout fora da transação. O processador de outbox Java
consome e-mails/repasses em transações curtas, com entrega externa fora do banco.
A manutenção expira pedidos/cobranças não pagos, encerra confirmações vencidas e
calcula a carência mensal de 72 horas a partir da meia-noite no fuso do campus.
Uma agenda nova só retém vagas se todos os trechos forem elegíveis. Essas vagas
não são reservas pagas; a renovação continua exigindo aceite e pagamento.
Parcelas contestadas e histórico pago são preservados. Cada item tem sua própria
transação e rechecagem sob locks. Checkout e
webhooks de pagamento estão ligados às reservas e contratos. O adapter local
de pagamento produz apenas o link da página de testes, sem cobrar dinheiro.

## Executar API e worker

O mesmo jar usa a configuração privada da aplicação. Em dois processos na raiz:

```powershell
java -jar backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar --UNICARONA_MODE=api
java -jar backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar --UNICARONA_MODE=worker
```

O padrão é `api`. O modo `worker` é resolvido depois de ler a configuração,
inclusive `.env`, e não abre servidor HTTP. Executa manutenção imediatamente e
depois a cada `WORKER_MAINTENANCE_INTERVAL_MS` (padrão 15000), processando a fila
entre essas execuções. Fila vazia ou erro aguardam `WORKER_IDLE_MS` (padrão 1000).
Os intervalos aceitam de 1 a 86400000 ms. São até 100 candidatos de cada categoria
por rodada. Logs próprios de falha guardam somente um código seguro.

Ao encerrar, o worker sinaliza a parada, desperta a espera ociosa e termina o
item em andamento antes de liberar as dependências. A manutenção observa a parada
entre transações. O prazo do ciclo de encerramento do Spring vem de
`WORKER_SHUTDOWN_TIMEOUT_MS` (padrão 30000); o supervisor deve conceder tempo
compatível. As entregas mantêm lease e proteção por número de tentativa, permitindo
recuperação de tarefas após interrupção abrupta. API e worker usam o mesmo banco
e configuração de providers; o processo API não consome a fila automaticamente.

## Dados de demonstração em Java

Defina `SEED_PASSWORD` no ambiente privado, com pelo menos 16 caracteres.
Os comandos usam o banco configurado, encerram ao concluir e não abrem HTTP:

```powershell
java -jar backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar --UNICARONA_MODE=seed
java -jar backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar --UNICARONA_MODE=seed-scenarios
```

`seed` preenche o catálogo UNICEPLAC, quatro contas sintéticas, perfis acadêmicos,
dois motoristas aprovados e documentos fictícios. O catálogo vem dos dados literais
de `selection-data.js` no frontend configurado ou no jar, sem executar JavaScript.
Repetir o comando conserva senhas, decisões, perfis e arquivos existentes.
Uma falha da transação remove os arquivos sintéticos recém-criados.

`seed-scenarios` inclui pedido pendente, reserva avulsa paga e mensalidade paga,
usando os serviços de domínio e recibos assinados do gateway de desenvolvimento.
Não movimenta dinheiro real. Exige `PAYMENT_PROVIDER=development`.
Ambos os comandos são proibidos com `NODE_ENV=production`.
Não execute esses comandos contra dados reais. Não há senha padrão.

Validação final em 04/10/2026: 8 testes unitários e 15 de integração no seed,
sem falhas ou testes ignorados. Inclui PostgreSQL real, concorrência, rollback
dos arquivos, execução do jar e replays com o original nos dois sentidos.

## Verificar o frontend existente com Java

```powershell
./backend-java/scripts/e2e-local.ps1 -LocalDatabase
# Executar uma jornada específica:
./backend-java/scripts/e2e-local.ps1 -LocalDatabase -Journey auth
```

Sem `-LocalDatabase`, o suporte Java usa PostgreSQL em Testcontainers. O modo
local exige o banco dedicado `unicarona_java_test` em loopback. Não execute esses
testes ao mesmo tempo que a suíte Maven de integração no mesmo banco dedicado.

O runner compila o suporte Java e executa as dez jornadas Playwright com o
frontend original servido pelo Spring. Usa Chrome em `E2E_BROWSER_PATH`, o
Chrome local padrão no Windows ou o Chromium instalado para Playwright.
Node/tsx, pg, Playwright e sharp são dependências dos testes; nenhum serviço do
backend TypeScript original é importado. Controle de manutenção, outbox,
conciliação, notificações e falhas sintéticas usa stdin no classpath de testes,
sem rotas públicas de controle. O jar de produção não inclui esse suporte.

Prova final em 04/10/2026, aproximadamente às 19:12 BRT: 10 jornadas passaram,
sem falhas, cancelamentos ou skips, em 416,063 segundos, com Chrome real
e PostgreSQL 18.6 via Testcontainers. A modalidade local também havia passado
às 15:29 BRT, em aproximadamente 243 segundos.
Cadastro/confirmação/login/reset, perfis, motoristas/documentos/administração,
viagens/busca/reservas, avulsa/mensal, pagamento, ocorrência/revisão conjunta,
carência/renovação, Sem aula/ausência/crédito, saques/devoluções, notificações,
histórico, paginação, falhas de rede/provider e layout foram exercitados.
Todas as jornadas exigem ausência de exceções de JavaScript. Foram produzidas
45 capturas em `docs/previews/java-connected/`. Armazenamento e caixa postal
de cada execução são privados, sintéticos e removidos ao encerrar.

## Docker Java

O `compose.yaml` principal e o `compose.java.yaml` definem PostgreSQL 18,
seed, API e worker Java.
Usa volumes próprios `unicarona-java_postgres` e `unicarona-java_storage`.
A imagem compila o jar com Java 21 e executa com usuário sem privilégios;
o contexto de build exclui `.env`, testes e o backend TypeScript.
O frontend existente em `dist` é incluído no jar. A API verifica `/health/ready`;
o worker não abre HTTP e recebe 45 segundos para encerrar.

Crie um arquivo privado a partir de `backend-java/.env.example` e preencha
`PG_SUPERUSER_PASSWORD`, `PG_APP_PASSWORD`, `JAVA_DATABASE_URL`, `TOKEN_PEPPER`,
`PIN_ENCRYPTION_KEY`, `JWT_SECRET`, `PAYMENT_WEBHOOK_SECRET` e `SEED_PASSWORD`.
`JAVA_DATABASE_URL` deve usar o host `db`, porta 5432, banco `unicarona` e
usuário `unicarona`, com a senha de `PG_APP_PASSWORD` escapada como URI.
Use chaves próprias para esse ambiente e não reutilize credenciais de produção.

Com Docker funcionando, execute na raiz:

```powershell
docker compose --env-file .local/docker-java.env -f compose.java.yaml up --build -d
docker compose --env-file .local/docker-java.env -f compose.java.yaml ps
```

Abra `http://localhost:3003` ou a porta definida em `JAVA_HTTP_PORT`.
O seed cria apenas contas e documentos fictícios; pagamento e e-mail usam os
adapters de desenvolvimento. As variáveis de inicialização do PostgreSQL
são aplicadas apenas ao criar o volume pela primeira vez.

Docker executado em 04/10/2026: build real, PostgreSQL 18.6, seed exit 0,
API healthy, worker sem HTTP, usuário uid/gid 10101 e arquivos preservados
após recriar os contêineres sem remover volumes. O smoke test verificou as
93 operações, login, arquivos privados e WebP nativo no Linux.
O WSL 2 está operacional após o boot das 18:32:30 BRT.

Neste computador, inicie a sessão persistente e configure Testcontainers:

```powershell
./backend-java/scripts/start-docker-local.ps1
./backend-java/scripts/maven-local.ps1 verify
```

Para Compose sem Docker CLI no PATH do Windows, use `wsl -d UniCaronaDocker -u root --exec docker`
como prefixo e caminhos Linux `/mnt/d/UniCarona/...` para o arquivo de ambiente
e o Compose. O helper mantém a distribuição ativa; systemd sozinho não foi
suficiente para mantê-la executando após o término do comando de build.
A execução integral Testcontainers passou: 372 testes Java e dez jornadas Chrome.
