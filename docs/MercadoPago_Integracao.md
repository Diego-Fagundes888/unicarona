# Integração Mercado Pago — arquitetura e histórico da entrega

**Configuração local atual, 17/10:** as referências históricas ao `.env` privado
devem ser lidas como o arquivo efetivo em `%LOCALAPPDATA%/UniCarona/Private/config/`.
`app.env` contém a configuração principal e `mp-sandbox.env` a do sandbox;
os helpers preservam a sobreposição do segredo oficial de webhook e de SMTP.
O `.env` da raiz é somente um ponteiro. A demonstração continua no jar V26,
sem novas cobranças ou publicação da camada de privacidade nesta migração.
[Armazenamento e recuperação](privacy/Armazenamento_Backup_Local.md).

## Entrega técnica — 15/10/2026, 20:35

[Integração funcional, evidências, arquivos, endpoints e configuração](MercadoPago_Entrega_Integracao.md). Pix, Card Payment Brick/3DS, mensalidades, devoluções, dívida/regularização e conciliação administrativa foram implementados e exercitados. A alternativa de saldo devido ao motorista está implementada; não há Pix automático/split habilitado. Contestações de caronas consumidas sem crédito de cancelamento ficam em análise administrativa conforme decisão do usuário. As fases abaixo preservam a evolução; não representam novas pendências de implementação.

## Estado atual — 15/10/2026, 14:54

O [3DS interativo](MercadoPago_3DS_Homologacao.md) e o [ciclo mensal com carência controlada](MercadoPago_Carencia_Homologacao.md) foram exercitados com o provedor oficial. Renovação, expiração, crédito tardio sem reserva e nova contratação confirmada; 95 testes distintos aprovados nesta etapa. A regra de dívida e Pix de regularização já estão implementados. Permanecem análises financeiras e requisitos operacionais/comerciais de produção; as seções datadas abaixo são históricas.

## Atualização — 15/10/2026, 14:36

O Challenge 3DS oficial foi concluído interativamente e o GET confirmou `authenticated` e `processed/accredited`. Uma reserva e um recebimento; callback posterior válido sem duplicação. [Relatório do ensaio](MercadoPago_3DS_Homologacao.md). As pendências de 3DS nas seções datadas abaixo são históricas. Carência mensal externa e condições operacionais/financeiras de produção permanecem verificações independentes.

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

Objetivo: cobrança avulsa/mensal → Orders API → webhook autenticado → consulta da order → transação financeira/reserva → interface. A conclusão exige evidência externa de sandbox; testes com servidor falso não a substituem.

Atualização de **15/10, 12:33**: a sessão MCP OAuth passou a administrar a aplicação principal e confirmou a atualização do callback de sandbox; as credenciais de teste correspondem às efetivas do backend. A aplicação automática **4063897889583204** continua exigindo conferência separada de acesso/configuração. Novo Pix oficial de R$ 5,93 (`ORDTST01M502SZT8ZJATPMXHZHZ40R90`) foi observado pendente no banco com worker parado, apesar do GET aprovado; sem callback observado. Após restaurar o worker, houve uma reserva, um recebimento e UI confirmada, com ledger zero. Essa recuperação veio da conciliação, não comprova webhook espontâneo. Backup/restauração conferiu 46 tabelas/1.074 registros, V22. A regressão combinada de **445 testes de 13/10**, com correção/reexecução isolada do inventário, está descrita no [checklist atual](MercadoPago_Checklist_Lancamento.md); não foi repetida sem alteração de fonte. Ver [homologação e pendências atuais](MercadoPago_Homologacao_Pendencias.md) antes dos registros históricos abaixo.

Atualização de **10/10, 23:01**: renovação antecipada/encerramento corrigidos na V22 e comprovados nos dois períodos oficiais; 43 reservas futuras canceladas, R$ 114,55 creditados e lote de devolução de R$ 116,21 confirmado por GET oficial. A leitura de histórico completo de refunds foi corrigida após reprodução real, recuperando o mesmo comando. Replay sem duplicação, um recebimento original por pagamento, processamento zero, ledger zero e UI desktop/mobile conferidos. **101 testes selecionados** passaram às 22:59:25. Backup/restauração V22 aprovados às 23:01:10: 46 tabelas, 977 linhas, esquema/tabelas correspondentes e ledger zero. Continuam pendentes notificação espontânea autenticada, Challenge interativo e decisões financeiras/comerciais de produção.

Atualização de 10/10, 21:36: callbacks do simulador oficial para cartão e renovação mensal validados por assinatura e GET, com HTTP 200; a repetição do cartão preservou uma reserva e um recebimento, e a renovação preservou 26 reservas e um recebimento. O timeout anterior não comprova entrega e sua causa não foi determinada. Entrega espontânea autenticada, Challenge interativo e os demais gates comerciais/operacionais continuam pendentes; lançamento ainda não aprovado.

Device ID: o navegador utiliza o coletor do SDK e, quando o identificador ainda não existe, carrega o coletor oficial `security.js` com o nonce CSP existente. O identificador opcional é validado e congelado junto ao comando cifrado, enviado somente no header `X-meli-session-id` e apagado ao concluir o comando. Não é registrado em logs ou respostas. Comandos antigos continuam válidos, sem alterar seu payload ou chave de idempotência. Seleção após esse ajuste: 17 unitários + 41 integrações = 58, aprovada às 20:35; jornada de navegador com SDK/upstream sintéticos aprovada em 80,9 segundos. Cartão oficial com Device ID confirmado por GET/banco/UI às 21:32–21:33, com recuperação da primeira resposta 503 pelo worker. Referência: [recomendações de aprovação em Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/improve-payment-approval/recommendations).

O cliente agora preserva comandos ao receber 423 `resource_locked`. Nas recusas, usa o motivo conhecido da transação consultada por GET quando a order informa apenas `failed`; motivo desconhecido conserva a resposta genérica. A UI distingue saldo/limite insuficiente, valor acima do limite, dados inválidos e autenticação expirada. O estado financeiro continua derivado da order verificada. Referências: [erros de Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/integration-errors/api) e [status da transação](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/status/transaction-status).

A correção posterior distingue reserva expirada de cobrança ainda pendente e mantém o acompanhamento de uma aprovação tardia que gera crédito. Jornada com fixtures aprovada em 90,1 s às 21:50; pacote/frontend conferidos e API/worker atualizados às 21:52, com readiness e hash do Jar correspondentes. O cenário oficial CONT ainda em processamento foi conferido em desktop/mobile; o render com gateway ausente também foi exercitado com configuração de desenvolvimento explicitamente simulada. Notificação fora das simulações indicou a aplicação de teste `4063897889583204` e teve HMAC incompatível com o segredo atual, que valida as simulações de `5555811490405924`. Conferência do segredo correspondente solicitada; o corpo sem autenticação não é usado como evidência financeira. Ver [configuração de notificações](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/notifications).

## Correções da auditoria financeira

### Renovação antecipada: direitos pagos preservados (V22)

A auditoria reproduziu uma falha: pagar o próximo mês marcava o período anterior como `renewed`, mas suas viagens pagas continuavam futuras. Encerrar somente o período novo deixava reservas e parcelas antigas abertas. A V22 permite `renewed → ended/interrupted` e cria `subscriptions_with_open_rights`, incluindo períodos renovados com reservas confirmadas em viagens futuras, segundo o fuso do campus. Não permite reativar um período renovado.

O encerramento pelo motorista percorre períodos ligados da mesma dupla e rotina, sob os locks existentes, cancela pedidos de renovação ainda não pagos e fecha somente direitos futuros elegíveis. Bloqueio pessoal, alteração acadêmica e suspensão administrativa também incluem esses direitos antigos, preservando a distinção entre crédito e valores em análise. Viagens passadas, iniciadas ou já consumidas continuam preservadas.

`GET /api/v1/subscriptions/{id}/rights` informa `openRights` somente aos participantes. O DTO original do contrato permanece igual; a UI consulta o endpoint separado para períodos renovados, explica o alcance do encerramento e conserva acesso de leitura para contas suspensas. Ownership, autenticação, query estrita e proibição de mutação por conta suspensa foram exercitados por HTTP/PostgreSQL.

Ensaio oficial de 10/10, 22:52: contratos `8320ba22-cce1-4bf0-b1ab-c1513d43a124` e `9aa81c96-58e5-42bb-987d-2ca7d081dfd3`, pagos anteriormente no Mercado Pago. Encerramento pela API cancelou **43 viagens futuras** (17 + 26), creditou **R$ 114,55**, preservou um recebimento por pagamento e manteve o ledger global em zero. Replay retornou 200 sem alterar dinheiro ou reservas. Os 44 registros históricos permanecem, incluindo um cancelamento anterior. UI antes/depois conferida para motorista e passageiro, desktop/mobile, sem overflow ou erro JavaScript. Não foi usada fixture de pagamento nesse ensaio.

### Histórico de devoluções parciais

O lote de devolução posterior encontrou outra incompatibilidade real: a resposta de refund da order pode trazer o histórico completo. O cliente anterior exigia exatamente um refund e mantinha a segunda devolução pendente mesmo após o provedor processá-la. A leitura agora exige exatamente um resultado compatível com transação/valor, excluindo somente referências imutáveis já vinculadas a outros comandos do mesmo pagamento/proprietário. Identidades inválidas, dados históricos malformados e resultados ambíguos continuam rejeitados; não se escolhe o primeiro item da lista. A recuperação reutiliza o UUID e o corpo originais, seguida de GET verificado. Referência: [API oficial de devolução de Orders](https://www.mercadopago.com.br/developers/en/reference/online-payments/checkout-api/refund-order/post).

Verificação conjunta em 10/10 às **22:59:25**: **19 unitários + 82 integrações = 101**, zero falhas/erros/skips. Inclui cliente/assinatura, 42 cenários Orders, 33 de contrato e sete de compatibilidade Flyway. O teste de devoluções sucessivas com perda de resposta reproduziu `REFUND_MISMATCH` antes da correção e passou depois, com três devoluções distintas, recebimento original preservado e ledger zero. O upstream dessas integrações é uma fixture local explícita; a evidência oficial continua registrada separadamente. Comando: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest' '-Dit.test=MercadoPagoHttpIT,ContractHttpIT,FlywayCompatibilityIT' verify`.

Ensaio oficial recuperado às **23:00**: lote `988f6603-5294-4da1-91bd-e2757ec96041`, R$ 116,21 (inclui o crédito anterior de R$ 1,66). Refunds `REF01M4MAMFN6BVMS7RQMG421CNRH` (R$ 46,95) e `REF01M4MAMH38CEZEG1GTDMKK20R5` (R$ 69,26), ambos `processed` no GET oficial. Os dois pagamentos mantêm estado interno `approved` e um receipt cada; o ciclo externo é `REFUNDED`, sem reservas confirmadas. Carteira: crédito/processamento zero, R$ 123,14 devolvidos no acumulado dos ensaios e R$ 11,86 de parcelas futuras de outras caronas preservadas. Replay do lote retorna o mesmo ID, sem refund novo. Recuperação veio do worker/GET, não de webhook espontâneo autenticado.

A conferência visual encontrou data incorreta na carteira após 21h: timestamps UTC estavam sendo formatados como datas civis, mostrando 11/10 para a devolução criada em 10/10 às 22:53 de São Paulo. Carteira, avisos e fila administrativa agora utilizam `UI.dateTime`, já existente com fuso explícito. As datas civis da agenda não foram alteradas. Sintaxe JavaScript/package passaram; API/worker iniciaram o mesmo Jar e readiness às 23:02:58. UI nativa da carteira e replay foram conferidos às 23:03 em desktop/mobile com navegador em UTC, mostrando a data/hora corretas e sem overflow/erros. O verify de 101 antecede somente essas três chamadas de formatação e a atualização do cache; não houve alteração posterior de Java financeiro.

Na auditoria da V22 também passaram os cenários de segurança pessoal (26), alteração de perfil (14) e administração de contas (14), na execução de 22:21. Essa execução conjunta tinha quatro falhas em contratos/compatibilidade, posteriormente corrigidas: contagens novas de migration/índices/função e alteração indevida do DTO original. O endpoint de direitos ainda precisou de matcher autenticado explícito e schema de query estrito; todos os 33 casos de contrato passaram na execução final acima. Não apresentar a execução intermediária como um `verify` aprovado nem somá-la como uma única execução ampla de todo o projeto.

## Decisão de arquitetura

Checkout Transparente via Orders API, Pix e Card Payment oficial com tokenização. A aplicação existente foi criada para Orders. Checkout Pro acrescentaria redirecionamento; Bricks/SDK oferecem coleta oficial de cartão. Boleto não atende a confirmação rápida de vaga. A mensalidade é um contrato de viagens com agenda/preço congelados: cobrança manual por período, preservando aceite, carência de 72h, cancelamento de renovação e reativação existentes. Não transformar o plano em assinatura automática com cobranças sem revalidar vagas.

O backend calcula preço, comissão e desconto. A API externa recebe valor inteiro convertido para representação decimal exata e `UNICARONA_{paymentId}`. A referência usa apenas letras, números, hífens e sublinhado, como exige a Orders API. A mesma cobrança usa a mesma chave de idempotência externa.

O recebimento original e seu ledger permanecem imutáveis. `mercado_pago_orders` mantém o ciclo externo, QR e estado verificado separadamente. Migrations novas não reescrevem V1–V13. A validade da reserva continua sendo determinada internamente: um Pix pode permanecer pagável por pelo menos 30 minutos no provedor, mas um recebimento posterior à validade interna gera crédito integral, sem overbooking.

## Repasse: limite explícito

O split padrão divide automaticamente os recursos. Não comprova bloqueio e liberação parcial por viagem da mensalidade. Esta implementação não ativa split, não promete custódia pelo Mercado Pago e não simula Pix de saída. O ledger registra bruto/comissão/líquido/saldo devido. Um adapter indisponível preserva a obrigação sem confirmar repasse externo. Produção exige validação comercial da operação financeira e habilitação de meios reais de repasse.

## Referências oficiais consultadas em 09/10/2026

- [Pix via Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-integration/websites/pix)
- [Cartões via Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-integration/websites/cards)
- [Notificações de Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/notifications)
- [Split padrão](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/integrate-marketplace)

## Configuração privada

`PAYMENT_PROVIDER=mercadopago`, `MERCADO_PAGO_ENVIRONMENT=test`, `MERCADO_PAGO_ACCESS_TOKEN`, `MERCADO_PAGO_PUBLIC_KEY`, `MERCADO_PAGO_WEBHOOK_SECRET`, `APP_ORIGIN`. Access token e segredo nunca são expostos pela API, logs ou navegador. O prefixo `APP_USR` não distingue teste de produção. Verificar identidade e ambiente antes de ensaios externos.

Webhook: `POST /api/v1/webhooks/mercadopago?data.id={orderId}&type=order`. Ativar tópico Order na aplicação correta e configurar URL HTTPS pública. HMAC usa data.id em minúsculas, x-request-id e ts (segundos ou milissegundos); janela de cinco minutos. Consultar GET `/v1/orders/{id}` antes de atualizar negócio, comparar referência/valor/identidade. Não confiar em status do corpo ou query de retorno.

## Gates ainda pendentes

Atualização externa de 10/10, **23:11**: novo `APRO-CHOK` com Device ID gerou a order `ORDTST01M4MBFY8ES3BYJPQ30V7KXSPV`. Após 503 inicial, worker recuperou a mesma cobrança; GET autenticado confirmou R$ 5,93, referência, `pending_challenge` e **aplicativo 4063897889583204**. Comando concluído/token apagado, zero reservas/recebimentos. Iframe respondeu 403, sem Confirmar e sem violação CSP. Os dois callbacks recebidos nesse ensaio ainda falharam HMAC; corpos não são evidência financeira. Essa prova não conclui o Challenge, mas mostra que acrescentar Device ID não resolveu sua abertura. Orientação de configuração e relatório sanitizado em [MercadoPago_Homologacao_Pendencias.md](MercadoPago_Homologacao_Pendencias.md). Fonte/pacote financeiro não mudaram neste ensaio.

1. Fechar os checks pertinentes à última correção e a auditoria final sobre o deployment. A regressão ampla de 426 casos precede os ajustes seguintes; as seleções e jornadas posteriores são evidências separadas.
2. Homologar entrega espontânea autenticada e Challenge interativo. Os callbacks manuais de Pix, cartão e renovação já foram comprovados; consulta pelo worker não substitui a evidência de entrega automática.
3. Resolver os cancelamentos externos de Challenge que responderam 422 e o iframe oficial que respondeu 403, sem forjar estados ou desabilitar a autenticação.
4. Definir a resolução operacional de devoluções desconhecidas/contestações com saldo local insuficiente, sem inventar política de dívida ou débito.
5. Validar a operação comercial de recebimentos/obrigações dos motoristas, infraestrutura produtiva, chaves, monitoramento e restauração. O backup/restauração do sandbox foi aprovado, mas não prova recuperação produtiva.

Não há autorização de operação comercial neste estágio.

## Evidências desta etapa

- A credencial local foi consultada sem exposição: `/users/me` autenticado, Brasil e tag `test_user`. Nenhuma cobrança foi criada nesta consulta.
- Build Java com o novo adapter: aprovado.
- 16 unitários de cliente Orders, assinatura e compatibilidade dos adapters existentes: aprovados, sem skips.
- Quatro jornadas HTTP com PostgreSQL 18 e upstream local explícito: aprovadas, sem skips. Cobrem Pix pendente/aprovado, duplicação, status forjado no corpo, assinatura inválida, valor externo divergente, adulteração de preço, ownership, recusa e expiração. Não representam um teste externo do Mercado Pago.
- A migration V14 foi aplicada a banco descartável de testes; migrations V1–V13 foram preservadas.
- Verificação conjunta final desta etapa: 16 unitários + 29 testes de integração, sem falhas ou skips. Os 29 incluem seis de compatibilidade Flyway, 19 de pagamento existente e quatro do novo fluxo Orders com upstream local. Comando: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest,PaymentProviderTest,WebhookAuthenticationTest' '-Dit.test=MercadoPagoHttpIT,PaymentHttpIT,FlywayCompatibilityIT' verify`.

## Auditoria de escopo do objetivo

| Requisitos do objetivo | Situação e evidência |
|---|---|
| 1–2: análise e arquitetura | Arquitetura Java/HTML, aceites, mensalidades, ledger e políticas lidas; decisão Orders acima. |
| 3: credenciais | Variáveis privadas, exemplo sem valores, identidade test_user verificada e guard no cliente antes de operações; nenhuma credencial nova versionada. |
| 4–10: cobrança, valor, estados, webhook, idempotência e referência | Fluxo Pix/backend, comandos duráveis e conciliação periódica implementados e testados em HTTP/PostgreSQL com upstream local. Falta ciclo externo completo. |
| 11–12: avulsa e mensal | Confirmação existente reutilizada; ensaios novos de mensal pelo adapter passaram: desconto/agenda congelados, período pago preservado ao cancelar renovação, carência e retomada após recusa. Histórico anterior da carência é fixture sintética. Falta processamento externo. |
| 13: Pix | Cliente Orders, QR/copia e cola e estados integrados à UI e testados localmente. Falta processamento externo. |
| 14: cartão | Endpoint, Brick oficial, comando criptografado, outbox e recuperação implementados; quatro novos casos HTTP e recuperação no navegador passaram. SDK e serviço externo foram simulados; validação externa pendente. |
| 15–16: repasses e taxas | Ledger existente preservado; repasse indisponível explicitamente. UI e API impedem solicitar saque no provedor Mercado Pago. Revisão final de obrigações pendente. |
| 17–18: cancelamento e reembolso | Comandos duráveis, confirmação por GET e lote de devoluções com ledger implementados; integral, parcial, falha parcial e resposta perdida testados localmente. Falta homologação oficial e auditoria dos demais casos. |
| 19–20: interface e resultado | QR/copia e cola, resumo calculado no servidor, Brick e estados verificados integrados. Query de retorno não aprova pagamento. Desktop/mobile/tablet verificados com upstream/SDK explícitos de teste. |
| 21: migrations | V14–V21 passaram em banco novo e migração de histórico legado, incluindo backfill de devoluções verificadas, tentativas já iniciadas e contestação já registrada. V18 amplia explicitamente o guard financeiro/status de transferências, mantendo as regras anteriores e acrescentando confirmação do lote de devoluções. |
| 22–24: logs, erros e segurança | Logs mínimos, HMAC e ownership testados. Revisão administrativa exige admin, motivo, CSRF e idempotência; DTOs não expõem dados de cartão ou credenciais. Falta revisão completa e homologação oficial incluindo cartão/reembolso/reconciliação. |
| 25: cenários mínimos | Aprovado, recusado, pendente, duplicado, Pix, expiração, cancelamento, reembolso, preço e ownership cobertos localmente. Cobertura local não prova comportamento do serviço oficial. |
| 26: sandbox real | Não executado. Scripts do túnel HTTPS restrito ao webhook preparados; processos anteriores encerraram após reinicialização em 10/10. Depende de segredo oficial e nova verificação do callback/túnel. |
| 27–28: produção e regressões | Configuração separada, gate comercial e runbook presentes. O estado atual tem 424 resultados aprovados combinando regressão ampla e reexecução do seed; o comando amplo original teve dois timeouts de limpeza e exit 1, conforme décima etapa. Homologação externa e validação operacional de produção permanecem pendentes. |
| 29–30: auditoria final e conclusão | Pendente. Não declarar integração completa com base nestes testes. |

Próximo trabalho: SDK e sandbox HTTPS reais; auditoria de concorrência/recuperação, tratamento operacional de alterações externas e revisão final. O callback/segredo oficial de webhook ainda precisam ser configurados para o ensaio externo.

## Cartão e interface — segunda etapa

- `POST /api/v1/payments/{id}/card` recebe somente token oficial e metadados mínimos. Campos desconhecidos, valores, PAN e CVV são rejeitados; preço/e-mail vêm da cobrança/usuário do banco.
- A migration V15 guarda o comando criptografado antes da chamada externa. O outbox retoma com a mesma chave externa `{paymentId}`. A identificação da order apaga o token, inclusive se a análise financeira ainda estiver pendente. Resposta perdida/timeout não gera segunda cobrança nem segundo token.
- Se o provedor recusar a requisição definitivamente, o comando fica `failed` e apaga o token. Falhas de conexão, autenticação/configuração e conflito de idempotência preservam o comando para recuperação. Preserve a chave AES existente enquanto houver comandos pendentes.
- `GET /api/v1/payments/config` expõe apenas provedor, ambiente, Public Key e capacidades. Access Token e segredo HMAC nunca saem do servidor.
- `GET /api/v1/payments/{id}/summary` calcula subtotal, desconto congelado, comissão, líquido e total para o proprietário. A comissão já está incluída no total; não é somada novamente no navegador.
- `POST /api/v1/payments/{id}/sync` permite consultar o estado real da order ou retomar um envio pendente. Não aceita status/valor do navegador.
- 3DS usa a URL verificada de `payment_method.transaction_security.url`. O iframe e seu evento COMPLETE não aprovam pagamentos; origem/source são conferidos e o servidor é consultado novamente.
- A UI abre o SDK somente no cartão e descarta a instância ao sair. Pix mostra QR/copia e cola; copiar não muda status. Detalhes financeiros recolhem em telas pequenas, mantendo total e taxa visíveis. Estados terminalmente encerrados retiram o código Pix.
- A configuração Compose de desenvolvimento encaminha as variáveis Mercado Pago ao mesmo ambiente de API e worker. Continua sendo um ambiente local com seed; não é uma receita de produção.

### Evidência local da segunda etapa

10 unitários do cliente/assinatura e 14 integrações (oito HTTP Orders e seis Flyway) passaram sem skips. O navegador passou com Java/PostgreSQL reais, upstream e SDK simulados: Pix/clipboard, webhook/aprovação, cartão com resposta perdida e retomada, preço adulterado, expiração e layout em 1440/390/768px. As duas jornadas anteriores `failures` e `reservation` também passaram. Capturas em `docs/previews/mercadopago/`; QR sintético e valores de fixture, sem pagamento real.

Foi feita uma revisão visual em dois passes e uma execução do detector Impeccable. O detector listou avisos advisory em estilos preexistentes; não houve reescrita de tokens ou estilos fora do fluxo de pagamento. Leitor de tela, aparelhos físicos e SDK oficial remoto ainda não foram validados.

O ensaio local não prova homologação Mercado Pago. A integração permanece em andamento.

## Conciliação e mensalidades — terceira etapa

- O worker chama `MercadoPagoReconciliation` na manutenção periódica. A API continua recebendo webhooks assinados; o worker consulta Orders para recuperar notificações ausentes.
- A migration V16 acrescenta data da próxima consulta, lease com expiração, contador de falhas e código sanitizado. Claims usam `FOR UPDATE SKIP LOCKED`; a consulta de rede ocorre fora da transação. Um worker encerrado não prende a cobrança indefinidamente.
- A conclusão da consulta exige o mesmo token do lease; um worker atrasado não encerra um lease retomado por outro worker. Estados pendentes são consultados novamente após um minuto; demais estados a cada seis horas; reembolso integral sai da varredura. Falhas têm backoff de um minuto até uma hora. Cada chamada processa no máximo 20 itens e verifica a parada entre eles.
- GET externo deve manter order, UUID e valor correspondentes à cobrança. A aplicação reutiliza o recebimento/ledger/reserva idempotentes existentes; pagamento tardio não ocupa vaga expirada e gera crédito integral do valor externo.
- O fingerprint de idempotência do envio de cartão agora inclui o UUID do pagamento. Uma chave não pode ser reutilizada em outra cobrança, mesmo com os mesmos dados tokenizados. O cliente também valida o campo oficial `currency`, além de `currency_id`, quando presente.
- Testes mensais cobrem cobrança após aceite, desconto congelado e divisão em parcelas, duplicação de webhook, cancelamento de renovação preservando período pago e retomada após recusa dentro da carência. O contrato histórico do teste de carência é fixture, sem pagamento financeiro real.
- Os testes antigos que moviam uma viagem mensal para hoje agora trocam datas de fixtures em transação quando há colisão. A restrição de uma viagem por rotina/sentido/dia permanece ativa. A documentação Swagger continua preservando as 93 operações originais e acrescenta somente os novos endpoints comuns no provedor de desenvolvimento.

Evidência específica: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MaintenanceServiceTest' '-Dit.test=MercadoPagoHttpIT' verify` passou com 12 unitários + 15 integrações, sem falhas/skips. Os seis testes de compatibilidade Flyway também passaram na execução anterior com V16; uma consulta de fixture mensal estava com nome de tabela incorreto, foi corrigida e passou na execução acima.

Os seis grupos de regressão anteriormente falhos passaram com 76 unitários + 102 integrações. A regressão ampla com o estado final desta etapa terminou em 09/10/2026 às 14:28 (America/Sao_Paulo): `./backend-java/scripts/maven-local.ps1 -LocalDatabase verify`, **77 unitários + 321 integrações = 398 testes**, sem falhas, erros ou skips, build aprovado. Cobertura inclui autenticação, usuários, caronas, reservas, mensais, pagamentos, ledger, manutenção, worker, administração, arquivos e seed. Banco PostgreSQL real dedicado; Mercado Pago continua sendo upstream explícito de teste nesses cenários.

A conciliação atualiza o ciclo externo e confirma recebimentos. Na quarta etapa abaixo, passou a concluir os reembolsos rastreados pelo app. Reembolso desconhecido bloqueia o uso/devolução do crédito e registra auditoria; ainda não existe resolução administrativa completa para esse caso ou chargeback. Operação de produção permanece bloqueada pelos gates acima. Consulte também [operação e homologação](MercadoPago_Operacao.md).

### Itens identificados ao fim da terceira etapa

1. Ligar a solicitação de devolução de crédito ao reembolso oficial de Orders, com alocação rastreável ao recebimento original, reserva do saldo, identidade persistida antes do envio e confirmação externa. Preservar os triggers existentes de transferências/ledger; uma falha parcial ou resposta incerta não pode restaurar saldo já devolvido.
2. O endpoint oficial de reembolso parcial usa ID da transação e valor; o resultado fornece `transactions.refunds` com ID, transação, valor e estado. Validar essa identidade em consulta externa antes de concluir a devolução. Reembolso total tem corpo vazio. Respeitar a janela de 180 dias e disponibilidade financeira do vendedor. Referências: [gestão de reembolsos](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/refunds-cancellations) e [API de refund Orders](https://www.mercadopago.com.br/developers/en/reference/online-payments/checkout-api/refund-order/post).
3. Cancelar externamente orders ainda não pagas quando a solicitação interna for cancelada/expirar, usando um comando durável e a identidade original. Se o pagamento vencer a corrida com o cancelamento, aplicar recebimento/credito conforme disponibilidade, sem inventar sucesso do cancelamento.
4. Criação Pix deve ter recuperação por worker mesmo quando a resposta se perde antes de anexar a order. O cartão já tem comando/outbox para esse cenário.
5. Tarefas `mercadopago_card` aparecem atualmente como tipo desconhecido no painel administrativo; o retry administrativo aceita somente e-mail/transferência. O endpoint próprio `sync` recupera o comando de cartão, mas falta habilitar a retomada administrativa segura desse tipo sem expor payload.
6. Antes de produção, auditar transições de reembolso/contestação, notificações atrasadas fora de ordem, consulta de histórico financeiro por conta restrita, concorrência de reembolso versus uso do crédito, e resumo sempre visível do valor externo efetivamente a pagar quando há crédito.

Esse é o registro histórico da terceira etapa. Os itens 1–5 foram implementados localmente na quarta etapa; o item 6 e a homologação externa permanecem abertos.

## Pix, cancelamento e devolução — quarta etapa

- V17 acrescenta comando Pix com payload congelado e outbox. A resposta perdida é retomada mesmo sem order anexada; uma tentativa externa já iniciada conserva sua identidade após expiração interna. Comandos Pix/cartão nunca enviados são encerrados se a cobrança expirou ou o pedido foi cancelado. O token de cartão desses comandos é apagado.
- Cancelamento externo tem UUID persistido antes do POST, confirmação por GET e registro do resultado. Pagamento que vence a corrida com cancelamento segue as regras de crédito/validade existentes, sem reserva indevida.
- V18 liga devoluções às cobranças originais elegíveis, dentro de 180 dias e limitadas pelo crédito devido. O pedido reserva crédito antes de enviar operações. Cada operação tem UUID estável, motivo, responsável, valor, identidade externa e resultado verificado por GET. O recebimento original permanece imutável.
- Lotes só terminam após todas as operações externas estarem resolvidas. Sucesso parcial devolve apenas o valor comprovado e restaura somente a parte recusada. Resposta incerta mantém saldo reservado. Guards no banco impedem encerrar o lote sem correspondência entre operações e ledger.
- `GET /api/v1/transfers/{id}/refunds` verifica ownership e expõe solicitado/devolvido/restaurado/em processamento. A carteira apresenta esses valores e impede saque indisponível. Jobs Pix/cartão aparecem com nomes próprios e retry administrativo autorizado.
- A assinatura de evento idempotente usa fingerprint determinístico do snapshot verificado, incluindo devoluções ordenadas, sem dados de pagador. Uma mudança de estado de devolução não é escondida por uma notificação anterior da mesma order.

Evidência local em 09/10/2026: 10 unitários + 26 integrações Orders passaram, sem falhas/erros/skips. A seleção anterior de Orders/Flyway/pagamentos/jobs passou com 10 unitários + 65 integrações. A jornada de navegador passou com Java/PostgreSQL reais e SDK/upstream explícitos de teste: Pix, cartão com resposta perdida, adulteração, expiração e carteira de devolução parcial com valores conferidos individualmente; screenshots em `docs/previews/mercadopago/refund-partial-{desktop,mobile}.png`.

A regressão completa com V17/V18 e proteção de comandos nunca enviados terminou às 18:43 de 09/10/2026: `./backend-java/scripts/maven-local.ps1 -LocalDatabase verify`, **77 unitários + 332 integrações = 409 testes**, sem falhas, erros ou skips. A contagem 398 acima pertence ao estado anterior V16.

Após essa regressão, a auditoria encontrou e corrigiu a decisão de corpo vazio do reembolso integral: ela agora depende apenas dos valores imutáveis da operação e do recebimento. Um webhook que chega antes de recuperar a resposta perdida não transforma o reenvio em reembolso parcial com payload diferente. A seleção final `'-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest' '-Dit.test=MercadoPagoHttpIT' verify` passou às 18:44 com **10 unitários + 27 integrações**, incluindo essa corrida, sem falhas/erros/skips. Não foi executada outra suíte ampla após essa alteração pontual.

O sandbox oficial continua pendente. Identidade do vendedor foi novamente verificada como `test_user` brasileiro. Túnel HTTPS está restrito ao webhook, com outros caminhos retornando 404 e backend ausente retornando 503. `save_webhook` do MCP recusou a operação por ausência de propriedade OAuth; nenhuma configuração externa foi alterada por essa tentativa. Foi solicitada configuração do callback de teste e segredo oficial pelo portal, sem compartilhar segredo no chat. A tentativa de criar comprador de teste por MCP com a credencial conectada também foi recusada com `FORBIDDEN_FOR_TRIAL_ACCOUNTS`; isso não prova que a conta proprietária de produção esteja nessa condição. A pesquisa posterior confirmou que o Checkout API Web escolhido usa os dados de pagador de teste oficiais abaixo; criar outra conta não é requisito para esse ensaio de cartão.

## Histórico externo e carteira — quinta etapa

- V19 acrescenta `mercado_pago_observed_refunds`, preservando identidade, transação, valor e resultado externo verificados. Omitir uma devolução em GET posterior não apaga a evidência anterior nem libera crédito sem prova. Uma observação não terminal não desfaz uma devolução já concluída. Identidades não podem ser alteradas/apagadas no banco.
- A migração recupera apenas snapshots sanitizados já processados. Se havia bloqueio sem histórico verificável, `refund_history_incomplete` mantém a revisão. O novo teste de atualização V18→V19 inclui metadata anterior, resultado terminal seguido de observação pendente e notificação ainda não processada; esta última não é promovida a prova financeira. Fixtures de metadata não simulam pagamento real.
- O teste HTTP simultâneo de devolução e checkout com crédito comprovou que o mesmo saldo não é gasto duas vezes, mantendo reconciliação zero. Devolução externa desconhecida bloqueia uso/devolução, inclusive quando consulta posterior omite o histórico; eventos repetidos não duplicam a auditoria desse bloqueio.
- `GET /api/v1/wallet/refund-review` é autenticado e consulta exclusivamente o proprietário da sessão. Conta restrita mantém essa leitura financeira, como a carteira existente; outras permissões de pagamento não foram ampliadas.
- Carteira/checkout explicam crédito em análise antes da ação. A carteira acompanha mudanças a cada dez segundos enquanto visível e encerra o polling ao navegar. Falha de consulta do detalhamento possui recuperação explícita; não apresenta valores estimados como confirmação.

O estado anterior desta etapa passou com 10 unitários + 64 integrações (Orders/Flyway/pagamentos/transferências). Após o teste de backfill e ajustes de dados de sandbox de cartão, a seleção passou com 11 unitários + 37 integrações às 19:04 de 09/10/2026. A fixture de backfill inicialmente repetia um horário de motorista; foram usadas datas distintas, preservando a restrição de horário do produto. O ajuste posterior de pagador Pix passou nos 11 unitários do cliente/assinatura; o pacote foi gerado com integrações explicitamente omitidas nessa chamada, mantendo a evidência separada dos 37 testes anteriores. A regressão ampla 409 da quarta etapa é anterior a V19.

A jornada ampliada `./backend-java/scripts/e2e-local.ps1 -LocalDatabase -Journey mercado-pago` passou com o estado final de frontend/backend, sem erro JavaScript nem overflow em desktop/mobile/tablet. Além dos cenários anteriores, comprovou atualização automática da devolução parcial e do crédito em análise, recuperação de falha no detalhamento e nova carona por Pix sem consumir crédito bloqueado. O primeiro ensaio revelou que ocultar o checkbox omitira o booleano obrigatório; o envio passou a informar `useCredit: false`, com requisição/status 200 verificados no navegador. Capturas `credit-review-{desktop,mobile}.png` mostram dados sintéticos. SDK e serviço Mercado Pago permanecem fixtures explícitas nesses testes; nenhuma cobrança oficial foi executada.

### Pagadores oficiais do sandbox

[Cartão Web via Orders](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/integration-test/cards) exige `test@testuser.com`; o backend e a inicialização do Brick usam esse e-mail somente em teste. [Pix via Orders](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/integration-test/pix) utiliza `test_user_br@testuser.com` e nome `APRO` na simulação oficial. Produção preserva o e-mail do usuário obtido pelo backend e não recebe esses dados de teste. O cliente verifica a identidade da conta antes de enviar operações. Pix de teste pode ter confirmação automática e não representa uso de um aplicativo bancário real.

## Recuperação de comandos e identidade — sexta etapa

- V20 registra o número de tentativas nos comandos Pix/cartão. Comandos antigos já iniciados recebem uma tentativa no backfill. Uma recusa do reenvio não comprova que o envio anterior incerto deixou de criar a cobrança: o comando permanece recuperável, com payload/identidade preservados e token de cartão ainda cifrado quando necessário.
- Uma primeira tentativa definitivamente recusada encerra o comando; no cartão, apaga o token. Isso não representa liberação imediata da solicitação interna, que continua seguindo suas regras de cancelamento/expiração.
- O cliente verifica país e ambiente da conta também antes de consultar Orders por GET. Uma conta incompatível não pode fornecer um estado usado para confirmar pagamento; a identidade validada permanece em cache durante a vida do cliente.

Evidência local em 09/10/2026 às 19:24 (America/Sao_Paulo): `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest' '-Dit.test=MercadoPagoHttpIT,FlywayCompatibilityIT' verify` passou com **12 unitários + 39 integrações = 51 testes**, sem falhas, erros ou skips. Os cenários incluem primeira recusa, resposta perdida seguida de recusa no reenvio e recuperação posterior, consulta por conta incompatível, backfill de tentativas e webhook duplicado sem duplicar ledger/vaga. PostgreSQL é real; o serviço Mercado Pago é um upstream local explícito de teste. A suíte ampla e o navegador registrados nas etapas anteriores precedem V20.

## Concorrência de reembolso e proteção das mutações — sétima etapa

A regressão ampla `./backend-java/scripts/maven-local.ps1 -LocalDatabase verify` terminou em 09/10/2026 às 19:36:16 com **79 unitários + 339 integrações = 418 testes**, sem falhas, erros ou skips. Cobriu o estado V20 antes dos novos testes e da correção abaixo.

A auditoria identificou que `MercadoPagoRefundTransactions.error` decidia restaurar saldo usando apenas a tentativa registrada antes da chamada externa. Se outra tentativa já havia iniciado, uma recusa atrasada da primeira podia encerrar o lote e restaurar crédito indevidamente. Uma recusa durante GET também não prova falha do POST quando o reembolso já foi identificado. A decisão agora relê, sob lock, o número de tentativas e a identidade externa persistidos. Somente a primeira tentativa ainda sem identidade externa pode ser encerrada por recusa definitiva; demais casos mantêm o saldo reservado para conciliação.

O novo teste reproduziu a falha antes da correção: esperado `processing`, encontrado `failed`. Após a correção, passou nas três sequências determinísticas: outra tentativa iniciada; outra tentativa com identidade externa anexada; primeira tentativa já anexada antes da falha de consulta. Cada sequência mantém o saldo reservado, recupera o mesmo reembolso e comprova uma única operação externa e conclusão de ledger.

Outro teste executa as rotas de cartão, atualização de status, checkout e devolução sem autenticação, com CSRF inválido e com origem externa. Todas são recusadas antes de criar comandos, transferências ou lançamentos financeiros.

Verificação final da alteração às 19:38:26: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest' '-Dit.test=MercadoPagoHttpIT,TransferHttpIT,LedgerHttpIT' verify`, **12 unitários + 53 integrações = 65 testes**, sem falhas, erros ou skips. Esses testes usam PostgreSQL real e serviço Mercado Pago local explícito. O jar foi reconstruído. A regressão ampla de 418 precede essa correção; os testes de navegador anteriores também a precedem. Não houve mudança de interface nesta etapa.

O segredo oficial de webhook permanece ausente do `.env` privado e dos ambientes Process/User verificados nesta etapa. Os processos do túnel/proxy foram encontrados ativos, mas isso não comprova configuração do callback no portal nem entrega oficial. Não foi criada uma cobrança oficial.

## Contestação e crédito em análise — oitava etapa

A [documentação de migração para Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/resources/migrate-payments-to-orders) descreve `charged_back` com detalhes `in_process`, `settled` e `reimbursed`. Esses estados não devem restaurar saldo local automaticamente. A [notificação de contestação](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/chargebacks/notifications) usa o ID da order e `type=order`; ativar **Contestações** além de **Order** no portal, mantendo a assinatura e GET já implementados.

O novo teste reproduziu a lacuna antes da correção: uma contestação verificada deixava `creditUnderReview=false`. V21 acrescenta `chargeback_review`, com backfill dos estados inequívocos já registrados. A observação de contestação mantém esse marcador, inclusive em uma cobrança já reembolsada. Uma consulta posterior aprovada não o limpa. A primeira observação registra auditoria sem dados de cartão; o recebimento original e os lançamentos anteriores permanecem imutáveis.

Checkout com crédito e novas solicitações de devolução recusam o uso desse saldo. Pagamento sem crédito continua permitido. O worker também pausa reembolsos já planejados e ainda nunca enviados quando o recebimento passa a exigir revisão; mantém o saldo reservado. Envios anteriores incertos continuam recuperáveis com a mesma identidade, para não abandonar uma operação externa já realizada.

Carteira/checkout passam a explicar conferência financeira, cobrindo devolução externa e contestação. Uma reserva já confirmada apresenta mensagem própria de revisão do pagamento. O endpoint existente de leitura mantém seu contrato e ownership; o bloqueio não expõe informações de outro usuário.

A primeira seleção com V21 passou às 19:44:59 de 09/10/2026 com **12 unitários + 70 integrações = 82 testes**, incluindo migração, pagamentos, transferências e Orders. A jornada funcional de navegador passou em 49 segundos, com SDK/upstream explícitos de teste: mensagens de crédito em análise, pagamento sem crédito bloqueado e revisão de uma reserva confirmada após contestação. Não houve erros JavaScript ou overflow nas larguras já exercitadas; capturas adicionais foram desativadas (`JAVA_E2E_CAPTURE_SCREENSHOTS=false`). Essa jornada precede a proteção posterior para comandos de reembolso nunca enviados.

A seleção final após essa proteção terminou às 19:49:17: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest' '-Dit.test=MercadoPagoHttpIT,FlywayCompatibilityIT,PaymentHttpIT,TransferHttpIT' verify`, **12 unitários + 71 integrações = 83 testes**, sem falhas, erros ou skips. Inclui pausa de reembolso nunca enviado e recuperação de resposta perdida após contestação, sem duplicar a operação externa. Jar reconstruído com a interface atual e V21; nenhuma outra alteração de interface ocorreu após o teste de navegador.

O ajuste financeiro e a resolução administrativa de contestação continuam pendentes: o marcador mantém a análise; não representa devolução, decisão bancária ou reconciliação de saldo real. A regressão ampla de 418 testes foi executada antes de V21. O teste oficial de sandbox segue pendente do callback/segredo, SDK e operações oficiais; nenhuma cobrança real foi criada nesta etapa.

Continuam abertos: webhook/SDK/sandbox oficiais, resolução administrativa de histórico externo desconhecido/chargeback e conclusão da auditoria de produção. Não marcar a integração concluída com base nessas evidências locais.

## Revisão financeira administrativa — nona etapa

Foram acrescentados `MercadoPagoReviewController`, `MercadoPagoReviewService`, `MercadoPagoReviewTransactions` e DTOs próprios. A lista, o detalhe e a nova consulta estão disponíveis em `/api/v1/admin/payment-reviews`, `/{id}` e `/{id}/recheck`. Contratos de requisição e regras de segurança incluem essas rotas somente para administradores. Não houve nova migration nesta etapa; as evidências usam V19–V21 e a auditoria/idempotência existentes.

O painel **Administração → Conciliação** apresenta cobranças com contestação, devolução sem vínculo ou histórico incompleto. O detalhe mostra valores verificados, saldo geral do passageiro, comandos ainda pendentes e até 100 devoluções observadas, com indicação de truncamento. Identificadores ficam em um bloco expansível. Falha de leitura oferece retry explícito. A consulta exige motivo e registra solicitação/conclusão; GET externo reutiliza o processamento verificado existente. Repetir a mesma solicitação não duplica auditoria nem recebimento. O formulário não aceita valor, estado escolhido pelo administrador ou instrução para liberar saldo.

Essa entrega é uma ferramenta de investigação, não a resolução contábil de contestação/devolução desconhecida. Nenhum marcador é removido manualmente, repasse é inventado ou perda é alocada. A política para uma devolução externa após gasto do crédito foi perguntada ao responsável e permanece sem resposta; não presumir a opção sugerida como autorização. A análise/resolução completa permanece pendente.

### Evidências de 10/10/2026

- A primeira tentativa de integração falhou porque o PostgreSQL local não estava ativo após reinicialização. O cluster existente foi iniciado em loopback `127.0.0.1:55432`, sem recriar bancos. A repetição terminou às **09:37:31**: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-Dtest=MercadoPagoOrdersClientTest,MercadoPagoSignatureTest,RequestContractValidationTest' '-Dit.test=MercadoPagoHttpIT,AdminAccountHttpIT,AdminCatalogHttpIT' verify`, **24 unitários + 73 integrações = 97 testes**, sem falhas/erros/skips. As integrações são 38 Orders, 14 contas administrativas e 21 catálogo administrativo.
- Os novos casos comprovam 401/403, acesso somente admin, CSRF, contrato estrito, paginação, ausência de dados privados nos DTOs, justificativa obrigatória, indisponibilidade externa sanitizada, retry com a mesma chave, conflito quando o motivo muda, auditoria única e preservação do bloqueio/recebimento/ledger.
- A verificação de navegador encontrou um erro de renderização: `UI.summary` recebe pares, e o novo detalhe passava objetos. As duas chamadas foram corrigidas. Também foi corrigida a espera do teste para localizar o motivo dentro do texto com nome do passageiro e aguardar o conteúdo do detalhe. A execução final de `./backend-java/scripts/e2e-local.ps1 -LocalDatabase -Journey mercado-pago` passou em **48,7 segundos**, sem erro JavaScript ou overflow nos tamanhos exercitados. SDK e serviço externo são fixtures explícitas; Java e PostgreSQL são reais.
- A jornada cobre Pix/clipboard/aprovação, cartão com resposta perdida, adulteração de preço, expiração, devolução parcial com valores conferidos, falha/retry do resumo, crédito em análise e reserva em contestação. Acrescenta login administrativo isolado, lista/detalhe, consulta justificada com resposta 200 e manutenção do motivo de bloqueio, além de falha/retry da lista.
- Capturas novas em `docs/previews/mercadopago/admin-review-{desktop,mobile}.png` (1440 e 390px), inspecionadas em uma rodada conjunta. São dados sintéticos; a captura inclui o aviso transitório de consulta registrada. Capturas anteriores do checkout não foram repetidas.
- Jar reconstruído às **09:48:17**. O hash de `backend-ui.js` empacotado corresponde ao fonte; controller administrativo presente e ponte `MercadoPagoHttpIT` ausente do artefato. O build de pacote não reexecutou testes. A regressão ampla de 418 pertence ao estado anterior V20; os 97 acima são a seleção atual, não uma nova regressão completa.

O segredo oficial de webhook continua ausente da configuração privada verificada. O runtime de automação do Windows falhou antes de acessar uma janela (`failed to write kernel assets`, caminho não encontrado), impedindo essa via de configuração do portal nesta sessão. O túnel/proxy anteriores não estavam ativos após reinicialização; nenhum callback foi alterado por esta etapa. O banco dedicado de sandbox permanece sem migrations/seed e nenhuma cobrança oficial foi criada.

Próximos gates: segredo/callback de teste e túnel ativos, SDK/Orders/webhook oficiais de ponta a ponta, regra e implementação da resolução de diferenças financeiras, repasse realmente habilitado e auditoria final de produção. A integração continua em andamento.

## Auditoria de requisitos e regressão atual — décima etapa

O [checklist de lançamento](MercadoPago_Checklist_Lancamento.md) confronta separadamente os 30 itens do objetivo original com fonte, testes e evidências. Foi conferida a cobertura das 30 linhas e a validade de seus links locais. Registra explicitamente quais itens têm implementação/evidência local e quais ainda dependem de SDK, notificação/serviço oficiais, decisão financeira ou operação comercial. O README principal e o do backend apontam para o estado atual do Mercado Pago.

A revisão conferiu assinatura/GET/identidade/valor, comandos duráveis e proteção contra resultados atrasados, aplicação transacional, ownership, papel/CSRF/origem, resumo de taxas, guards de produção e configurações públicas. Os adapters antigos não podem confirmar operações Mercado Pago: `MercadoPagoPaymentProvider.authenticate` rejeita o webhook genérico; o gateway de desenvolvimento não é carregado nesse provedor; `UnavailableTransferProvider` rejeita confirmação de repasse. Isso foi conferido no fonte, sem transformar o resultado em homologação externa. Nenhuma regra financeira ou componente de frontend foi alterado nesta etapa.

`./backend-java/scripts/maven-local.ps1 -LocalDatabase verify` terminou às **10:06:12 de 10/10/2026**, após 16:12 minutos: **79 unitários sem falhas/erros/skips; 345 integrações com zero falhas de asserção, dois erros e zero skips; exit 1**. Os dois erros foram `QueryTimeoutException` em `SeedIT.cleanSyntheticData`, antes dos casos `nonDevelopmentScenarioGatewayIsRejectedBeforeBaseSeed` e `scenariosProducePendingRequestPaidAvulsaAndPaidMonthlyThroughRealDomainServices`, ao executar `TRUNCATE` do banco descartável. Os demais grupos de integração passaram, incluindo 38 Orders, autenticação, caronas, reservas/contratos, ledger, arquivos, administração e worker.

Depois do término, a inspeção do PostgreSQL não encontrou outra sessão no banco descartável nem transação preparada. O grupo de seed foi reexecutado sem alterar fonte ou timeout: `./backend-java/scripts/maven-local.ps1 -LocalDatabase '-DskipTests=false' '-Dtest=SeedSettingsTest,SeedCatalogTest' '-Dit.test=SeedIT' verify`, **8 unitários + 15 integrações, zero falhas/erros/skips, exit 0 às 10:13:00**. Os timeouts não se repetiram; a causa anterior não foi demonstrada com certeza.

Os XML atuais de 10/10 somam **79 unitários + 345 integrações = 424 resultados aprovados**, em 19 suites unitárias e 23 de integração. O último fonte backend foi escrito em 09:36:18, antes das duas execuções. A cobertura é combinada da execução ampla e do complemento de seed; **não houve uma segunda execução ampla inteira com exit 0**. Não somar os 23 do complemento como testes únicos adicionais, nem omitir os dois erros iniciais. A jornada de navegador aprovada da nona etapa continua sendo a evidência atual da UI.

A varredura de 592 caminhos não ignorados pelo Git não encontrou os formatos examinados de Access Token Mercado Pago ou chave privada nos arquivos de texto/tamanhos selecionados. Não é uma prova universal de ausência de segredos. Dados do túnel e logs novos permanecem ignorados em `.local`.

O proxy e ngrok foram recriados com inspeção desativada (PIDs 17500/17744). HTTPS público conferido: outro caminho responde 404, webhook sem backend responde 503. A URL nova está em `.local/mp-webhook-url.txt` e a configuração manual do callback/segredo foi solicitada usando esse endereço, porque a automação do Windows não inicializa. Ao fim da etapa os processos estavam vivos, mas o segredo no `.env` continuava ausente; não há confirmação do portal. Verificar handles/endpoint novamente antes de usar o túnel em outra sessão.

Nenhuma cobrança oficial foi criada. Sandbox real, resolução de diferenças financeiras, repasse compatível e gates produtivos continuam pendentes. O checklist não declara atendimento ao critério de conclusão do item 30.

## Primeiro Pix oficial — 10/10/2026, 19:02

O segredo apareceu preenchido no `.env` privado; sua autenticidade ainda não foi comprovada. O vendedor respondeu `/users/me` como brasileiro com tag `test_user`. O banco exclusivo `unicarona_mercadopago_sandbox` recebeu V1–V21 e seed explícito de usuários sintéticos; API loopback `3005` pronta. O túnel atual foi conferido ativo, com inspeção desativada.

O primeiro checkout Pix pelo frontend foi rejeitado pela API oficial: HTTP 400, `property_value`, referência externa com `:` fora do padrão. O cliente e parser passaram a usar `UNICARONA_{paymentId}`; o teste de protocolo agora rejeita caracteres proibidos. A seleção cliente/assinatura/Orders HTTP passou com **12 unitários + 38 integrações** às 19:00:12. Uma tentativa de empacotamento anterior falhou por Jar aberto na API Windows; a API foi encerrada antes da repetição aprovada. A regressão ampla anterior não cobre esta mudança posterior.

Um novo pedido avulso sintético, aceito pelo motorista, gerou R$ 5,93 calculados no backend. O navegador iniciou o Pix sem upstream ou SDK substituídos; a API oficial criou `ORDTST01M4KX9JTTE8989ES87Q9XCMAH`, na aplicação de teste `4063897889583204`. QR visível e ausência de overflow conferidos em 1440×1000 e 390×844. GET oficial posterior retornou `processed/accredited`, com valor e referência correspondentes.

O UniCarona ainda estava `pending`, sem `paid_at`, e não foi observado webhook assinado oficial. O recibo processado de estado pendente veio da resposta de criação; não comprova entrega. API e túnel continuam disponíveis para o teste pelo portal; worker não iniciado no sandbox. O histórico de notificações via MCP foi recusado por falta de propriedade OAuth. Próxima etapa: conferir callback/segredo da aplicação associada e simular a notificação oficial usando a order criada, sem forjar assinatura ou forçar reserva. Cartão/SDK, mensalidade, reembolsos oficiais, decisões financeiras e gates produtivos continuam abertos.

## Webhook oficial e reserva Pix comprovados — 10/10/2026, 19:22

A simulação do portal com Data ID genérico `123456` foi rejeitada em 422 pelo contrato de ID. Com a order verdadeira, o portal retornou 401. O diagnóstico temporário em `.local`, sem capturar chave/assinatura/corpo, comprovou headers presentes, timestamp com idade de 1,6 s e HMAC válido **com a capitalização original do ID**, mas inválido quando convertido a minúsculas. A chave privada estava correta. O validador passou a aceitar ambos os manifests autenticados do mesmo ID: minúsculas previstas na documentação e capitalização original observada no simulador. Mantém vínculo com request ID/timestamp, comparação constante, janela de 5 minutos e GET oficial obrigatório.

Às 19:21:03, o portal enviou nova notificação assinada para `ORDTST01M4KX9JTTE8989ES87Q9XCMAH` e recebeu 200. O backend registrou `Mercado Pago webhook verified`, consultou a order com o token do vendedor de teste e confirmou **R$ 5,93**, ignorando os valores/tipo/status artificiais do corpo do simulador. Às 19:21:44, Postgres mostrou pagamento `approved`, `paid_at` preenchido, **1 reserva e 1 recebimento financeiro**. O navegador exibiu **Reserva confirmada** em desktop/mobile, sem overflow. São criação/aprovação em Orders sandbox e webhook disparado manualmente pelo simulador oficial; entrega automática espontânea do evento ainda não foi comprovada. O worker permanece desligado nesse ensaio, portanto não foi ele que confirmou a reserva.

O SDK oficial revelou bloqueios CSP que fixtures não exercitavam: textos do Brick no CDN, carregamento dos secure fields e código inline de prevenção a fraude. A política libera origens HTTPS explícitas e os caminhos `/jms/` observados; o dashboard recebe nonce aleatório por documento, fornecido ao parâmetro `deviceProfileCspNonce` do SDK. Não permite scripts inline arbitrários nem `unsafe-eval`. Teste HTTP confirma nonce/policy correspondentes, rotação, ausência de cache condicional 304 e ausência de nonce na API. Os campos oficiais passaram a carregar; tokenização/pagamento com cartão ainda em teste.

O estado de carregamento do cartão usa texto neutro. Inicialização que não termina em 30 segundos mostra erro e recuperação; resposta tardia não pode enviar pagamento. A jornada local ampliada passou em **80,8 s**, com cenário de SDK travado, evento tardio e nenhum POST de cartão. O protocolo final passou às 19:19:16: **13 unitários + 45 integrações**, zero falhas/erros/skips. Jar reconstruído e runtime de sandbox passou a usar cópia privada por hash, evitando bloqueio do target no Windows. Isso não equivale a regressão ampla nova nem conclui cartão, mensalidades, reembolsos oficiais, resolução financeira e produção.


## Mensalidade, recuperação e devolução oficiais — 10/10/2026, 19:33

O worker dedicado foi iniciado no banco de sandbox. Uma contratação mensal sintética criou o pagamento `8930140f-7c2d-4617-8607-ecc438aa416a`: subtotal R$ 53,28, desconto R$ 5,33, total R$ 47,95 congelado pelo backend. Order oficial `ORDTST01M4KZ2Q9SGYHJHE4S3H4Y2CWM`. A primeira resposta do checkout foi HTTP 422; o comando durável foi recuperado pelo worker e anexou a order às 19:31:34. A conciliação às 19:31:40 consultou o provedor e confirmou a cobrança. Nenhum callback dessa order foi observado. A UI mostrou reserva confirmada; banco: 18 reservas, um contrato e um recebimento. GET oficial às 19:33 conferiu `processed/accredited`, R$ 47,95 e referência correspondente. Isso comprova recuperação e confirmação por GET, sem prova de entrega espontânea do webhook nem de todos os ciclos de renovação/carência.

Após confirmar o Pix avulso, a carona sintética foi cancelada pelo motorista via API. O crédito integral de R$ 5,93 foi solicitado para devolução via `/api/v1/transfers`, ID `ce61a547-f1cd-4c9c-b774-2dea45e2ac33`. O worker enviou a devolução oficial e consultou a order às 19:32:32: estado `REFUNDED`. GET às 19:33 comprovou refund `REF01M4KZ4FVPYR6XP4SVS76PP1ZY`, R$ 5,93, `processed`. A carteira mostrou “Devolução confirmada”, R$ 5,93 devolvidos e zero em processamento. O recebimento original foi preservado (um registro); soma global das entradas do ledger: zero. Esse total conserva a contabilidade interna e não comprova saldo bancário líquido ou lucro.

Na primeira tokenização de cartão, o SDK oficial retornou HTTP 201 em `/v1/card_tokens`; a chamada interna enviou apenas token/metadados. A Orders API rejeitou o contrato 3DS por ausência de `config.online.transaction_security.liability_shift`. O token do comando definitivamente recusado foi apagado do banco. Uma repetição diagnóstica com a mesma identidade e payload confirmou `required_properties`; nenhum pedido foi criado. O cliente foi corrigido para `validation: on_fraud_risk` e `liability_shift: required`, conforme [3DS oficial](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/integrate-3ds). Testes de contrato passaram a exigir os dois campos. Cobrança de cartão corrigida ainda precisa ser validada oficialmente.

A regressão ampla iniciada às 19:28 encontrou um conflito: o header global de CSP sobrescrevia a política restrita de downloads privados. O writer foi corrigido para preservar CSP já emitida pelo controlador. O teste `FileHttpIT.documentsUseDetectedTypeAndRemainPrivateAndByteExact` mantém a exigência `default-src 'none'; sandbox`. Resultado após a correção ainda deve ser registrado; não apresentar a execução anterior como aprovada.

O proxy temporário de diagnóstico foi substituído pelo proxy normal às 19:29, mantendo o túnel e a validação de assinatura. Inspeção ngrok continua desativada. Nenhum segredo/header de autenticação foi acrescentado aos logs.


## Cartão oficial aprovado — 10/10/2026, 19:43

Após completar o contrato 3DS, o SDK oficial tokenizou (HTTP 201) e a API interna enviou somente token/metadados. O cenário de 3DS `APRO-AUTH`, previsto na documentação oficial, criou e aprovou a order `ORDTST01M4KZP92HR9GJ4NQVPEC9AH7X`, pagamento interno `4432283f-3657-4d17-af35-b917cbcd3ec3`, R$ 5,93. GET oficial às 19:42 confirmou `processed/accredited`, valor e referência correspondentes. Banco: um recebimento, uma reserva e token do comando apagado. A tela “Reserva confirmada” foi conferida em desktop/mobile às 19:43, sem overflow.

O cenário anterior com nome genérico `APRO` retornou `pending_challenge`; o iframe do provedor mostrou erro de página. Nenhuma reserva/recebimento foi liberado. A solicitação foi cancelada via API própria às 19:42, preservando o acompanhamento externo. Isso não comprova conclusão do Challenge interativo. Para 3DS, usar os códigos oficiais `APRO-AUTH`, `OTHE-NAUT`, `APRO-CHOK` e `OTHE-CHNO`, conforme [cenários 3DS](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/integrate-3ds).

Foi solicitado ao usuário reenviar duas vezes pelo simulador oficial a notificação da order de cartão aprovada. Ainda não há resposta/prova desse callback ou de entrega espontânea; a aprovação inicial veio da resposta de criação autenticada, seguida de GET/worker. Não registrar a simulação como concluída antes da confirmação.

A devolução parcial oficial de R$ 1,00 da mensal foi conferida novamente por GET às 19:36: refund `REF01M4KZ93WE4BECM19SEGTRGENM`, `processed`, order `partially_refunded`. O recebimento original continua único, soma global do ledger zero. A reserva cancelada permanece no histórico; quantidade total de registros não significa que todos os trechos continuem ativos.


## Recusa oficial e regressão — 10/10/2026, 19:49

A Orders API devolve HTTP **402** em uma recusa do emissor, com a order criada dentro de `data`. O cliente anterior classificava o erro genericamente e não anexava essa identidade. A correção extrai apenas o ID com formato estrito, consulta a order por GET autenticado e usa somente o snapshot dessa nova consulta. Não confia no estado/valor do envelope de erro, nem aceita URL arbitrária. Erros de configuração sem identidade continuam recusados; respostas incertas preservam o mesmo comando.

O cenário oficial `OTHE-NAUT` criou `ORDTST01M4KZRYNGAZXZT98NQR3Q6WFY` para o pagamento `5c757635-4f48-44a3-b25c-0049a6f3d00c`. Após implantar a correção, `/sync` recuperou a mesma cobrança pelo payload/idempotência preservados. GET oficial às 19:49 confirmou `failed`, transação `rejected_by_issuer`, R$ 5,93 e referência correta. Banco: pagamento `failed`, gateway `REJECTED`, comando concluído/token apagado, zero reservas e zero recebimentos. Não houve alteração manual do banco nem novo token para essa recuperação.

A regressão ampla das 19:37 passou às **19:46:05**, com **80 unitários + 346 integrações = 426**, zero falhas/erros/skips. Inclui a correção da CSP restrita dos documentos e o contrato 3DS completo. Esse comando precede o ajuste HTTP 402. Após ele, `verify` selecionado cliente/assinatura/Orders HTTP passou às **19:48:07**, **14 unitários + 39 integrações = 53**, zero falhas/erros/skips. O novo teste unitário confere que o estado contraditório do envelope 402 não vence o GET; o teste HTTP confere recusa, token apagado, idempotência e ausência de reserva/ledger. Não apresentar as duas execuções como um único verify amplo do fonte final.

O cenário oficial `APRO-CHOK` criou `ORDTST01M4M00TAGVPMJRPZDK49145GH`, permaneceu `pending_challenge` e exibiu “Confirme com seu banco”. O iframe do Mercado Pago respondeu com página de erro, sem botão de confirmação disponível. A autenticação interativa segue pendente externa; não foi desativado 3DS, simulada aprovação ou liberada vaga para contornar essa falha. A criação dessa order é oficial, mas não comprova conclusão do Challenge.
