# Verificação de e-mail — UniCarona

**Configuração local atual, 17/10:** depois da migração de segurança, edite as
variáveis de e-mail em `%LOCALAPPDATA%/UniCarona/Private/config/app.env`.
`D:\UniCarona\.env` contém apenas um ponteiro sem credenciais. Os helpers
resolvem o arquivo protegido e preservam a sobreposição de SMTP no sandbox.
As referências antigas a `.local/arquivo.log` correspondem agora à cópia em
`Private/archives/legacy-local/arquivo.log`. Essa migração não refez envios reais.
[Detalhes e limites](privacy/Armazenamento_Backup_Local.md).

Implementado e validado de ponta a ponta em 16/10/2026 no ambiente local de teste: cadastro, envio real pelo Resend, recebimento no endereço autorizado, abertura do link recebido, confirmação no backend/banco, interface de sucesso e login. A configuração comercial de domínio e o envio para os demais alunos ainda dependem da preparação de produção descrita abaixo. Aceitação pelo provedor, recebimento e aparência no cliente de e-mail são evidências distintas.

## Arquitetura e escolha do serviço

Mantida a arquitetura Java/Spring, PostgreSQL, sessão com cookies HttpOnly e outbox existente. O cadastro grava usuário não verificado, perfil, hash do token e trabalho de envio na mesma transação. O worker envia fora de transações/locks longos. A verificação só é concluída pelo backend.

O serviço originalmente integrado foi **Resend**, por sua API HTTPS pequena, suporte explícito a HTML/texto e idempotência de envio. Em 16/10, o usuário escolheu **SMTP do Gmail para os testes atuais**, para enviar a endereços diferentes sem comprar domínio. O adapter SMTP existente foi aproveitado, com TLS obrigatório fora de loopback. Amazon SES também foi considerado: é compatível com crescimento e SMTP, mas requer configuração AWS/identidades e liberação do sandbox; não traz uma vantagem operacional imediata ao projeto atual. Não há dependência do SDK Java do Resend: usa-se o HttpClient do JDK, sem URLs externas configuráveis pelo navegador.

Fontes oficiais consultadas em 15/10/2026: [API Resend](https://resend.com/docs/api-reference/emails/send-email), [idempotência de 24h](https://resend.com/docs/dashboard/emails/idempotency-keys), [domínios verificados](https://resend.com/docs/dashboard/domains/introduction), [restrição de teste sem domínio](https://resend.com/docs/knowledge-base/403-error-resend-dev-domain), [identidades e sandbox SES](https://docs.aws.amazon.com/ses/latest/dg/verify-addresses-and-domains.html). Custos e limites comerciais devem ser conferidos no plano contratado; esta implementação não contrata serviços ou planos pagos.

## Fluxo e segurança

1. `POST /api/v1/auth/register`: valida dados públicos igualmente para contas novas e duplicadas; normaliza e-mail; cria apenas uma conta. Resposta genérica HTTP 202, sem sessão autenticada.
2. Token gerado com SecureRandom: 32 bytes, 256 bits, 43 caracteres base64url. `auth_tokens` guarda HMAC-SHA256 com pepper privado, prazo e uso. Novos links de confirmação valem **60 minutos**, configuráveis entre 5 e 60. Recuperação de senha continua com 30 minutos.
3. URL baseada em `APP_ORIGIN`, validado no startup; produção exige HTTPS. O token fica no fragmento `/login.html#verificar?token=…`, que não é enviado no pedido HTTP da página. O JavaScript remove o fragmento antes de enviá-lo no corpo do POST. Meta `no-referrer` preservada.
4. URL e conteúdo completo do e-mail ficam cifrados com AES-GCM na outbox. HTML/texto são congelados ao emitir o token: retries ou deploys não mudam o conteúdo sob a mesma chave de idempotência. Não há cartão, senha ou API key no e-mail.
5. O worker descarta mensagens de tokens consumidos, substituídos, expirados ou usuários excluídos. A validação continua soberana se uma substituição ocorrer durante o envio. Ao concluir, remove os campos cifrados do trabalho e conserva metadados/auditoria.
6. `POST /api/v1/auth/verify-email` deriva o usuário do hash, trava usuário e token nessa ordem, confirma a conta e consome o token atomicamente. Não aceita ID de usuário nem endereço para escolher a conta a verificar.
7. Login exige confirmação. O filtro também recusa sessões de contas não verificadas, inclusive se uma sessão anterior existisse. Verificar não autentica automaticamente.

Expiração, uso e substituição têm códigos `TOKEN_EXPIRED`, `TOKEN_USED`, `TOKEN_REPLACED`; token inexistente usa `TOKEN_INVALID`. Só quem possui o token aleatório recebe essa distinção. Eventos inválidos/expirados são auditados sem o segredo e sem reverter a auditoria ao responder erro.

## Reenvio, concorrência e falhas

- `POST /api/v1/auth/resend-verification`: apenas e-mail normalizado; resposta genérica idêntica para endereço inexistente ou conta já confirmada.
- Cooldown persistido de **60 segundos por endereço**, incluindo desconhecidos; cadastro inicia esse intervalo. Reenvio antes do prazo retorna HTTP 429 `EMAIL_COOLDOWN` com `retryAfterSeconds` real. O contador do navegador é apenas uma orientação e não substitui o limite do servidor.
- Até **5 reenvios por hora por endereço** e **20 pedidos por hora por IP**, além do limite geral da API. Confirmação limita 30 tentativas por IP em 15 minutos. Não confiar em X-Forwarded-For livremente fornecido por clientes.
- Novo token invalida imediatamente o anterior. Requests concorrentes serializam pela linha do usuário; não ficam vários tokens atuais válidos. Dois cliques têm um único vencedor e não alteram novamente a data de confirmação.
- Resend recebe a mesma `Idempotency-Key` (`auth-email/{jobId}`) em cada retry. A proteção externa dura 24h; tokens de até 60 minutos são checados antes da entrega, inclusive após lease expirar. SMTP tem Message-ID estável, mas não oferece garantia universal de entrega única; duplicatas de transporte não tornam o token reutilizável.
- Falha do provedor conserva a solicitação, registra erro sanitizado e aplica o backoff da outbox; após oito tentativas, vai para a fila administrativa de tarefas esgotadas. Não apagar ou recriar o trabalho para recuperar um envio incerto.
- O cadastro e a tela dizem que a solicitação foi recebida e que o envio pode levar alguns instantes. Não afirmam entrega confirmada. O usuário pode reenviar depois do prazo; a administração acompanha falhas em tarefas/auditoria. Nenhum detalhe bruto do upstream é devolvido ao usuário.
- `GET /api/v1/auth/email-service-status` informa somente atraso geral de envios recentes com falha na fila; não recebe endereço, ID ou token. Query adicional é rejeitada. A tela de confirmação consulta esse aviso ao abrir e após 5, 15 e 30 segundos; novas solicitações reiniciam a consulta. É uma indicação geral, não um rastreamento de entrega da pessoa. Falhas de conexão também recebem orientação de tentar mais tarde.

## Interface e template

Mantidos o layout, a tipografia e os componentes do acesso existente. Confirmação mostra endereço mascarado, orientação de Spam/Lixo eletrônico, contador, reenvio e volta ao login. Login não confirmado leva a essa recuperação sem exigir preencher o cadastro novamente. Há estados de verificando, sucesso, expirado, utilizado, substituído, inválido e falha recuperável com nova tentativa.

Template `AuthEmailTemplate`: palavra-marca UniCarona, preto/branco, detalhe verde, saudação escapada, título direto, CTA, validade, segurança e rodapé. HTML em tabelas com estilos inline e fontes do sistema, sem JavaScript, pixels, imagens remotas ou CSS externo. Alternativa texto puro acompanha o HTML. Token só aparece dentro do link. Recuperação de senha utiliza o mesmo sistema visual, preservando sua finalidade.

As rotas públicas `login.html#ajuda` e `#privacidade` explicam acesso e privacidade da confirmação. Essa explicação não substitui a política completa de privacidade, retenção ou encerramento de conta do produto. Em produção, configurar URLs reais aprovadas de suporte e política.

## Banco, código e endpoints

- V26 acrescenta `invalidated_at`, `sent_at` e `last_delivery_error_at` em `auth_tokens`, mais índice dos tokens atuais. `email_verified_at` e os hashes já existiam. Migrations antigas não foram editadas.
- `sent_at` significa que o transporte aceitou o envio; não é comprovante de entrega no destinatário. Logs `email_requested`, `email_delivery_completed`, `email_delivery_failed`, `email_delivery_skipped` e `email_verified` conservam IDs/tentativas, sem token.
- Endpoints existentes preservados: `/auth/register`, `/auth/login`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/forgot-password`, `/auth/reset-password` sob `/api/v1`. Cadastro/reenvio acrescentam `retryAfterSeconds`; mensagens genéricas permanecem.
- Novo endpoint: `GET /api/v1/auth/email-service-status`, resposta `{ "delayed": false, "message": "" }` ou aviso genérico quando há falha recente pendente/esgotada. Contrato sem parâmetros, sujeito ao limite geral da API; não permite consultar o estado de uma conta.
- Arquivos principais: `auth/{controller,service,repository,dto}`, `security/{ApiSecurityFilter,RequestContractValidation}`, `notification/{config,provider,service,template}`, `job/{service/OutboxProcessor,repository/OutboxDeliveryRepository}`, migration V26, `dist/login.js`, `dist/login.html` e exemplos de ambiente. Testes de autenticação/Flyway existentes foram atualizados para as mudanças explícitas; hashes, cookies e contratos restantes são preservados.

## Configuração

Exemplos: [raiz](../.env.example), [teste externo Resend](../config/email.test.env.example), [teste Gmail](../config/email.gmail.env.example), [produção Resend](../config/email.production.env.example).

`EMAIL_PROVIDER=development|resend|smtp`, `EMAIL_PROVIDER_API_KEY`, `EMAIL_FROM`, `EMAIL_VERIFICATION_MINUTES`, `APP_ORIGIN`, `EMAIL_SUPPORT_URL`, `EMAIL_PRIVACY_URL`. `MAIL_FROM` continua como alias legado se `EMAIL_FROM` não estiver definido. Sessão e cifragem continuam usando `TOKEN_PEPPER`, `JWT_SECRET`, `PIN_ENCRYPTION_KEY`. SMTP usa `SMTP_HOST`, `SMTP_PORT`, `SMTP_SECURE`, `SMTP_USER`, `SMTP_PASS`.

Desenvolvimento automatizado usa fake provider ou caixa privada `DEV_MAIL_PATH`, fora de `dist`. Credenciais externas não são necessárias para os testes. Os testes reais iniciais usaram Resend; sem domínio, `onboarding@resend.dev` só envia para o endereço associado à conta do próprio provedor. Isso não habilita envio para alunos em geral. O caminho atual de testes com Gmail SMTP permite destinatários diferentes, sujeito aos limites e controles do Google, como detalhado abaixo.

API e worker precisam da mesma configuração/banco. `npm run build`, `npm start`, `npm run worker` usam os scripts existentes. Para o sandbox dedicado já em uso, `mercadopago-sandbox-local.ps1 -Mode api|worker` também lê as configurações privadas de e-mail da raiz, mantendo origem e banco do sandbox.

## Domínio e entregabilidade antes de produção

1. Cadastrar um domínio/subdomínio de envio próprio no Resend. Publicar os registros SPF e DKIM exatos gerados pelo painel e aguardar a verificação; não inventar valores DNS.
2. SPF: evitar dois registros no mesmo nome. Preservar serviços existentes e conferir o domínio de retorno indicado pelo provedor.
3. DKIM: publicar os seletores/valores fornecidos e conferir autenticação em mensagens recebidas.
4. DMARC: configurar `_dmarc` com alinhamento ao domínio visível do remetente; iniciar monitoramento e avançar a política depois de avaliar os relatórios. Definir endereço responsável pelos relatórios. Não presumir que SPF/DKIM isolados configurem DMARC.
5. Usar remetente real e subdomínio dedicado ao envio transacional. Desabilitar rastreamento de abertura/cliques nos e-mails de autenticação. Configurar domínio público HTTPS, suporte/política reais, alertas para fila esgotada, falhas e reputação de envio.
6. Separar chaves/contas/bancos de desenvolvimento, teste e produção. Não registrar tokens, senhas, cabeçalhos Authorization ou corpo completo de pedidos/e-mails. Manter mailbox e `.env` privados e fora do Git.

## Testes e prova real

As suítes usam o banco descartável `unicarona_java_test`, com fake provider/SMTP local/servidor HTTP local para erros do Resend. Nenhum teste automatizado depende de receber e-mail real ou mover dinheiro. O backend TypeScript permanece legado; o caminho implementado e executado é Java.

| Execução | Resultado | Escopo |
| --- | --- | --- |
| 15/10, 21:04, `email-verification-final.log` | 166 testes, zero falhas/erros/ignorados | 30 unitários; 136 integração: Flyway, compatibilidade, autenticação, e-mail, jobs, pagamentos e Mercado Pago |
| 16/10, 10:19, `email-verification-delivery-status.log` | 81 testes, zero falhas/erros/ignorados | 30 unitários; 51 integração: autenticação, e-mail, jobs e compatibilidade após acrescentar o aviso geral |
| JavaScript | `node --check dist/login.js` passou | Sintaxe da versão final da interface |
| Navegador | Fluxo real e capturas desktop/celular | Link expirado, novo pedido, sucesso de verificação e login |

São **167 testes distintos** cobertos pelas duas execuções; os 81 da última execução incluem repetições e não se somam aos 166. Não foi executada toda a suíte global do repositório nesta etapa. Os testes de e-mail incluem os 12 cenários solicitados, concorrência entre verificar/reenviar, token de recuperação recusado na verificação, conta excluída, auditoria persistente, normalização e respostas equivalentes para endereços existentes/desconhecidos. Testes de transporte cobrem HTML/texto, sanitização, idempotência e configuração de produção.

Comandos reproduzíveis, na raiz do projeto e com o ambiente de testes privado configurado conforme os scripts existentes:

```powershell
pwsh -NoProfile -File backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=EmailProviderTest,ResendEmailProviderTest,AuthBoundaryTest,RequestContractValidationTest,WorkerLifecycleTest' '-Dit.test=FlywayCompatibilityIT,CompatibilityAuditIT,PaymentHttpIT,MercadoPagoHttpIT,AuthHttpIT,EmailVerificationHttpIT,JobHttpIT' verify
pwsh -NoProfile -File backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=EmailProviderTest,ResendEmailProviderTest,AuthBoundaryTest,RequestContractValidationTest,WorkerLifecycleTest' '-Dit.test=CompatibilityAuditIT,AuthHttpIT,EmailVerificationHttpIT,JobHttpIT' verify
node --check dist/login.js
```

Prova nativa, sem editar manualmente a verificação, validade ou consumo dos tokens:

1. Backup anterior à V26 restaurado em banco temporário e comparado: 52 tabelas, 1.953 linhas, esquema e dados equivalentes; restauração temporária descartada. O banco de demonstração financeira foi preservado.
2. Cadastro HTTP 202 com perfil **sintético** e endereço real autorizado da própria conta Resend. Antes de confirmar: login HTTP 403 `EMAIL_UNVERIFIED`, sem cookies de sessão.
3. Primeira mensagem recebida em 15/10 às 21:07:14, comprovada pelo conector Gmail. Estava inicialmente em Spam; não se atribui entregabilidade geral com base nesse teste.
4. Após expirar naturalmente durante a noite, o link original mostrou **O link expirou.** Foi solicitado novo link pela interface normal, sem repetir o cadastro.
5. Segunda mensagem recebida em 16/10 às 10:11:21, encontrada com marcador Inbox. HTML e texto recebidos foram lidos pelo conector; o token do link foi comparado por HMAC ao token corrente do banco, sem imprimir seu valor.
6. O link dessa mensagem foi aberto no navegador da aplicação: fragmento removido, POST validado, `email_verified_at` preenchido, token consumido e tela **E-mail confirmado.** exibida em desktop e celular. O login normal depois disso abriu o dashboard protegido.
7. Repetição do POST retornou HTTP 400 `TOKEN_USED`; o trabalho de envio ficou `done`, uma tentativa, `sent_at` preenchido e conteúdo cifrado removido do payload. Soma contábil continuou zero.
8. API e worker atualizados para o mesmo artefato SHA-256 `3a4948dfb3a7f3c6d200abff0f50cbed72475b4dad808139f22345c921241e32`, V26, banco de teste preservado. `/health/ready` confirmou `ready`; status geral de e-mail HTTP 200, e query indevida HTTP 422. Prova de consumo e integridade repetida nesse artefato.

Evidências privadas em `.local` incluem logs de teste, runtime, backup e correspondência token/mensagem; não devem ser publicadas. Capturas sem token em `.impeccable/review/email/`. O usuário dispensou explicitamente a inspeção visual do Gmail: **não foi verificada a aparência dentro de Gmail, Outlook, modo escuro ou aparelhos físicos**. O template foi revisado no código e nos testes de HTML/texto; isso não equivale a homologação visual em clientes de e-mail. A URL de teste é local e só funciona em máquina com acesso ao servidor; não foi enviada uma URL de produção inexistente.

## Auditoria dos 34 requisitos

| Nº | Requisito | Implementação / evidência |
| --- | --- | --- |
| 1 | Entender o projeto | Reutilizados Java/Spring, sessão, PostgreSQL, `auth_tokens` e outbox; recuperação e pagamentos incluídos na regressão. |
| 2 | Fluxo completo | Cadastro real → envio → Gmail → link recebido → banco → sucesso → login comprovados acima. |
| 3 | Backend como autoridade | A interface só mostra sucesso após POST aceito; acessar `#verificado` diretamente não confirma conta. |
| 4 | Modelo de dados | `users.email_verified_at`, token por usuário/finalidade; V26 acrescenta substituição e metadados de envio. |
| 5 | Token seguro | SecureRandom 32 bytes, base64url; HMAC-SHA256 com pepper privado na tabela de validação. |
| 6 | Expiração | 60 minutos, configuráveis 5–60; teste de backend e link realmente expirado na interface. |
| 7 | Uso único | Consumo na mesma transação da confirmação; replay recusado por API e testes. |
| 8 | Reenvio | Cooldown persistente de 60s; 5/h/endereço e 20/h/IP; contador na interface. |
| 9 | Enumeração | Respostas genéricas para cadastro duplicado, reenvio desconhecido/confirmado; catálogo validado antes da decisão sobre existência. Login só distingue não confirmado após senha válida. |
| 10 | Normalização | Trim e lowercase antes de validação/pesquisa; unicidade preservada e testes de contrato. |
| 11 | Pós-cadastro | Ícone, endereço mascarado, próximo passo, reenvio, contador e volta ao login. |
| 12 | Não verificado | Login 403 sem sessão; filtro protege rotas mesmo perante sessão antiga de conta não confirmada. |
| 13 | Estados | Loading, sucesso, expirado, inválido, utilizado, substituído e erro recuperável; token de retry só em memória. |
| 14 | Template profissional | Palavra-marca, saudação escapada, título, CTA, prazo, aviso de segurança, suporte e privacidade. |
| 15 | HTML/texto responsivo | Tabelas, largura fluida, CSS inline, fontes do sistema, MIME alternativo; visual dentro do Gmail dispensado pelo usuário. |
| 16 | Token na mensagem | Somente no URL do CTA/alternativa textual; nenhum código isolado apresentado ao usuário. |
| 17 | URL configurável | `APP_ORIGIN`, caminho restrito; HTTPS obrigatório em produção; token em fragmento removido antes do POST. |
| 18 | Serviço real | Resend integrado e recebimento comprovado; SMTP preservado; motivos de escolha e fontes acima. |
| 19 | Segredos | Variáveis privadas, não enviadas ao frontend; `.env` e `.local` ignorados pelo Git. |
| 20 | Entregabilidade | Instruções SPF/DKIM/DMARC/remetente acima; aplicação real dos DNS depende de domínio próprio. |
| 21 | Endpoints | Rotas existentes aproveitadas; status geral novo sem consulta de conta; contratos explícitos. |
| 22 | Autorização | Conta derivada do hash/finalidade, sem ID escolhido pelo cliente; contrato rejeita campos extras. |
| 23 | Concorrência | Lock usuário → token; transação atômica e testes de clique simultâneo/verificar × reenviar. |
| 24 | Substituição | Novo link invalida o anterior; fila descarta trabalho antigo; testes de substituição. |
| 25 | Falha de envio | Outbox/backoff/fila esgotada, auditoria sanitizada, reenvio e aviso geral ao usuário sem enumerar contas. |
| 26 | Logs | Solicitado, concluído, falhou, descartado e verificação inválida/expirada/usada registrados sem segredo. |
| 27 | Segurança | Revisão abaixo e testes de replay, rate limits, finalidade, validação, escape, origem, TLS e configuração. |
| 28 | UX simples | Sem refazer cadastro; recuperação clara, e-mail mascarado e feedback honesto de solicitação/atraso. |
| 29 | Testes | 12 cenários exigidos e casos adicionais em `EmailVerificationHttpIT`; regressões de sessão, recuperação, jobs, banco e pagamentos. |
| 30 | Desenvolvimento | Provider de desenvolvimento fora de `dist` e doubles locais; prova externa separada. |
| 31 | Produção | Exemplos raiz/backend/teste/produção; validação recusa provider de desenvolvimento e remetente de teste em produção. |
| 32 | Preservação | Nenhum reset do banco de demonstração; migrations antigas intactas; contratos/cookies/recuperação e pagamentos verificados nas suítes indicadas. |
| 33 | Auditoria final | Provas reais, testes adversariais, inspeção de segredos e revisão visual com limites registrados. |
| 34 | Critério completo | Recebimento, link recebido, validação, atualização do banco, sucesso visual e login demonstrados; não apenas aceitação do transporte. |

## Revisão de segurança e limites

- **Tomada de conta/manipulação:** não há endpoint que aceite `userId` para confirmar; token não serve para trocar senha e token de recuperação não serve para confirmar. Alteração de senha mantém seus próprios controles/revogação de sessão. Confirmar endereço não é prova de identidade, maioridade ou matrícula.
- **Vazamento/replay:** hash para validação, URL e conteúdo cifrados na fila, remoção após conclusão, fragmento removido do navegador e sem referrer. Um invasor que obtenha o link ainda tem uma credencial válida até expiração/uso/substituição; proteger mailbox, chaves e backups continua obrigatório.
- **Força bruta/abuso/enumeração:** limites persistentes, segredo aleatório e respostas genéricas; limites por IP atrás de proxy precisam preservar o endereço confiável do cliente. O endpoint de status é geral, sem dados por conta.
- **XSS/links/redirecionamento:** nome e conteúdo dinâmico escapados, origem/caminho/finalidade do URL validados, redirects HTTP do provedor desativados; destino após login mantém allowlist existente. Token vai em POST JSON, sem abrir uma sessão automaticamente; rotas autenticadas mantêm cookies SameSite/HttpOnly e CSRF.
- **Observabilidade:** erros do provedor sanitizados; nenhum corpo de resposta, Authorization, senha ou token deve ir para logs. `sent_at` indica aceitação, não abertura/entrega. A fila administrativa continua necessária para falhas esgotadas.
- **Operação externa:** faltam domínio público HTTPS, domínio de envio validado e DNS, remetente próprio, URLs finais de suporte/política e avaliação de reputação antes de liberar o envio a outros destinatários. A primeira mensagem em Spam é um limite observado, não uma garantia resolvida pelo reenvio.

O relatório visual independente [`finish-review.md`](../.impeccable/review/email/finish-review.md) recebeu **ship**, sem correções obrigatórias no escopo de confirmação/reenvio e template no código. O detector foi executado uma vez; o único aviso, Arial, corresponde à fonte do sistema existente e foi mantido. A comparação documental preserva DESIGN.md e seu sidecar, sem corrigir divergências anteriores fora de escopo.

A checagem final de segredos examinou 552 arquivos de fonte pública, configuração de exemplo, documentação e logs operacionais, sem encontrar os segredos privados nem os tokens/senha do ensaio nativo; nenhum arquivo foi pulado por tamanho. Resultado em [`security-audit.json`](../.impeccable/review/email/security-audit.json). `.env` e as evidências privadas foram confirmados como ignorados pelo Git. A impressão do JavaScript corresponde à versão referenciada pelo HTML e `git diff --check` passou (somente avisos de conversão LF/CRLF).

A política completa de privacidade/encerramento de conta e o lançamento público do MVP são trabalhos distintos.

## Gmail SMTP — configuração e prova de 16/10/2026

O pedido recente de confirmação era para o endereço do titular terminado em `0030`, enquanto a conta Resend liberava somente o endereço terminado em `003g`. A API de logs do Resend mostrou oito respostas HTTP 403 com a restrição de destinatário de teste; não era lentidão da fila. O histórico foi preservado. Após a escolha do usuário pela alternativa gratuita, ele gerou uma senha de app no Google e a salvou privadamente.

A autenticação da conta remetente foi validada antes da alteração. Configuração atual: `EMAIL_PROVIDER=smtp`, `SMTP_HOST=smtp.gmail.com`, `SMTP_PORT=465`, `SMTP_SECURE=true`, `SMTP_USER` e `EMAIL_FROM` correspondendo à conta autorizada, com `SMTP_PASS` privado. Conexão TLS com validação de certificado/hostname; nenhuma senha normal do Google é usada. API e worker precisam ser reiniciados após alterar essas variáveis. O helper do sandbox carrega esses valores e `WORKER_IDLE_MS` da raiz, sem trocar origem, banco ou chaves financeiras.

Foi configurado `WORKER_IDLE_MS=250`, reduzindo o intervalo de consulta da fila vazia de 1 segundo para 250 ms. Isso reduz a espera de descoberta do trabalho; não acelera na mesma proporção a autenticação SMTP, a transmissão nem o processamento do destinatário.

Provas externas:

- **10:57:** pedido normal `POST /auth/resend-verification`, HTTP 200; novo trabalho concluído em uma tentativa, **4,690 segundos** entre criação e conclusão. `sent_at` preenchido, payload sensível removido, token ainda não consumido, soma contábil preservada. Mensagem encontrada na **INBOX** da conta destinatária por IMAP autenticado, consultando somente o Message-ID desse trabalho em modo somente leitura; continuou não lida. O link foi deixado para o titular usar.
- **10:58:** teste técnico simples, sem link de autenticação, enviado pela mesma conta SMTP ao outro endereço autorizado do titular. SMTP aceitou em **3,328 segundos**; o conector Gmail confirmou a mensagem na **INBOX** desse destinatário diferente. Esse teste adicional valida o transporte entre contas; o fluxo completo da aplicação foi exercitado no item anterior.

Não foram enviados dez e-mails ou testados dez destinatários: foram comprovados dois endereços autorizados. A aplicação faz envios individuais por cadastro, sem a restrição de destinatário único do remetente de testes Resend. Limites diários, bloqueios antispam e tempos de entrega do Google continuam aplicáveis; não há promessa de entrega instantânea ou capacidade comercial garantida.

O sinal geral de atraso também passou a desconsiderar falhas de links substituídos, usados, expirados ou contas removidas, mantendo os registros de erro na auditoria. Um envio atual com falha continua sinalizando atraso. Essa correção evita que a falha histórica do Resend mantenha o aviso depois de um novo envio bem-sucedido; não apaga nem reclassifica manualmente os trabalhos antigos.

Validação dessa etapa: **59 testes passaram** (12 unitários de e-mail/ciclo do worker e 47 de integração de autenticação, verificação e jobs), sem falhas, erros ou ignorados; execução concluída às 11:01 em `.local/gmail-smtp-verification.log`. O caso novo conserva a tarefa antiga esgotada e sua auditoria, enquanto o novo envio funciona sem aviso de erro obsoleto. Sintaxe PowerShell do helper validada, segredos privados fora de Git e nenhum segredo encontrado na varredura de fontes/documentos/logs. A interface não foi alterada nesta etapa. API e worker executam o mesmo artefato SHA-256 `02892a03ee550f3eba0227e82e5676e0817e9e6c4bfd63383c1b9743062e875f`, sem nova migration ou recriação de banco.

Fontes: [SMTP do Gmail](https://developers.google.com/workspace/gmail/imap/imap-smtp), [senhas de app](https://support.google.com/accounts/answer/185833?hl=pt-BR), [limites de envio](https://support.google.com/mail/answer/22839?hl=pt-BR). A senha de app exige verificação em duas etapas e pode não estar disponível em algumas contas. Resend continua disponível para uso futuro com remetente/domínio verificado. Para alunos abrirem o link em outros dispositivos, ainda é necessário publicar a aplicação e configurar `APP_ORIGIN` público; o domínio de envio e a hospedagem da aplicação são configurações distintas.
