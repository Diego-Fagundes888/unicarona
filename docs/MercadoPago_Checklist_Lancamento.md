# Mercado Pago — auditoria dos requisitos para lançamento

## Entrega técnica — 15/10/2026, 20:35

O fluxo solicitado foi comprovado no sandbox oficial, incluindo pagamento, callback autenticado, GET, banco, reserva/mensalidade e interface. [Relatório de entrega](MercadoPago_Entrega_Integracao.md). A conciliação administrativa foi implementada e exercitada com estorno oficial; caronas consumidas sem crédito de cancelamento permanecem em análise conforme decisão explícita do usuário. São 205 testes distintos aprovados em conjuntos complementares, V25 e ledger conservado.

O checklist separa implementação de habilitação produtiva: não houve cobrança real, deploy de produção ou ativação de Pix/split aos motoristas. Os requisitos externos de lançamento estão listados ao final. As seções datadas abaixo são histórico; a matriz de 30 requisitos e o relatório de entrega são a consolidação atual.

## Estado atual — 15/10/2026, 14:54

[3DS interativo aprovado](MercadoPago_3DS_Homologacao.md). [Carência mensal com Pix oficiais e relógio de manutenção controlado](MercadoPago_Carencia_Homologacao.md): renovação, expiração, crédito tardio e reativação comprovados; não decorreram 72 horas reais. **95 testes distintos aprovados nesta etapa**, separados dos ensaios externos.

Permanecem a resolução administrativa de análises financeiras, conciliação de perdas fora do crédito de cancelamento e os requisitos comerciais/operacionais de produção e repasse. As atualizações datadas abaixo preservam o histórico; não tratar pendências antigas de webhook, dívida ou 3DS como estado atual.

## Atualização — 15/10/2026, 14:36

Challenge 3DS aprovado interativamente, com GET oficial `authenticated`, uma reserva/recebimento e webhook posterior válido sem duplicação. [Prova e limitações](MercadoPago_3DS_Homologacao.md). Este gate deixou de ser pendente; as seções anteriores abaixo preservam o histórico. Carência mensal externa e operação financeira de produção continuam separadas.

## Evidência atual — 15/10/2026, 13:56 — dívida e regularização oficiais

A regra escolhida pelo usuário foi implementada: **registrar dívida por crédito já utilizado e bloquear novas compras até regularizar**. O sandbox está em **V24**, com avaliação da dívida, conta a receber no ledger, Pix exclusivo de regularização, confirmação idempotente, reabertura por estorno e carteira conectada. Detalhes, endpoints e limites: [Regularização de dívidas](MercadoPago_Regularizacao_Dividas.md).

O ensaio oficial partiu de crédito zero, pagou uma nova carona, cancelou-a para gerar crédito, consumiu integralmente os R$ 5,93 em outra reserva e estornou a cobrança de origem pelo endpoint oficial Orders. A API registrou dívida de R$ 5,93 e recusou os quatro checkouts Pix/cartão, com/sem crédito, com **409 PASSENGER_DEBT**. Nenhum estado financeiro foi alterado diretamente no banco.

| Prova | Resultado |
|---|---|
| Pagamento de origem / order | `5071ee79-e520-4025-9eba-47ddefb66404` / `ORDTST01M507Q0CFJQ6EQKG02J2NRHX0` |
| Reserva que consumiu o crédito | Pagamento `48f05bd0-466b-426c-b0de-0c0b78d66efa`, R$ 5,93, confirmado com crédito |
| Regularização iniciada pela carteira real | `6426c841-67e9-4e5b-9728-f51722e55806` / `ORDTST01M507T7NNNZHFF2AAH2RX97V0` |
| Valor calculado no servidor | R$ 5,93, sem taxa adicional de regularização |
| Callback espontâneo 13:55:08 | HMAC válido, aplicação 4063897889583204, HTTP 200, 218 ms; ainda pendente |
| Callback espontâneo 13:55:14 | HMAC válido, GET oficial e HTTP 200, 281 ms |
| Banco às 13:55:14.761 | APPROVED, dívida zero, um lançamento de regularização, nenhuma reserva criada |
| Conferência somente de leitura às 13:56 | GET processed/accredited, referência e valor correspondentes, bloqueio removido, ledger zero |
| Novo checkout após a baixa | Preparação de cartão aceita; sem envio de cartão ou cobrança adicional. Pedido de teste cancelado depois |

**O worker ficou parado durante a regularização.** Não foi usado simulador nem atualização manual de status nessa cobrança; a aprovação foi causada pelo webhook espontâneo. A carteira apresentou Pagamento confirmado e O bloqueio por dívida foi removido sem clicar em Atualizar. O worker foi restaurado após a prova, PID 1516, inicializado às 13:56:54.

Após a prova oficial, foi corrigido e testado o caso em que a primeira observação já contém devolução parcial processada: recebimento e reversão ficam na mesma transação e somente o líquido regulariza a dívida. A suíte Mercado Pago passou com 51 cenários (mais 19 unitários), às 14:01:36. Somados aos 11 de identidade/contratos e 38 de regressão financeira já aprovados nesta etapa, são 119 testes distintos. Essa correção usa prova de protocolo com upstream simulado, sem atribuir a ela um novo ensaio externo.

Versão final recompilada às 14:02:32: Jar SHA-256 `c12521db561106587f5ced1c8e32d1049071e856bc4db4cb15cecb0e2c77ef26`. API PID 15220 pronta e worker PID 4980 iniciado às 14:03:27; V24 preservada. Conferência somente de leitura às 14:03:49 manteve dívida zero, um recebimento de regularização e ledger zero. A carteira foi recarregada e conferida nessa versão, screenshot privado `mp-oct15-debt-official-final.png`. A prova causal espontânea anterior permanece separada em `mp-oct15-debt-spontaneous-confirmation.json`; seu Jar era 337c9a125791465a1f8f7636bd61baf9a69d7c163a0691e0511418d32270bc34.


Validação automatizada: **19 unitários + 51 HTTP Mercado Pago + 11 identidade/contratos + 38 regressão financeira**, todos aprovados. Os testes HTTP usam PostgreSQL real e upstream simulado; a prova externa acima é independente. Carteira pendente e confirmada foi inspecionada em desktop e viewport móvel de 390 × 844 no ensaio isolado; a captura oficial final foi conferida em desktop. Detector estático Impeccable sem achados. Build concluído às 13:47:55, Jar SHA-256 `337c9a125791465a1f8f7636bd61baf9a69d7c163a0691e0511418d32270bc34`.

Provas privadas: `.local/mp-oct15-debt-official.json`, `mp-oct15-debt-spontaneous-confirmation.json`, `mp-oct15-debt-final-tests.log`, `mp-oct15-debt-finance-regression.log` e `mp-oct15-debt-official-approved.png`. Backup anterior à atualização: V22, 46 tabelas, 1.119 linhas, restauração correspondente e ledger zero, concluído às 13:43:42.

Esta etapa **não conclui a homologação geral nem autoriza produção**: permanecem a comprovação interativa do Challenge 3DS, o ensaio externo da renovação após carência e a operação financeira produtiva/repasse. Crédito retido em análise e ajustes de perdas fora do crédito de cancelamento também exigem conciliação própria. As seções anteriores abaixo preservam o histórico; a decisão sobre dívida não está mais pendente.

## Evidência atual — 15/10/2026, 12:48 — webhook espontâneo comprovado

O login legítimo do vendedor de teste foi concluído com os dados atuais do painel. A aplicação **4063897889583204 (TestApp-1175c80a)** apontava para um túnel antigo; a URL atual foi salva em seus modos de teste e produção e confirmada após recarregar o portal. Order e Contestações foram preservados. O modo de produção citado pertence à **conta de teste**, sem configurar callback produtivo da conta real. O segredo oficial completo dessa aplicação era diferente do `.env`; foi provisionado privadamente, com backup, e API/worker foram recarregados. Nenhuma credencial foi redefinida ou exibida no relatório.

A simulação oficial dessa aplicação respondeu **200 OK**, com HMAC original válido e GET autenticado, preservando o Pix anterior. Separadamente, uma **nova cobrança Pix sandbox** foi iniciada pelo checkout real com o worker parado e vendedor `test_user` verificado:

| Evidência | Resultado |
|---|---|
| Pagamento / order | `75a59378-7aa0-437f-8232-732b7675fb58` / `ORDTST01M503YWX5CR9NYQQQY0ZVK4E9` |
| Valor congelado | R$ 5,93, taxa incluída R$ 0,59 |
| 12:47:43.906 | Snapshot verificado PENDING; zero reservas/recebimentos |
| Callback espontâneo 12:47:45 | HMAC oficial válido, manifesto com ID em minúsculas, HTTP 200 em 234 ms |
| Callback espontâneo 12:47:51 | HMAC oficial válido; GET autenticado; HTTP 200 em 328 ms |
| Banco 12:47:51.610 | APPROVED, uma reserva e um recebimento |
| Conferência somente de leitura 12:47:57 | GET processed/accredited; valor, aplicação e referência correspondentes; ledger zero; worker ainda parado |
| Interface | Atualizou sozinha para Pagamento aprovado / Reserva confirmada |

**Esta aprovação foi causada pelo webhook espontâneo, sem simulador para a nova order e sem conciliação do worker.** O worker foi restaurado depois da confirmação e estava operacional às 12:48:36. As provas privadas estão em `.local/mp-oct15-child-webhook-proof.json`, `mp-oct15-child-pix-spontaneous-confirmation.json`, `mp-oct15-child-runtime-proof.json` e screenshot `mp-oct15-child-pix-confirmed.png`. O Pix anterior de 12:33 continua sendo prova de recuperação pelo worker, sem alterar sua causalidade histórica. Um callback anterior chegou durante reinício dos serviços e retornou 503; não confundir esse retry com a prova nova.

**A integração completa ainda não está concluída.** Permanecem Challenge 3DS interativo oficial, ciclo externo de carência mensal, política de diferenças financeiras e condições operacionais/comerciais de repasse. O novo replay manual desta order ainda não foi executado; a evidência anterior de duplicação de cartão permanece válida. Nenhuma fonte Java/frontend foi alterada nesta correção de configuração; a regressão combinada de 445 testes continua datada de 13/10. Backup de 12:33:50 (46 tabelas/1.074 registros) antecede esta nova cobrança e não representa o snapshot posterior.

## Registros anteriores — histórico

## Revalidação de 15/10/2026

**Lançamento continua pendente.** O OAuth desta conversa agora permite listar/administrar a aplicação principal **5555811490405924**. A URL atual do túnel foi salva via MCP como callback de sandbox, com Order/Contestações; callback produtivo permaneceu sem configuração. Credenciais de teste retornadas pelo MCP correspondem às efetivas do backend; o segredo mascarado retornado pela ferramenta não permite comparação integral, e nenhum segredo foi substituído. O acesso direto às credenciais da aplicação automática **4063897889583204** foi rejeitado por ownership. A falha anterior de sessão OAuth está resolvida; a relação entre conta, modo e configuração da entrega ainda precisa ser conferida.

Novo Pix sintético de **R$ 5,93**, criado pela interface real, resultou na order `ORDTST01M502SZT8ZJATPMXHZHZ40R90`. Com worker parado, o GET oficial aprovou valor/referência e identificou a aplicação automática, enquanto banco/UI continuaram pendentes, sem reserva/recebimento e sem callback observado. O worker foi restaurado: aprovação aplicada às **12:33:04**, uma reserva, um recebimento, ledger zero e UI “Reserva confirmada”. Esta é prova de conciliação por consulta; **não é prova de entrega espontânea autenticada**.

PostgreSQL dedicado recuperado sem recriar o banco, API pronta e mesmo Jar/V22. Novo backup/restauração às **12:33:50** conferiu **46 tabelas/1.074 registros**, esquema/dados correspondentes e remoção da cópia temporária. A senha disponível do vendedor de teste foi recusada no portal; aba preservada e orientação de login fornecida ao usuário, sem solicitar credenciais pelo chat. A documentação específica de Orders orienta a aba Modo de produção; o modo da aplicação de teste será conferido após o login, sem configurar produção da conta real por suposição.

Sem alterações de Java/frontend financeiro nesta rodada; a evidência combinada de **445 testes** descrita abaixo continua datada de 13/10. Permanecem os gates de webhook espontâneo, Challenge interativo, carência mensal externa, decisão sobre diferenças financeiras e condições reais de repasse. [Resultados e próximo passo](MercadoPago_Homologacao_Pendencias.md).

## Revalidação de 13/10/2026

**Lançamento continua pendente.** Restaurados PostgreSQL/API/worker do sandbox existente V22, com o mesmo Jar. GET oficial confirmou o Pix aprovado, a expiração do Challenge e a recusa por emissor do cartão antes em processamento. O worker aplicou os dois estados terminais sem reservas/recebimentos; tokens apagados e ledger zero. UI desktop/mobile mostrou expiração/recusa sem novo formulário, iframe ou overflow. Backup/restauração às 17:28:16 conferiu esquema e dados de 46 tabelas/1.042 registros, V22, snapshot consistente e remoção da cópia temporária.

O usuário autorizou a conexão OAuth pelo navegador; o cliente oficial confirmou o login, e `codex mcp list` mostrou **enabled / OAuth**. Removida somente a precedência do Bearer da configuração do MCP, com backup privado; credenciais do backend preservadas. As ferramentas desta conversa ainda usam a conexão anterior e recusam ownership OAuth; reconexão do cliente é necessária antes de validar gestão remota. Endpoint HTTPS de teste recriado/verificado às 17:42:48 (404 fora do webhook; 401 sem assinatura; inspeção desativada). Pelo portal, o segredo de teste da aplicação principal correspondeu ao `.env`; a URL antiga foi substituída e acionado Salvar, preservando Order/Contestações. O primeiro clique em Salvar não persistiu a URL. Após o usuário concluir o QR no aplicativo, a URL foi salva novamente e confirmada após reload, com Order/Contestações preservados. Novo envio do simulador terminou em timeout de 22 segundos sem callback assinado novo. A assinatura espontânea da aplicação automática continua pendente. GET/banco às 18:20:11 preservaram Pix aprovado, uma reserva e um recebimento. Nenhuma cobrança nova ou alteração de Java/frontend financeiro nesta rodada. [Evidência e sequência pendente](MercadoPago_Homologacao_Pendencias.md).

### Regressão atual e documentação da API — 13/10

Executados `test failsafe:integration-test failsafe:verify` com PostgreSQL real no banco descartável `unicarona_java_test`, sem empacotar/substituir o Jar do sandbox. A execução ampla terminou com **86 unitários e 359 integrações** e uma falha: o inventário de `IdentityDriverHttpIT` esperava 95 operações e não incluía `GET /api/v1/subscriptions/{id}/rights`. Os demais resultados passaram, incluindo 42 Orders, 33 contratos, sete migrations e o seed completo.

Corrigida somente a expectativa do teste para as 96 operações do modo development, incluindo o endpoint de direitos, sua declaração de autenticação e o schema `MonthlyContractRights.openRights`. A reexecução isolada dos **11 testes de `IdentityDriverHttpIT`** passou, sem falhas/erros/skips. Às **18:34:25**, os relatórios atuais correspondiam a 19 suites unitárias/86 testes e 23 suites de integração/359 testes, todos aprovados e escritos nesta rodada. **É evidência combinada da execução ampla com a reexecução corrigida; não foi um único comando amplo aprovado.** O wrapper privado inicialmente não propagou o código de falha do Maven; isso foi corrigido. O resultado Maven `BUILD FAILURE` original permanece registrado.

Exportada novamente [OpenAPI Java](api/openapi-java.json) a partir do servidor sandbox atual em modo Mercado Pago: **104 operações**, incluindo cartão, webhook, resumo e direitos mensais. O documento anterior tinha 93 operações e omitia a integração. O baseline da API antiga permaneceu preservado. Não houve alteração de Java financeiro ou frontend nesta rodada. API/worker/túnel continuaram ativos e API pronta; nenhum processo de teste permaneceu ativo após o término.

## Atualização de 10/10/2026, 23:39

**Ainda não aprovado para lançamento.** A V22 corrige direitos pagos da renovação antecipada; encerrou 43 reservas futuras dos dois períodos oficiais, creditando R$ 114,55, sem duplicação. O lote oficial de R$ 116,21 foi confirmado por GET e recuperado com os comandos originais depois de corrigir a leitura de histórico completo de devoluções. Um recebimento por pagamento, processamento zero e ledger zero preservados.

Verify pertinente: **101 testes selecionados** (19 unitários + 82 integrações) aprovado às 22:59:25. Backup/restauração V22 aprovado às 23:01:10 (46 tabelas/977 linhas). Após três ajustes de formatação de timestamps, package/readiness e UI nativa desktop/mobile em navegador UTC foram conferidos às 23:02–23:03; não houve mudança posterior de Java financeiro. Datas civis de agenda continuam iguais.

Pendente: assinatura da notificação espontânea da aplicação correspondente, Challenge 3DS, carência externa, política de divergência financeira e capacidade/condições reais de repasse. O usuário confirmou o segredo salvo no `.env`; a validação da assinatura permanece pendente, conforme o ensaio abaixo.

**Configuração e Pix às 23:21–23:27:** constatado arquivo `.env` salvo às 23:21:18; API/worker recarregados novamente às 23:26, depois dessa alteração. Novo Pix oficial `ORDTST01M4MC9DY4JN14A5RESQT2JVTW`, R$ 5,93, aplicativo `4063897889583204`: três notificações tiveram HMAC incompatível com o segredo no arquivo e respostas 401 antes da segunda reinicialização. GET oficial confirmou aprovação; com worker desligado, banco permaneceu pendente sem reserva/recebimento. Ao restaurar o worker, a conciliação gerou uma reserva e um recebimento; UI confirmada em desktop/mobile sem overflow ou erro JavaScript. Essa recuperação não comprova webhook autenticado. Não houve alteração posterior de Java/frontend financeiro ou repetição da suíte por essa configuração privada.

**Simulador às 23:35:58:** usuário informou HTTP 200 da aplicação **5555811490405924** para a mesma order. Diagnóstico/backend confirmaram HMAC original válido, timestamp fresco, GET e resposta em 1.031 ms; banco manteve uma reserva e um recebimento de R$ 5,93. É aplicação idempotente posterior à aprovação pelo worker, não prova de entrega espontânea da aplicação da cobrança. O MCP `application_list` encontrou a **4063897889583204** como **TestApp-1175c80a**, automática de teste. Solicitado conferir seu acesso pelo usuário de teste em janela privada; resultado pendente. [Evidência e orientação](MercadoPago_Homologacao_Pendencias.md).

**Evidência externa complementar às 23:11:** novo Challenge `APRO-CHOK` com Device ID foi tokenizado/enviado pelo SDK oficial; 503 inicial recuperado pelo worker. GET da order `ORDTST01M4MBFY8ES3BYJPQ30V7KXSPV` confirmou o aplicativo **4063897889583204**, valor/referência e `pending_challenge`. Zero reservas/recebimentos e token apagado. Iframe respondeu 403, sem botão Confirmar e sem violação CSP. Callbacks ainda falharam HMAC com o segredo atual. Challenge e notificação espontânea autenticada permanecem pendentes. [Orientação e relatório sanitizado](MercadoPago_Homologacao_Pendencias.md); código/pacote financeiro não mudaram nesta rodada.

Estado em 10/10/2026: **integração em andamento; lançamento não aprovado**. O objetivo exige Mercado Pago, SDK e webhook oficiais funcionando de ponta a ponta. Testes locais não atendem esse critério externo.

Este checklist corresponde aos 30 itens do objetivo original. **Local** significa implementação conferida em fonte e testes com PostgreSQL real e upstream/SDK sintéticos. **Parcial** significa que uma parte existe, mas falta evidência ou trabalho necessário. **Pendente** significa que não existe a prova exigida para encerrar o item. Os documentos históricos registram etapas anteriores; não somar suas contagens de testes como uma regressão atual.

## Evidências atuais — 10/10/2026, 21:36

- Novos callbacks oficiais do simulador: cartão aprovado às 21:34 e repetição às 21:35, ambos HTTP 200, preservando uma reserva/recebimento; renovação mensal às 21:35, HTTP 200, preservando 26 reservas/um recebimento. Assinatura e GET foram conferidos nos logs/banco. Entrega espontânea autenticada permanece pendente. O timeout anterior não teve causa determinada.
- Device ID implementado com SDK/coletor oficial, nonce CSP, validação, header e comando cifrado compatível com os antigos. Seleção às 20:35: 17 unitários + 41 integrações = 58, aprovada. Jornada com fixtures aprovada em 80,9 s. Cartão oficial com Device ID aprovado por recuperação da primeira resposta 503; GET/banco/UI confirmados às 21:32–21:33, uma reserva/recebimento e payload apagado.
- Dados inválidos comprovados oficialmente com `bad_filled_card_data`; processamento comprovado com `processing/in_process`. Zero reservas/recebimentos nesses casos. Challenge interativo encontrou iframe HTTP 403; cancelamentos de Challenge responderam 422 e permanecem sem confirmação externa. Correção da mensagem de expiração verificada na jornada com fixtures de 90,1 s às 21:50: cobrança continua sendo acompanhada após o prazo, aprovação tardia gera crédito sem confirmar reserva.
- Notificação às 21:46 indicou a aplicação de teste `4063897889583204`, com assinatura incompatível com o segredo que validou as simulações de `5555811490405924`; resposta 401 em 16 ms, timestamp fresco. Corpo não autenticado usado apenas como diagnóstico. Conferência do segredo correspondente solicitada ao usuário; entrega automática autenticada continua pendente.

As evidências abaixo foram obtidas até 20:16; conservar a distinção temporal das execuções e considerar as complementações acima.

- Pix avulso oficial: criação pelo frontend, QR, assinatura do simulador oficial, GET autenticado, reserva/recebimento únicos e UI. A consulta de 20:08 encontrou a devolução integral já concluída, preservando o recebimento original único. A simulação comprovada é manual; entrega espontânea ainda não foi demonstrada.
- Cartão oficial APRO-AUTH aprovado e OTHE-NAUT recusado, com tokenização pelo SDK e GET/banco/UI conferidos. Recusa: zero reservas/recebimentos, token apagado. O teste FUND de 20:12 ficou em Challenge pendente. O ensaio posterior FUND-AUTH retornou `insufficient_amount`, comprovado por GET oficial às 20:16: zero reservas/recebimentos, token apagado e mensagem específica em desktop/mobile.
- Mensal inicial de R$ 47,95/18 reservas e renovação de R$ 69,26/26 reservas confirmadas por worker/GET. A renovação possui um novo contrato e um recebimento, com referência/valor conferidos às 20:08. Falta callback oficial da mensal e carência externa.
- Devoluções oficiais integral R$ 5,93 e parcial R$ 1,00 verificadas por API/banco/carteira. Repasse automático ao motorista não habilitado; saldo devido registrado e saque recusado antes de movimentar valores.
- Manipulação de preço da renovação rejeitada (422), acesso por outro proprietário rejeitado (404), sem comando externo criado por essas tentativas.
- Backup/restauração nativos do sandbox aprovados às 19:56: 46 tabelas/508 linhas, V21, esquema e tabelas correspondentes, ledger zero, cópia temporária removida. Procedimento produtivo/chaves externas ainda pendentes.
- Regressão ampla das 19:46:05: 80 unitários + 346 integrações = 426, zero falhas/erros/skips. Precede os ajustes 402/423/motivo da recusa. Seleção mais recente das 20:08:39: 11 unitários + 40 integrações = 51, zero falhas/erros/skips. São execuções diferentes.
- Jornada atual do navegador: passou em 81,1 segundos, incluindo recusa específica, administração, devoluções, estados responsivos e SDK travado/callback tardio. Duas tentativas anteriores receberam 429 na recarga administrativa: a sequência composta ultrapassava o limite global usando um mesmo IP. A fixture reinicia o bucket no banco descartável entre passageiro e administrador, como cenários independentes. Nenhum limite produtivo foi alterado. Java/PostgreSQL reais; upstream e SDK desta jornada são fixtures.
- API/worker de sandbox iniciados com o Jar corrigido; `/health/ready` retorna `ready`. Histórico de notificações e avaliação de qualidade MCP continuam indisponíveis por propriedade OAuth; CUA indisponível antes de acessar o portal.

## Histórico de evidências anteriores

- Seleção anterior à correção da referência externa: 24 unitários e 73 integrações passaram em 10/10 às 09:37:31. Inclui cliente/assinatura/contratos, Orders, contas e catálogo administrativos.
- Jornada de navegador atual: passou em 48,7 segundos. Inclui Pix, recuperação de cartão, expiração, devolução parcial, bloqueio de crédito, reserva em contestação e consulta administrativa. Java/PostgreSQL reais; SDK e serviço Mercado Pago simulados.
- Regressão completa do código atual terminou em 10/10 às **10:06:12**: 79 unitários sem erros/falhas/skips e 345 integrações, com **dois erros de timeout na limpeza prévia de `SeedIT`**, zero falhas de asserção e zero skips. Build amplo reprovado. Os dois erros ocorreram antes dos métodos de negócio, em `TRUNCATE` do banco descartável; não são evidência de falha no pagamento, mas impedem considerar a execução ampla aprovada. A regressão de 418 testes de 09/10 precede V21 e o painel de revisão.
- Verificação após o término: nenhuma outra sessão ativa ou transação preparada no banco de testes. A reexecução isolada `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-DskipTests=false' '-Dtest=SeedSettingsTest,SeedCatalogTest' '-Dit.test=SeedIT' verify` passou às **10:13:00**, com **8 unitários + 15 integrações**, zero falhas/erros/skips. Fonte e timeouts não foram alterados; os erros de preparação não se repetiram. Essa evidência não identifica com certeza a causa do timeout anterior.
- Os relatórios atuais, após essa execução complementar, totalizam **79 unitários + 345 integrações = 424 cenários**, todos com resultado aprovado. São 19 suites unitárias e 23 de integração, escritos em 10/10 entre 09:50:06 e 10:13:00. O último fonte backend alterado foi em 09:36:18, antes de ambos os comandos. Trata-se de evidência combinada da execução ampla e da reexecução do seed; **o primeiro comando amplo teve exit 1 e não foi repetido integralmente**. Não apresentar esse histórico como um único `verify` amplo com exit 0.
- Jar reconstruído em 10/10 às 09:48:17, interface empacotada com hash correspondente ao fonte e ponte de testes ausente do artefato.
- Verificação privada às 18:53: `MERCADO_PAGO_WEBHOOK_SECRET` preenchido no `.env`, sem formato de Access Token. A presença permitiu iniciar o sandbox; às 19:21, o callback oficial do simulador comprovou a assinatura com esse segredo. Nenhum valor privado foi impresso.
- Varredura de 592 caminhos não ignorados pelo Git: nenhum achado para formatos de Access Token Mercado Pago ou chave privada nos arquivos de texto e tamanhos examinados. Essa verificação não é uma garantia de ausência de qualquer formato possível de segredo.
- Sandbox oficial: banco dedicado migrado até V21, seed sintético explícito e API `ready`; vendedor `/users/me` brasileiro com tag `test_user`. A primeira tentativa Pix pelo frontend recebeu rejeição oficial `property_value` por `external_reference` com `:`. Fonte corrigido para `UNICARONA_{paymentId}` e contrato de caracteres incluído no teste. Nenhuma order oficial criada ou webhook/reserva/mensalidade oficialmente confirmados nessa tentativa. A regressão das 18:59 teve 12 unitários aprovados, mas falhou no empacotamento porque o Jar estava aberto pela API no Windows; a API foi encerrada e o comando repetido. Evidências amplas acima precedem essa mudança.
- Após correção: seleção cliente/assinatura/Orders HTTP passou às 19:00:12, com **12 unitários + 38 integrações**, zero falhas/erros/skips. Pix oficial criado pelo frontend às 19:00: order `ORDTST01M4KX9JTTE8989ES87Q9XCMAH`, R$ 5,93 calculados no backend, aplicação teste `4063897889583204`. GET oficial confirmou `processed/accredited`, valor e referência correspondentes; QR exibido em desktop/mobile sem overflow. Backend permanecia `pending`; nenhum webhook assinado oficial observado, nenhuma reserva confirmada. Recibo da resposta inicial de criação não é prova de webhook. Worker não iniciado. Ensaio ponta a ponta segue pendente.
- Túnel recriado nesta auditoria: proxy Python PID 17500 e ngrok PID 17744, inspeção de requisições desativada. HTTPS público conferido: outro caminho retorna 404; POST no webhook sem backend retorna 503. URL atual no arquivo privado `.local/mp-webhook-url.txt`. Configuração do callback/segredo solicitada novamente com a URL nova; não há confirmação de conclusão no portal.

- **Evidência atual às 19:22:** o simulador oficial retornou HTTP 200 para `ORDTST01M4KX9JTTE8989ES87Q9XCMAH`. O backend validou HMAC, fez GET oficial e confirmou R$ 5,93, ignorando valor/referência fictícios do corpo de simulação. PostgreSQL: uma reserva, um recebimento financeiro. Interface “Reserva confirmada” conferida em desktop/mobile. Worker não estava iniciado: essa confirmação veio do callback. A entrega espontânea não foi comprovada.
- Correções de assinatura (formato normalizado e caixa original validada por HMAC), CSP com nonce por documento e referência externa: **13 unitários + 45 integrações = 58 testes selecionados**, `verify` aprovado às 19:19:16. Jornada de navegador com SDK/upstream sintéticos passou em **80,8 s**, incluindo timeout de inicialização e rejeição de callback tardio. Evidências amplas anteriores precedem essas alterações.
- Formulário oficial de cartão abriu com três secure fields e sem violações CSP; tokenização/cobrança ainda não confirmadas. Repetições automatizadas anteriores não enviaram POST de cartão ao backend.

- Às **19:33**, mensal oficial (R$ 47,95) confirmada por recuperação/conciliação do worker: 18 reservas, um contrato e um recebimento. GET conferiu identidade/valor e UI confirmou. Primeira resposta do checkout foi 422; comando durável recuperado. Devolução integral oficial do Pix (R$ 5,93) via cancelamento sintético/transferência também comprovada por GET e carteira; refund `REF01M4KZ4FVPYR6XP4SVS76PP1ZY`, saldo em processamento zero, ledger global zero.
- A regressão ampla das 19:28 encontrou CSP global sobrescrevendo a política restrita de downloads. Correção aplicada preservando header existente; nova verificação pendente. Contrato 3DS também corrigido após tokenização oficial revelar campo obrigatório `liability_shift` ausente.

- **Estado às 19:49:** cartão aprovado (3DS APRO-AUTH), GET/banco/uma reserva/um recebimento/token apagado/UI comprovados; recusa OTHE-NAUT recuperada pelo contrato HTTP 402/data e confirmada por GET, sem reserva/recebimento. Callback duplicado da order de cartão foi solicitado ao usuário e continua sem resposta/prova. Challenge APRO-CHOK exibiu erro da página do provedor e não foi concluído.
- Regressão ampla **426 testes** passou às 19:46:05 após corrigir CSP/3DS. Após descobrir e corrigir o contrato HTTP 402, **53 testes selecionados** passaram às 19:48:07. O último ajuste não foi seguido de nova execução ampla completa; demais fontes Java permaneceram iguais.

## Fontes de implementação e verificação

- [Fluxo de cobrança e confirmação](../backend-java/src/main/java/com/unicarona/payment/service/PaymentTransactions.java), [confirmação e reservas](../backend-java/src/main/java/com/unicarona/payment/service/PaymentConfirmation.java) e [resumo de valores](../backend-java/src/main/java/com/unicarona/payment/repository/PaymentReadRepository.java).
- [Cliente Orders](../backend-java/src/main/java/com/unicarona/payment/provider/MercadoPagoOrdersClient.java), [assinatura](../backend-java/src/main/java/com/unicarona/payment/provider/MercadoPagoSignature.java), [mapeamento de estados](../backend-java/src/main/java/com/unicarona/payment/provider/MercadoPagoStatus.java) e [processamento verificado](../backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoTransactions.java).
- [Devoluções e ledger](../backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoRefundTransactions.java), [envio/recuperação](../backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoRefundService.java) e [investigação administrativa](../backend-java/src/main/java/com/unicarona/payment/service/MercadoPagoReviewService.java).
- [Configuração privada](../backend-java/src/main/java/com/unicarona/payment/config/MercadoPagoSettings.java), [filtro de autenticação/CSRF/origem](../backend-java/src/main/java/com/unicarona/security/ApiSecurityFilter.java), [regras e CSP](../backend-java/src/main/java/com/unicarona/security/SecurityConfiguration.java) e [exemplo de ambiente](../backend-java/.env.example).
- [Brick no frontend](../dist/mercado-pago.js), [telas e estados](../dist/backend-ui.js) e [jornada de navegador](../backend-java/e2e/mercado-pago.test.ts).
- [40 testes HTTP de Orders](../backend-java/src/test/java/com/unicarona/payment/provider/MercadoPagoHttpIT.java), [pagamentos existentes](../backend-java/src/test/java/com/unicarona/payment/PaymentHttpIT.java), [transferências](../backend-java/src/test/java/com/unicarona/payment/TransferHttpIT.java) e [compatibilidade de migrations](../backend-java/src/test/java/com/unicarona/config/FlywayCompatibilityIT.java).

## Requisito por requisito — revisão de 15/10, 20:35

As evidências datadas acima preservam o histórico. Esta matriz e os relatórios de [dívida](MercadoPago_Regularizacao_Dividas.md), [3DS](MercadoPago_3DS_Homologacao.md) e [carência](MercadoPago_Carencia_Homologacao.md) descrevem o estado atual.

| Nº | Requisito | Estado e evidência | Limite ou trabalho restante |
| --- | --- | --- | --- |
| 1 | Arquitetura e negócio | Adapter Java integrado a aceites, reservas, parcelas, contratos, identidade e ledger; decisão e fontes na entrega técnica. | Contestações fora do crédito de cancelamento ficam em análise administrativa conforme escolha do usuário. |
| 2 | Solução escolhida | Orders, Pix e Card Payment Brick; mensal manual com nova validação da agenda. Comparação das alternativas no relatório de integração. | Validar tarifas e condições comerciais produtivas. |
| 3 | Credenciais privadas | Backend e arquivos privados ignorados; frontend recebe somente configuração pública. | Provisionamento e rotação no ambiente produtivo. |
| 4 | Cadeia do pagamento | Orders oficial → callback assinado → GET → PostgreSQL → reserva/contrato → interface comprovados. | Não equivale a liberação de produção. |
| 5 | Valores do servidor | Preço, comissão e desconto calculados/congelados no backend; alteração de preço rejeitada antes do comando externo. | Manter contratos estritos em novas rotas. |
| 6 | Modelagem | Pagamentos, orders, comandos, eventos, devoluções, avaliações de dívida e regularizações relacionados e versionados. | Nenhuma alteração manual de schema no sandbox. |
| 7 | Estados internos | Mapeamento centralizado; recebimento imutável; aprovação, recusa, expiração e devoluções observadas oficialmente. Contestação exige identidade e concordância da transação antes de efeitos financeiros. | Challenge aprovado foi exercitado interativamente; recusa interativa específica é QA adicional, distinta das recusas já comprovadas. |
| 8 | Webhook | HMAC/timestamp, GET autenticado e correspondência de referência/valor/aplicação; entrega espontânea da aplicação correta comprovada. | Callback produtivo e monitoramento precisam de infraestrutura própria. |
| 9 | Idempotência | Comandos duráveis, identidade estável e guards de banco; callbacks repetidos preservam uma reserva/recebimento e uma regularização. Serialização estável e compatibilidade com hashes antigos; replay nativo após reinício preservou a resolução e o ajuste únicos. | Manter recuperação de comandos incertos com a identidade original. |
| 10 | Referência externa | Referência interna inequívoca, validada no GET; regularização de dívida possui rota própria. | Não confiar em referência apenas declarada pelo evento. |
| 11 | Avulsa | Pix/cartão oficiais, reserva, cancelamento, crédito, devolução e aprovação tardia exercitados. | Não reservar vaga definitivamente antes de aprovação verificada. |
| 12 | Mensalidades | Mensal, renovação, encerramento ligado, carência, expiração e reativação exercitados; período pago preservado. | A fronteira de 72h foi controlada somente na manutenção de testes; não houve espera real de 72h. |
| 13 | Pix | QR/copia e cola, pendência, prazo, confirmação automática e Pix de regularização; callbacks espontâneos oficiais comprovados. | Não usar o simulador ou QR aberto como prova de quitação. |
| 14 | Cartão oficial | Brick tokenizado, comando cifrado e apagado após identificação; aprovação, recusa, saldo insuficiente e 3DS interativo autenticado comprovados. | Cobertura do Challenge recusado e aparelhos reais pode ser ampliada; nunca tratar COMPLETE como aprovação. |
| 15 | Motoristas | Alternativa permitida no objetivo: bruto, comissão, líquido e saldo devido no ledger; repasse indisponível é recusado antes de débito. | Não há Pix automático ao motorista nem split habilitado. Definir e habilitar operação real antes de oferecer saque. |
| 16 | Taxa | Cálculo congelado no backend e apresentado no resumo; sem cobrança duplicada no navegador. | Comissão contratual não representa lucro após tarifas externas. |
| 17 | Cancelamentos | Avulsa, mensal, períodos renovados e direitos futuros tratados no servidor; histórico consumido preservado. | Comandos de cancelamento externo incertos continuam sujeitos à conciliação. |
| 18 | Devoluções | Integral/parcial/lote e recuperação oficial comprovados; dívida de crédito gasto, Pix de regularização e resolução administrativa com ajuste único implementados. | Perdas de caronas consumidas permanecem em análise para decisão humana, sem destinação automática, conforme regra confirmada. |
| 19 | Interface | SDK/QR, resumo, carteira/dívida e resultados conferidos; carência tardia e reativação em desktop/390×844 sem overflow. | Dispositivos reais e acessibilidade assistiva precisam de verificação própria. |
| 20 | Resultado confiável | Backend determina o estado; query de sucesso e mensagem do iframe não confirmam pagamento. | Distinguir aprovação de pagamento de disponibilidade da reserva. |
| 21 | Migrations | V25 aplicada/validada no sandbox; relações, índices, histórico e guards. Backup/restauração de 52 tabelas/1.939 registros conferidos. | Recuperação de infraestrutura e chaves produtivas depende do deployment externo. |
| 22 | Logs | UUID/código e evidências sanitizadas; payloads sensíveis não usados como relatório público. | Política produtiva de acesso e retenção. |
| 23 | Recuperação | Timeouts, perda de resposta, inconsistência, duplicação e aprovação tardia tratados; renovação tardia gera crédito sem overbooking. | Casos ambíguos de conciliação seguem bloqueados até análise. |
| 24 | Segurança | Ownership, sessão, papel admin, Origin/CSRF, limites, contratos, HMAC e CSP cobertos por testes. | Auditoria do deployment, proxy, segredos e monitoramento produtivos. |
| 25 | Cenários mínimos | Dez cenários solicitados cobertos em HTTP/PostgreSQL com upstream explícito; evidência externa documentada separadamente. | Não confundir testes simulados com operação oficial. |
| 26 | Sandbox real | Test seller/aplicação correta; Pix, cartão/3DS, callback espontâneo, devoluções, dívida, mensalidade e conciliação pela interface comprovados. Conferência final do banco sem recebimento duplicado e ledger zero. | Carência usou relógio de manutenção controlado; não houve espera real de 72h, aparelhos físicos ou cobrança produtiva. |
| 27 | Preparação de produção | Ambientes explícitos, exemplos separados, HTTPS/gate test_user, segredos privados, worker, monitoramento e backup documentados. | Ativação comercial, domínio/infraestrutura, credenciais e meio real de repasse exigem configuração externa. Não estão ativados. |
| 28 | Preservação/regressão | 205 testes distintos em conjuntos complementares: regressão ampla de 198, dois casos adicionais e cinco verificações de contratos antigos. Última execução focada: 31 unitários + 60 HTTP Mercado Pago + 5 CompatibilityAuditIT, todos aprovados. | Não apresentar execuções repetidas como testes novos nem os testes protocolados como operações oficiais. |
| 29 | Auditoria final | Identidade/valor/versão antes dos efeitos, histórico de refunds, direitos mensais, dívida líquida, estornos cumulativos, concorrência entre administradores, segurança e UX conferidos/corrigidos. Inspeção final somente de leitura preservou os invariantes no runtime. | Auditoria do deployment produtivo é externa; não há promessa de ausência universal de falhas ou habilitação comercial por flag local. |
| 30 | Entrega end-to-end | Cadeia externa funcional comprovada, interface conectada e entrega técnica com arquitetura, arquivos, endpoints, tabelas, métodos, devoluções, testes e configuração. | Entrega do software em sandbox; ativação produtiva e transferências reais dependem dos requisitos externos abaixo. |

## Dependências externas para ativação produtiva

1. **Operação comercial e repasse.** O saldo devido/status estão implementados conforme a alternativa autorizada. Split/Pix automático ao motorista não estão habilitados; validar capacidade real, custos e operação antes de oferecê-los. Flag local não concede capacidade ao provedor.
2. **Infraestrutura e credenciais produtivas.** Domínio HTTPS estável, aplicação/segredo corretos, banco/chaves separados, monitoramento, backups com recuperação das chaves e responsáveis. Os exemplos estão em `config/`; não copiar credenciais de teste.
3. **Operação humana.** Casos de caronas consumidas sem crédito de cancelamento permanecem em análise administrativa por decisão explícita do usuário. Nenhum débito do motorista, dívida do passageiro ou perda da plataforma é presumido. A conclusão com ajuste de crédito de cancelamento tem procedimento, evidência e histórico implementados.

Webhook espontâneo, dívida/regularização, Challenge 3DS aprovado e conciliação de crédito não são pendências de implementação. A carência foi exercitada com provedor oficial e relógio de manutenção controlado, com limites documentados. A entrega não ativa cobranças reais ou repasse automático.

## Como repetir os ensaios externos e produzir evidência adicional

Seguir [MercadoPago_Operacao.md](MercadoPago_Operacao.md), usando `mercadopago-sandbox-local.ps1` e o banco dedicado **unicarona_mercadopago_sandbox**. Não utilizar o banco descartável dos testes, que é apagado pelas suítes automáticas. O túnel expõe somente o webhook; app/API ficam locais.

Para cada cenário, registrar UUID interno, ID da order, referência externa, método/valor, horários, estado verificado, recibo/ledger, reserva/contrato e resultado na UI. Registrar origem/HMAC do evento sem guardar segredo ou payload pessoal. Um ID criado por fixture local não serve como operação oficial nem como avaliação de qualidade Mercado Pago.

A sequência final é: criação real por Pix/cartão → processamento de teste oficial → entrega HTTPS → assinatura válida → GET autenticado → aplicação transacional → reserva/contrato/ledger → UI. Depois conferir repetição, notificação ausente, recusa, expiração, cancelamento e devoluções. Atualizar este checklist com evidência concreta; não mudar o critério de conclusão para acomodar os testes já realizados.
