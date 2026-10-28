# Mercado Pago — operação e homologação

## Entrega técnica — 15/10/2026, 20:35

Integração funcional no sandbox oficial; [relatório completo](MercadoPago_Entrega_Integracao.md). [Procedimento de conciliação administrativa](MercadoPago_Conciliacao_Administrativa.md) implementado, com nova consulta, evidência estável e histórico imutável. O usuário determinou que perdas de caronas consumidas sem crédito de cancelamento permaneçam em análise administrativa, sem alocação automática. A preparação de produção está documentada; habilitação comercial, infraestrutura e repasse real continuam dependências externas. Os registros anteriores abaixo são históricos.

## Estado atual — 15/10/2026, 14:54

3DS aprovado e mensalidade exercitada com provedor oficial: [3DS](MercadoPago_3DS_Homologacao.md) · [carência, expiração e reativação](MercadoPago_Carencia_Homologacao.md). O relógio foi controlado somente na manutenção mensal de testes; pagamentos e assinaturas mantiveram tempo real. API/worker voltaram ao relógio normal com o mesmo Jar. Análises financeiras e operação produtiva continuam pendentes; seções datadas anteriores são históricas.

## Atualização — 15/10/2026, 14:36

Challenge 3DS aprovado interativamente no sandbox: `authenticated`, `processed/accredited`, uma reserva/recebimento, callback posterior HMAC válido HTTP 200. [Evidência e causalidade](MercadoPago_3DS_Homologacao.md). Os 403 descritos abaixo são históricos. Não usar um evento do iframe como aprovação: manter GET autenticado/webhook e conciliação.

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

Preparação operacional, não autorização para produção. Pix, cartão, mensalidade e devoluções já possuem evidências oficiais descritas abaixo, incluindo callbacks do simulador para Pix, cartão e renovação. A homologação completa ainda está em andamento. O ambiente Compose existente é de desenvolvimento e inclui seed; não deve ser usado diretamente para dados financeiros reais.

**Estado atual em 15/10:** o MCP OAuth administra a aplicação principal e salvou o callback de sandbox com Order/Contestações. Credenciais de teste correspondentes foram conferidas privadamente; o segredo parcialmente mascarado da resposta MCP não serve para provisionamento ou comparação integral. A aplicação automática da cobrança exige conferência separada de ownership/conta/modo. Pix `ORDTST01M502SZT8ZJATPMXHZHZ40R90`, R$ 5,93, foi aprovado oficialmente e recuperado pelo worker às 12:33:04, com uma reserva, um recebimento, ledger zero e UI confirmada. Não houve callback observado dessa order durante o ensaio. Backup/restauração V22 às 12:33:50: 46 tabelas/1.074 registros, esquema/dados correspondentes, cópia de verificação removida. O login do vendedor de teste e a entrega espontânea autenticada continuam em conferência. Os registros de 13/10 e 10/10 abaixo preservam evidências históricas; a antiga necessidade de renovar a sessão MCP está resolvida. Ver [detalhes atuais](MercadoPago_Homologacao_Pendencias.md).

**Revalidação em 13/10:** ambiente local restaurado sem recriar bancos, V22 e mesmo Jar. GET oficial/worker/UI comprovaram expiração do Challenge e recusa após processamento, com zero reservas/recebimentos e tokens apagados; Pix preservou uma reserva/recebimento e ledger zero. Backup/restauração às 17:28:16 conferiu 46 tabelas/1.042 registros. OAuth foi autorizado no navegador e confirmado pelo cliente oficial; a configuração local agora usa OAuth, mas a conexão das ferramentas da conversa ainda precisa ser renovada. Túnel recriado e verificado às 17:42:48. No portal da aplicação principal, segredo de teste conferido com o `.env`, URL atual preenchida e Salvar acionado, preservando eventos. O primeiro clique em Salvar não persistiu a URL. Após o QR concluído pelo usuário, a URL atual foi salva novamente e confirmada após reload com Order/Contestações preservados. Novo envio do simulador retornou timeout de 22 segundos sem callback assinado. GET/banco às 18:20:11 preservaram uma reserva/recebimento do Pix. Ver [estado atual e próximos passos](MercadoPago_Homologacao_Pendencias.md). Não repetir cobranças ou recriar contas para resolver a conexão.

**Simulação após reload, às 23:35:58:** callback manual de `ORDTST01M4MC9DY4JN14A5RESQT2JVTW` autenticado com HMAC original/GET, HTTP 200 em 1.031 ms; uma reserva e um recebimento de R$ 5,93 preservados. O simulador pertence à **5555811490405924**; a aplicação da cobrança no GET é **4063897889583204**. O MCP `application_list` encontrou esta última como **TestApp-1175c80a**, automática de teste. Conferir acesso em janela privada com o usuário de **Credenciais de teste → Dados das credenciais de teste** antes de repetir o simulador. A entrega espontânea autenticada permanece pendente. [Relatório atualizado](MercadoPago_Homologacao_Pendencias.md).

**Configuração recarregada às 23:26:** o usuário confirmou `D:\UniCarona\.env`; o arquivo foi salvo às 23:21:18, depois da inicialização anterior. API/worker foram reiniciados com a versão atual e o mesmo Jar. Novo Pix oficial `ORDTST01M4MC9DY4JN14A5RESQT2JVTW` (R$ 5,93, aplicação `4063897889583204`) foi aprovado no GET, mas suas três notificações anteriores não corresponderam ao segredo lido diretamente do arquivo pelo diagnóstico. Sem worker, o banco permaneceu pendente, sem reserva/recebimento; restaurado às 23:26, o worker conciliou uma reserva e um recebimento. UI confirmada em desktop/mobile às 23:27. Não atribuir essa confirmação ao webhook posterior. A simulação de 23:35:58 foi recebida da outra aplicação, conforme registro acima. [Evidência e próximo teste](MercadoPago_Homologacao_Pendencias.md).

Ensaio externo de **23:08–23:10**: cartão `APRO-CHOK` com Device ID, order `ORDTST01M4MBFY8ES3BYJPQ30V7KXSPV`, primeira resposta 503 recuperada pelo worker com o mesmo comando. GET autenticado confirmou `integration_data.application_id=4063897889583204`, `pending_challenge`, R$ 5,93 e referência correspondente. Token apagado, nenhuma reserva/recebimento. Iframe respondeu 403 sem Confirmar, sem violação CSP; Challenge continua pendente. Os callbacks dessa order falharam HMAC com o segredo atual. Ver [relatório de homologação e orientação do portal](MercadoPago_Homologacao_Pendencias.md). O código/pacote financeiro permaneceu igual.

## Evidências mais recentes — 10/10/2026, 21:36

- Cartão `4432283f-3657-4d17-af35-b917cbcd3ec3`: callbacks oficiais do simulador às 21:34 e 21:35, assinatura original válida e GET da order `ORDTST01M4KZP92HR9GJ4NQVPEC9AH7X`, HTTP 200. Após a repetição: uma reserva e um recebimento. O primeiro callback medido demorou 3,4 s. O timeout de 22 s informado antes não teve causa comprovada. Uma requisição assinada anterior retornou 401 em 15 ms, mas não foi registrado seu ID; não atribuir esse evento à simulação ou à entrega espontânea sem evidência.
- Renovação `7a53e27a-ed99-4db3-8f1f-3e3bcaa2a385`: callback oficial do simulador às 21:35, assinatura/GET verificados, HTTP 200 em 1,6 s. Permaneceram 26 reservas e um recebimento. Estes callbacks são manuais, não prova de entrega espontânea autenticada.
- Cartão com Device ID `88c01d8f-a7df-429c-8aaf-c0914e3dae22`, order `ORDTST01M4M5XW2SY55VGR6K4X5PQXXT`: SDK oficial tokenizou; POST interno enviou token e identificador opaco de 231 caracteres, sem PAN/CVV. Primeira resposta 503; worker recuperou a mesma cobrança. GET oficial às 21:32: `processed/accredited`, R$ 5,93 e referência correspondente. Banco: uma reserva/recebimento, comando concluído e payload cifrado apagado. UI conferida em desktop/mobile, sem overflow ou erro JavaScript.
- FORM-AUTH: GET oficial de `ORDTST01M4M210QV91EXG06EF7XCPF5J` confirmou `bad_filled_card_data`; zero reservas/recebimentos e token apagado. CONT-AUTH: GET de `ORDTST01M4M212KEZNS00916J5QQJKX0` confirmou `processing/in_process`; zero reservas/recebimentos e token apagado. Combinações de nomes são ensaios observados, não afirmar que a documentação lista literalmente essas combinações. UI de ambos conferida às 20:25, antes de vencer o prazo interno.
- Challenge FUND: iframe oficial respondeu HTTP 403 com página de erro, sem ação para concluir autenticação. Cancelamentos dos cenários APRO e CHOK, com as chaves originais, responderam 422 `unprocessable_content`, tanto com `{}` quanto com corpo vazio; GET continuou pendente de Challenge. Não forjar conclusão ou atribuir causa específica a essa resposta genérica. Os registros continuam sujeitos à conciliação.
- Seleção após Device ID: 17 unitários + 41 integrações = 58, aprovada às 20:35. Jornada anterior do navegador com fixtures de SDK/upstream aprovada em 80,9 s. Após distinguir reserva expirada de cobrança ainda pendente, a jornada passou em 90,1 s às 21:50, incluindo acompanhamento da aprovação tardia que gera crédito sem reserva. Antes disso, a asserção do novo cenário esperava indevidamente PENDING em vez de PROCESSING; depois, o crédito tardio interferiu na alocação do cenário de devolução, e uma regravação do arquivo de teste corrompeu acentos. Foram corrigidos a expectativa, a ordem dos cenários e a codificação do teste. Nenhuma regra financeira produtiva foi alterada para contornar esses problemas de fixture.

- Às 21:46, outra notificação indicou `application_id=4063897889583204`, order `ORDTST01M4M00TAGVPMJRPZDK49145GH`, tópico Order. Timestamp fresco (2,2 s), request ID e digest presentes, mas HMAC não correspondeu ao segredo atual nas duas variantes; backend retornou 401 em 16 ms. A origem não está autenticada, portanto o corpo é apenas diagnóstico, não prova de estado financeiro. As simulações humanas da aplicação `5555811490405924` validam o segredo atual. Foi solicitada conferência da configuração/segredo da aplicação de teste; não desativar HMAC para aceitar a notificação.

O checkout agora distingue o prazo interno encerrado de uma cobrança PENDING/PROCESSING no provedor: não mostra formulário/QR para novo pagamento nessa reserva, conserva atualização periódica e informa que aprovação tardia vira crédito. O acesso ao motivo da recusa também tolera gateway ausente, preservando o render de expiração no provedor de desenvolvimento.

O coletor de Device ID respeita o nonce CSP e utiliza o SDK/coletor oficial. O campo é opcional quando indisponível, validado como identificador opaco limitado, cifrado no comando para preservar os retries e enviado somente no header `X-meli-session-id`. Comandos antigos omitem o campo nulo para manter compatibilidade. Não registrar o identificador em logs ou incluir nas respostas públicas. A geração do identificador não prova que todos os serviços de análise de risco foram executados: o navegador também registrou falhas ORB em requisições de profiling.

## Encerramento e devolução após renovação antecipada

### Encerramento depois de pagar a renovação antecipadamente

Aplicar a **V22** por Flyway antes de iniciar o pacote novo. Ela cria a view de direitos futuros e dois índices, e amplia somente as transições de `renewed` para `ended/interrupted`; V1–V21 permanecem intactas. Não mudar manualmente o período antigo de `renewed` para `active`.

Quando o motorista encerra o vínculo, a API inclui os períodos pagos ligados da mesma dupla/rotina e cancela pedidos de renovação ainda não pagos. Somente reservas confirmadas em viagens futuras elegíveis geram crédito; valores em análise continuam separados. Bloqueios, mudança acadêmica e suspensão também precisam considerar o período anterior renovado. Histórico consumido não deve ser cancelado para equilibrar um saldo.

O endpoint adicional `GET /api/v1/subscriptions/{id}/rights` é privado aos participantes e somente de leitura. O contrato original mantém sua resposta anterior. Antes de operação, conferir identidade do pacote de API/worker e readiness, e comparar pagamentos, reservas, parcelas, saldo e recebimentos após o encerramento e o replay.

Ensaio nativo às 22:52: os dois períodos oficiais encerrados cancelaram 43 reservas futuras e geraram R$ 114,55 em crédito; havia R$ 1,66 de crédito anterior. Um recebimento por pagamento e ledger zero preservados, replay 200 sem alteração. UI antes/depois conferida em desktop/mobile para ambos os participantes. Os registros históricos permanecem; as 26 reservas da renovação documentadas às 21:35 foram posteriormente canceladas nesse ensaio, não permanecem ativas.

Ao recuperar uma devolução parcial, a resposta pode conter devoluções anteriores. Reutilizar o UUID/corpo originais, vincular apenas o resultado único compatível que ainda não pertence a outro comando do mesmo pagamento/proprietário e confirmar por GET. Resultado ambíguo deve continuar em análise, sem marcar aprovado nem restaurar o saldo como se nada tivesse sido enviado. **101 testes selecionados** passaram às 22:59:25, incluindo histórico completo, parcelas iguais, perda de resposta, direitos pagos da renovação, acesso privado e migrations. São testes locais HTTP/PostgreSQL com upstream explícito, distintos das operações oficiais.

O lote oficial `988f6603-5294-4da1-91bd-e2757ec96041` foi concluído às 23:00: R$ 46,95 do primeiro período e R$ 69,26 da renovação, ambos confirmados por GET, sem duplicação no replay. O pagamento original permanece aprovado/imutável; o ciclo externo registra a devolução. Saldo em processamento zero, ledger zero, um receipt por pagamento e R$ 11,86 de outras parcelas futuras preservados. As telas de carteira em desktop/mobile mostraram os valores aprovados.

Timestamps de carteira, avisos e fila administrativa usam o formatador explícito de São Paulo, em vez de extrair o dia UTC. A devolução criada em 10/10 às 22:53 foi conferida com a data/hora corretas em navegador UTC, desktop/mobile, às 23:03. Datas civis da agenda continuam preservadas. Após esses três ajustes de apresentação, package/readiness e igualdade de artefato da API/worker foram conferidos; não se executou novamente o verify de 101, cujo Java financeiro permaneceu igual.

Backup/restauração após o ensaio: **V22, 46 tabelas e 977 linhas**, dump de 264.018 bytes, snapshot consistente, esquema/tabelas correspondentes e ledger zero; conclusão às 23:01:10. A cópia isolada de restauração foi removida após a verificação. Relatório/dump permanecem somente na pasta privada ignorada `.local/mp-backup-proof`; não substituem backup de produção nem o plano de restauração das chaves privadas. O backup anterior à V22 também passou às 22:40:56 (817 linhas, V21).

## Ambientes

| Ambiente | Configuração | Operações |
|---|---|---|
| Desenvolvimento | `NODE_ENV=development`, `PAYMENT_PROVIDER=development` | Provedor simulado existente; não movimenta dinheiro. |
| Teste Mercado Pago | `PAYMENT_PROVIDER=mercadopago`, `MERCADO_PAGO_ENVIRONMENT=test`, credenciais de usuário de teste | Orders reais de teste, SDK oficial e webhook HTTPS de teste. Verificar `/users/me` com Brasil e tag `test_user`; `APP_USR` sozinho não prova o ambiente. |
| Produção | `NODE_ENV=production`, `MERCADO_PAGO_ENVIRONMENT=production`, HTTPS e autorização comercial real da operação | Somente após cumprir os gates e validar recebimentos, devoluções e obrigações dos motoristas. |

Não compartilhar bancos, chaves AES/pepper, tokens ou segredos de webhook entre teste e produção. API e worker do mesmo ambiente precisam compartilhar suas próprias configurações. Alterar a chave AES enquanto há comandos de cartão pendentes impede a recuperação dos tokens cifrados; usar um procedimento de rotação com drenagem ou migração desses comandos.

## Configuração privada obrigatória

- `MERCADO_PAGO_ACCESS_TOKEN`: credencial backend do vendedor daquele ambiente.
- `MERCADO_PAGO_PUBLIC_KEY`: chave pública correspondente, necessária para habilitar o Card Payment Brick.
- `MERCADO_PAGO_WEBHOOK_SECRET`: segredo oficial emitido para a configuração de webhook; não usar um segredo inventado apenas para o servidor iniciar.
- `APP_ORIGIN`: origem exata do frontend/API, sem caminho ou barra final. Produção exige HTTPS.
- `PAYMENT_PROVIDER=mercadopago` e `MERCADO_PAGO_ENVIRONMENT=test|production`.
- `PAYMENT_PROVIDER_TIMEOUT_MS`: de 100 a 20000 ms, conforme validação atual de billing.
- `MERCADO_PAGO_MARKETPLACE_APPROVED=true`: gate técnico de produção; só preencher depois da aprovação comercial real. A variável não cria uma habilitação no Mercado Pago.
- Configurações gerais existentes: conexão PostgreSQL, `JWT_SECRET`, `TOKEN_PEPPER`, `PIN_ENCRYPTION_KEY` (64 caracteres hexadecimais), segredo legado `PAYMENT_WEBHOOK_SECRET` e provedor de e-mail real em produção.

O token do MCP dá acesso a ferramentas do assistente; sua conexão autenticada não configura automaticamente o backend, SDK ou webhook. Não colocar Access Token em código, comando visível, navegador, exemplo versionado ou logs. Os exemplos `.env.example` contêm somente nomes e valores não privados.

## Inicialização

1. Fazer backup e verificar restauração do banco e das chaves privadas.
2. Executar o jar em `UNICARONA_MODE=migrate`, com a configuração do ambiente carregada por um meio privado. Flyway aplica as migrations novas; não editar o schema manualmente nem alterar V1–V13.
3. Iniciar API e worker (`UNICARONA_MODE=worker`) no mesmo ambiente. O worker executa outbox, expiração, carência e conciliação Orders.
4. Configurar o tópico **Order** no webhook da aplicação correta. Para acompanhar contestação de cartão, ativar também **Contestações**, conforme a [documentação oficial](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/chargebacks/notifications). O exemplo de Orders mantém `type=order` e `data.id` como ID da order; a aplicação consulta a order após validar a assinatura. URL: `https://<origem>/api/v1/webhooks/mercadopago`; os IDs e o tipo vêm na notificação, não são fixados na configuração.
5. Conferir HTTPS, preservação de `x-signature`/`x-request-id`/query e ausência de redirecionamento no endpoint. Conferir relógio sincronizado: a assinatura aceita uma janela de cinco minutos.

API e worker não devem executar seed em produção. A migração e o startup precisam falhar se o histórico legado tiver checksum alterado, lacuna ou versão desconhecida.

## Evidência exigida no sandbox

Usar dados e contas de teste oficiais; nenhum pagamento produtivo é necessário.

1. Criar pedido avulso e mensal, aceitar quando necessário e conferir preço/desconto no backend.
2. Criar Pix e cartão pelo SDK real. Confirmar que o navegador e a requisição interna nunca enviam PAN/CVV/preço confiado pelo servidor.
3. Processar pagamento de teste e comprovar entrega do webhook HTTPS oficial, HMAC válido e nova consulta Orders pelo backend.
4. Conferir order/referência/valor, receipt idempotente, parcelas/reservas e atualização da interface.
5. Reenviar notificação e atualizar status repetidamente; conferir uma única alocação financeira e nenhuma vaga adicional.
6. Testar recusa, pendência, expiração, resposta perdida, cancelamento e reembolso integral/parcial com operações oficiais. Testes locais desses cenários não encerram o gate de homologação externa.
7. Testar uma notificação ausente: a conciliação do worker deve recuperar o resultado. Testar pagamento recebido após expiração interna: crédito integral sem reserva indevida.
8. Testar renovação manual, falha dentro da carência de 72h, encerramento da carência e nova contratação elegível. Não cobrar uma assinatura automática sem revalidar agenda/vagas.

Registrar somente IDs, horários, estados, resultados dos testes e evidências de UI sem dados privados. A avaliação de qualidade Mercado Pago deve usar uma order real recente de teste. Uma order criada pelo servidor local de fixtures não serve para essa avaliação.

## Monitoramento

Logs implementados: criação/submissão, webhook verificado, aplicação recusada e `mercadopago_reconciled`/`mercadopago_reconciliation_failed`. Contêm UUID e código de erro, sem payload externo, token, CPF, PAN ou CVV.

Consulta operacional somente leitura:

```sql
SELECT payment_id, state, checked_at, next_check_at, reconcile_failures,
       reconcile_error_code, reconcile_lease_until
FROM mercado_pago_orders
WHERE reconcile_failures > 0
   OR (next_check_at < now() - interval '10 minutes'
       AND (reconcile_lease_until IS NULL OR reconcile_lease_until < now()))
ORDER BY next_check_at;
```

Essa consulta detecta atraso/falha de conciliação de orders anexadas. Pix e cartão possuem comandos duráveis e outbox com identidade estável, inclusive para recuperar respostas perdidas antes de anexar a order. Pix congela valor/e-mail/expiração; cartão guarda o token cifrado e o apaga ao identificar a order ou encerrar definitivamente um comando nunca enviado. Tarefas `mercadopago_pix` e `mercadopago_card` podem ser retomadas no painel administrativo com as mesmas proteções de versão, motivo e autorização. Não gerar outro pagamento ou trocar chave de idempotência para resolver uma resposta incerta.

Devoluções usam `transfers` e alocações em `mercado_pago_refunds`. Conferir `status`, `provider_reference`, `amount_cents`, `attempts`, `last_error_code` e `completed_at` antes de encerrar um incidente. Uma resposta incerta mantém o saldo em processamento. Em lote parcialmente recusado, somente a parte não devolvida é restaurada ao crédito. A carteira expõe separadamente valor confirmado, restaurado e em processamento.

Uma recusa atrasada não pode restaurar saldo quando outra tentativa já iniciou ou o reembolso foi identificado. O tratamento de erro confere esses dados novamente sob lock; uma falha no GET depois de anexar a identidade mantém a devolução em processamento para conciliação. Retomar o mesmo comando, sem criar outra transferência ou presumir falha financeira a partir do código HTTP da consulta.

Se o recebimento exigir revisão por contestação ou devolução externa desconhecida, comandos de reembolso com `attempts=0` aguardam análise sem chamar o provedor e sem restaurar saldo. Comandos anteriormente enviados com resposta incerta precisam continuar a recuperação pela mesma identidade; bloquear seu acompanhamento poderia ocultar uma devolução já realizada externamente.

Alertar sobre fila esgotada, atraso de conciliação, divergência de valor/identidade e pagamentos externos sem aplicação interna. Repasses seguem indisponíveis até habilitação real: saldo devido ao motorista permanece registrado; nenhuma transferência fictícia deve ser marcada concluída.

## Incidentes

### Examinar um caso financeiro

Em **Administração → Conciliação → Casos financeiros em análise**, selecionar **Examinar caso**. A tela reúne recebimento original, devoluções externas verificadas, crédito do passageiro, saldo em processamento, comandos pendentes e motivos do bloqueio. O crédito apresentado é o saldo geral daquele passageiro; não representa uma alocação exclusiva ao pagamento examinado. O ledger local não comprova o saldo bancário disponível no Mercado Pago.

As APIs administrativas são:

- `GET /api/v1/admin/payment-reviews?limit=20&offset=0`: lista paginada de cobranças com contestação, devolução externa sem conciliação ou histórico incompleto.
- `GET /api/v1/admin/payment-reviews/{id}`: evidências verificadas de um pagamento. O resumo limita a lista de devoluções a 100 e informa quando existe histórico adicional.
- `POST /api/v1/admin/payment-reviews/{id}/recheck`: motivo obrigatório de 3–1000 caracteres, CSRF e `idempotency-key`. Registra a solicitação, consulta a order por GET e aplica o snapshot pelo fluxo já verificado. A conclusão da consulta também é auditada.

Consultar novamente não solicita cancelamento/reembolso, não limpa marcadores manualmente e não decide quem arca com uma diferença financeira. Uma falha após registrar a solicitação pode ser retomada com a mesma chave e motivo; alterar o conteúdo com essa chave é rejeitado. Consultas repetidas preservam a idempotência dos recebimentos e da auditoria.

A resolução contábil de devoluções/contestações externas, especialmente quando o crédito já foi gasto, continua pendente da regra de negócio e da conciliação. Não editar saldo, ledger ou marcadores no banco para encerrar um caso. As ações administrativas disponíveis nesta etapa são leitura e nova verificação, sem decisão de perdas ou repasses.

- **503/timeout/401 do provedor:** preservar cobrança e identidade; corrigir conexão/credencial do ambiente correto, aguardar backoff ou retomar a tarefa existente. Não divulgar o corpo do erro externo.
- **423 `resource_locked`:** bloqueio temporário da chave de idempotência. O adapter retorna 503 recuperável e mantém o comando/payload/chave originais, inclusive o token cifrado ainda necessário. Não criar uma segunda cobrança para contornar o bloqueio. Ver [erros oficiais de Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/integration-errors/api).
- **Recusa após envio anterior incerto:** conferir `attempts` nos comandos Pix/cartão. V20 mantém o comando recuperável quando houve mais de uma tentativa; uma recusa posterior não elimina a possibilidade de cobrança no primeiro envio. Retomar a mesma identidade e payload, sem criar outra cobrança. No cartão, preservar o token cifrado até identificar a order. Uma primeira tentativa definitivamente recusada encerra o comando e apaga esse token, mas a solicitação interna continua sujeita às regras de cancelamento/expiração.
- **Webhook inválido:** rejeitar; conferir segredo oficial, relógio, ID e headers. Não desligar a validação de assinatura.
- **Pagamento externo aprovado sem reserva válida:** seguir o crédito integral existente e concluir a devolução pelo fluxo financeiro; não forçar assento.
- **Reembolso criado fora do app:** `untracked_refunds` bloqueia novos usos/devoluções do crédito daquele proprietário e registra auditoria. V19 preserva operações verificadas em `mercado_pago_observed_refunds`; uma consulta sem o histórico não libera o bloqueio. `refund_history_incomplete` mantém uma revisão legada sem prova suficiente. Revisar parcelas, operações externas e ledger antes de resolver. Não remover o bloqueio manualmente para contornar uma divergência. O procedimento administrativo de resolução continua pendente.
- **Contestação externa:** V21 mantém `chargeback_review` após estado `charged_back` verificado, inclusive para um recebimento já reembolsado. O crédito do proprietário fica indisponível para uso/devolução; pagamento sem esse crédito continua permitido. Uma nova consulta sem contestação não limpa esse marcador. A auditoria `mercadopago_chargeback_review` registra a primeira observação, sem dados de cartão. Revisar recebimentos, parcelas, resultado da disputa e ledger antes de confirmar resolução. Não remover o marcador para liberar saldo sem conciliação: ajuste financeiro e procedimento administrativo completos continuam pendentes.
- **Banco indisponível:** nenhuma confirmação local é presumida; restaurar acesso, verificar recibos e retomar processamento idempotente.

Concluir os gates, ensaiar backup/restauração e registrar o responsável operacional antes de liberar usuários reais.

## Túnel exclusivo para sandbox

`backend-java/scripts/mercadopago-webhook-proxy.py --port 3987 --backend-port 3005` abre somente uma porta loopback. Apontar um túnel HTTPS de teste para essa porta, com inspeção de requisições desativada. O proxy aceita apenas POST JSON em `/api/v1/webhooks/mercadopago`, limita o corpo a 64 KiB e encaminha assinatura/ID/query para a API local. Outros caminhos retornam 404. Sem backend disponível, retorna 503 para permitir nova tentativa; nunca confirma um evento por conta própria.

A URL é temporária. Se o túnel reiniciar, atualizar o callback na aplicação e no modo correspondentes ao ambiente verificado. O segredo deve ser o oficial emitido pelo Mercado Pago e ficar somente no ambiente privado. Em 15/10, o MCP OAuth confirmou `save_webhook` para o sandbox da aplicação principal; uma rejeição de ownership na aplicação automática é uma limitação de acesso distinta, não motivo para repetir o login OAuth principal. Conferir a configuração no portal da conta correta e seguir a documentação específica de Orders para o modo aplicável. Não configurar produção da conta real por suposição, desativar assinatura ou usar o segredo mascarado retornado pela ferramenta.

O inicializador `backend-java/scripts/mercadopago-sandbox-local.ps1 -Mode migrate|seed|api|worker` lê `.local/mp-sandbox.env` e aceita o segredo oficial/chave pública provisionados no `.env` privado. Exige o banco loopback **unicarona_mercadopago_sandbox** e Mercado Pago explicitamente em teste. API fica em `127.0.0.1:3005`; não expõe o app no túnel. O arquivo privado deve conter chaves próprias de autenticação/criptografia, Public Key correspondente e Access Token do vendedor de teste. O cliente valida a tag `test_user` antes de movimentar operações. Não reutilizar esse banco em testes automáticos que apagam schema.

Em 10/10/2026 às 18:54, o segredo preenchido no `.env` privado permitiu aplicar as 21 migrations no banco dedicado e executar o seed de usuários sintéticos. A API em `127.0.0.1:3005` respondeu `ready`, com configuração pública Mercado Pago em teste. `/users/me` confirmou vendedor brasileiro com tag `test_user`. Isso ainda não comprova autenticidade do segredo nem configuração/entrega do callback. Os modos `migrate` e `seed` são explícitos: iniciar `api` não cria dados fictícios nem pagamentos automaticamente.

Após a reinicialização do computador, em 10/10/2026, os processos anteriores do túnel e do proxy não estavam ativos. A URL temporária registrada na etapa anterior não comprova disponibilidade atual. Recriar o túnel/proxy e conferir o callback no portal antes de iniciar o ensaio oficial. O PostgreSQL local foi reiniciado usando o cluster existente; o banco dedicado de sandbox não foi migrado nem populado por essa ação.

Na auditoria seguinte de 10/10, proxy e ngrok foram recriados em janelas ocultas (PIDs 17500/17744). A inspeção do ngrok foi desativada; o HTTPS público foi verificado com 404 em outro caminho e 503 no webhook com backend ausente. A URL nova está no arquivo privado `.local/mp-webhook-url.txt`; a configuração de teste no portal foi solicitada usando esse endereço. Antes de confiar nessa evidência em outra sessão, verificar os processos e `/api/tunnels` atuais: arquivo de URL ou PID registrado não prova processo vivo. Às 18:53 o túnel continuava ativo; a API foi iniciada depois da configuração privada. Autenticidade do segredo e entrega oficial ainda dependem de notificação assinada verificada.

Às 18:56, o checkout Pix pelo navegador local chegou à API oficial, sem upstream/SDK simulados. A criação foi rejeitada com HTTP 400, código `property_value`: `external_reference` com `:` não respeita o padrão da Orders API. Nenhuma order foi retornada. A referência foi corrigida para `UNICARONA_{paymentId}`, e o teste do cliente passou a rejeitar caracteres proibidos no upstream de protocolo. A repetição diagnóstica usou a mesma chave/payload da tentativa rejeitada. O ensaio completo e o webhook continuam pendentes até uma order oficial válida.

Às 19:00, após a correção e reconstrução do Jar, uma nova solicitação avulsa sintética foi aceita pelo motorista e paga via checkout Pix do próprio frontend. O backend calculou R$ 5,93; a Orders API criou `ORDTST01M4KX9JTTE8989ES87Q9XCMAH`, aplicação de teste `4063897889583204`. QR Code visível e ausência de overflow conferidos em 1440×1000 e 390×844. GET oficial às 19:01 confirmou `processed/accredited`, valor e referência correspondentes. O backend permanecia `pending`, sem `paid_at`; não foi observado log de webhook assinado. O recibo de estado pendente já processado veio da aplicação da resposta de criação, portanto não comprova entrega do callback. API e túnel foram mantidos disponíveis para testar a notificação pelo portal. O worker ainda não foi iniciado no sandbox. A seleção do cliente/assinatura/Orders HTTP passou às 19:00:12: 12 unitários + 38 integrações, zero falhas/erros/skips. Esse resultado é local e não substitui assinatura oficial ou confirmação de reserva.

No Checkout API Web de teste, o backend utiliza o e-mail de pagador de cartão `test@testuser.com`, conforme a [documentação oficial](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/integration-test/cards). O Brick recebe esse e-mail na inicialização de teste. Para [Pix de teste](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/integration-test/pix), utiliza `test_user_br@testuser.com` e nome `APRO`; o provedor simula a aprovação. Esses campos não entram em produção. A recusa do MCP em criar outra conta não impede esse procedimento Web documentado; o segredo oficial e a validação ponta a ponta continuam necessários.


## Confirmação oficial do Pix — 10/10/2026, 19:22

O usuário reenviou a simulação com Data ID `ORDTST01M4KX9JTTE8989ES87Q9XCMAH`; o portal retornou **200 OK** às 19:21. A assinatura desse callback utilizou a caixa original do ID. O verificador aceita tanto o manifesto normalizado documentado quanto a caixa original, sempre com HMAC validado, request ID e janela de cinco minutos. Não foi necessário substituir o segredo oficial.

O backend consultou novamente a Orders API: referência e R$ 5,93 correspondentes ao pedido interno, `processed/accredited`. Os dados fictícios do corpo do simulador não foram usados para confirmar o valor. PostgreSQL registrou uma reserva e um recebimento financeiro; a tela mostrou “Reserva confirmada” em desktop e mobile. O worker não estava ativo. Essa evidência comprova a cadeia do Pix acionada pelo simulador oficial, mas não a entrega espontânea do evento.

O formulário oficial de cartão já carrega os secure fields. A CSP usa nonce por documento, origin/path permitidos do SDK e nenhuma liberação global de script inline/eval. Tokenização e cobrança de cartão ainda dependem de validação oficial.

O helper de sandbox inicia uma cópia privada imutável do Jar identificado por SHA-256, evitando que o bloqueio de arquivo do Windows impeça recompilar `target`. Não altera as credenciais nem o banco dedicado.


## Worker e devoluções oficiais — 10/10/2026, 19:35

A contratação mensal de R$ 47,95 (`ORDTST01M4KZ2Q9SGYHJHE4S3H4Y2CWM`) foi recuperada e confirmada pelo worker por GET oficial, sem callback observado: 18 reservas, um contrato e um recebimento, UI confirmada. A primeira resposta de checkout foi 422, por isso não apresentar esse ensaio como criação imediata sem falhas.

O cancelamento da carona avulsa sintética gerou crédito integral; a devolução oficial de R$ 5,93 foi confirmada por GET e carteira, refund `REF01M4KZ4FVPYR6XP4SVS76PP1ZY`. Em seguida, um trecho mensal sintético foi cancelado, gerando R$ 2,66 de crédito; a solicitação de devolução de R$ 1,00 retornou aprovada e a order passou a `PARTIALLY_REFUNDED`. Ambas as transferências terminaram sem saldo em processamento. IDs e horários estão registrados na integração; nenhum ajuste manual de saldo/estado foi feito no banco.

O SDK tokenizou o cartão de teste, mas a Orders API recusou a configuração 3DS incompleta. O cliente agora envia `liability_shift: required` junto de `validation: on_fraud_risk`. O token do primeiro envio definitivamente recusado foi apagado pelo ciclo normal de comando. A cobrança corrigida ainda precisa ser verificada oficialmente.


Às 19:49, o cartão APRO-AUTH já tinha uma reserva/recebimento confirmado por GET e UI. A recusa OTHE-NAUT foi recuperada com a mesma identidade depois de corrigir a leitura da order dentro do envelope HTTP 402: nova consulta GET, estado REJECTED, token apagado, nenhuma reserva/recebimento. O Challenge APRO-CHOK retornou uma página de erro do provedor; conclusão interativa continua pendente. Não desligar 3DS nem marcar pagamento aprovado manualmente.

Regressão ampla 426 passou às 19:46:05; seleção posterior ao ajuste 402, 53 testes, passou às 19:48:07. Callback duplicado da order de cartão ainda depende do reenvio solicitado ao usuário. API e worker usam o último Jar privado do sandbox; mutex local protege a cópia simultânea para evitar corrida de inicialização no Windows.

## Backup e restauração do sandbox — 10/10/2026, 19:56

Executar `node backend-java/scripts/postgres-sandbox-backup-verify.mjs` somente no sandbox local preparado. O script recusa banco, host ou ambiente diferentes; usa `pg_dump` com snapshot MVCC exportado e restaura em um banco de nome aleatório, criado pelo operador local. Confere hashes por tabela e esquema, registra a versão Flyway e a soma do ledger e remove somente a cópia criada por essa execução. Não altera o banco de origem. Credenciais e linhas não entram na saída; o dump contém dados privados e fica no diretório ignorado `.local/mp-backup-proof`.

O ensaio das 19:56:25 passou: 46 tabelas, 508 linhas, 226.679 bytes, versão V21, esquema e conteúdo das tabelas correspondentes, diferença do ledger zero e cópia temporária removida. O comando inicial havia falhado porque o usuário da aplicação não possui `CREATEDB`; a versão corrigida separa a credencial do operador e preserva os privilégios da aplicação. Esse ensaio comprova restauração desse snapshot local, não recuperação da infraestrutura produtiva nem restauração das chaves externas. O procedimento de produção precisa incluir as chaves privadas, armazenamento de arquivos, retenção e controle de acesso ao backup.

## Renovação mensal oficial — 10/10/2026, 19:59–20:08

Pagamento `7a53e27a-ed99-4db3-8f1f-3e3bcaa2a385`, order `ORDTST01M4M0MKPP7SSPNBK2NX5CW7A1`: subtotal R$ 76,96, desconto R$ 7,70, total R$ 69,26 calculados pelo servidor. A renovação começa em 01/11; a aprovação criou 26 reservas e um novo contrato, e o anterior passou a `renewed`. A tela mostrou Reserva confirmada. A criação inicial respondeu 422; o comando durável foi recuperado e confirmado pelo worker. Não foi capturado o corpo da falha inicial para atribuir sua causa com certeza. GET oficial às 20:08 confirmou `processed/accredited`, valor/referência correspondentes; banco com 26 reservas, um recebimento e comando concluído. Não foi observado callback oficial dessa mensalidade.

Antes da cobrança, enviar `price: 1` foi rejeitado com 422 `VALIDATION_ERROR`, e consultar o pagamento por outro proprietário retornou 404. Não havia referência externa ou comando criado por essas tentativas. A seleção das 20:08:39 passou com 11 testes unitários do cliente e 40 integrações HTTP, zero falhas/erros/skips, incluindo recuperação de 423 e recusa específica consultada na transação oficial. Esses testes de falha são fixtures explícitas; a jornada de navegador posterior ainda precisa fechar sem falhas.

O MCP voltou a recusar histórico de notificações e avaliação de qualidade por propriedade OAuth, tanto para a aplicação principal quanto para a do vendedor de teste. A automação CUA também continua indisponível antes de abrir o portal. Essas limitações não afetam os GETs autenticados do backend; ainda impedem comprovar o histórico de entrega por essas ferramentas.

## Recusa oficial por saldo insuficiente e jornada atual — 10/10/2026, 20:16

O teste padrão `FUND` tokenizou pelo SDK e criou `ORDTST01M4M1ECZ9P5E4R66BHNCXKEHA`, mas retornou `pending_challenge` sob a configuração 3DS, sem reserva/recebimento. A combinação `FUND-AUTH` foi um ensaio adicional do comportamento do sandbox, combinando o nome de recusa com o sufixo de autenticação; a documentação 3DS não lista essa combinação nominalmente. Portanto, a prova é a resposta observada, não uma promessa de resultado baseada no nome.

Pagamento `d7523853-439b-4c76-8378-bf7db3541c92`, order `ORDTST01M4M1K3VP134X05ETJQ8AW9S1`: GET oficial às 20:16 confirmou `failed`, transação `insufficient_amount`, R$ 5,93 e referência correspondente. Banco: `failed`/`REJECTED`, zero reservas/recebimentos, comando concluído e token apagado. Gateway/checkout exibiram o motivo correto em desktop/mobile, sem overflow ou erro JavaScript. O backend recebeu somente token/metadados do SDK oficial; não recebeu PAN/CVV.

A jornada de navegador com fixtures passou em 81,1 segundos. As duas falhas anteriores ocorreram após a consulta administrativa: GET retornou 429 porque a sequência composta de cenários ultrapassou o limite de 180 chamadas/minuto no mesmo IP loopback. A preparação da fixture reinicia o bucket somente no banco descartável, entre jornadas de passageiro e administrador. As proteções do aplicativo e do sandbox oficial permanecem iguais. A execução cobre também mensagem de limite insuficiente, recuperação de cartão, Pix/webhook, carteira, revisão administrativa, três tamanhos de tela e SDK que não responde/callback tardio.
