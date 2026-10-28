# Mercado Pago — evidência para resolver a homologação

## Entrega verificada — 15/10/2026, 20:35

A integração solicitada está funcional no sandbox oficial. [Entrega técnica e configuração de produção](MercadoPago_Entrega_Integracao.md) · [Conciliação administrativa](MercadoPago_Conciliacao_Administrativa.md). V25, API/worker no mesmo artefato, 205 testes distintos aprovados em conjuntos complementares, callbacks/GET/reserva/carteira/interface comprovados. A conferência final não encontrou recebimento duplicado ou divergência no ledger; crédito anterior preservado e dívida do ensaio quitada.

O usuário decidiu manter contestações de caronas consumidas, sem crédito de cancelamento, em análise para decisão administrativa. Esse comportamento é deliberado; não há desconto ou dívida automática nesse caso. A implantação produtiva, aprovação comercial, credenciais/HTTPS e meio real de repasse continuam externos à entrega de software. Repasse automático não foi habilitado. As pendências de código das seções datadas abaixo são históricas.

## Estado atual — 15/10/2026, 14:54

3DS interativo aprovado e ciclo mensal exercitado com Pix oficiais: renovação na carência, expiração no limite de 72h controladas, aprovação tardia em crédito sem reserva e nova contratação ativa. **95 testes distintos aprovados nesta etapa.** [3DS](MercadoPago_3DS_Homologacao.md) · [Carência: evidências e limite do relógio controlado](MercadoPago_Carencia_Homologacao.md).

Não há liberação para produção: permanecem resolução administrativa de análises financeiras, conciliação de perdas fora do crédito de cancelamento e preparação comercial/operacional do recebimento e repasse. A regra de dívida já foi implementada. Os estados pendentes nas seções datadas abaixo são históricos.

## Atualização — 15/10/2026, 14:36

**Challenge 3DS interativo aprovado no sandbox oficial.** GET retornou `authenticated` e `processed/accredited`; interface confirmou uma reserva e um recebimento. Callback posterior autenticado, HTTP 200, sem duplicação. [Evidência completa e limites da atribuição](MercadoPago_3DS_Homologacao.md). As pendências de 3DS nas seções datadas abaixo são históricas; permanecem a carência mensal externa e os requisitos operacionais/financeiros de produção.

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

Atualizado em 15/10/2026, horário de São Paulo. Este relatório contém identificadores de operações de teste e resultados sanitizados. As credenciais, assinaturas, URLs de autenticação e identificadores opacos de dispositivo permanecem nos arquivos privados. As seções datadas de 13/10 e 10/10 abaixo são históricas; o estado atual está na seção seguinte.

## Revalidação de 15/10 — OAuth disponível, entrega espontânea pendente

O OAuth passou a funcionar nas ferramentas desta conversa: `application_list` listou a aplicação principal **5555811490405924**, e `notifications_history`/`get_credentials` responderam sem a rejeição de autenticação da sessão anterior. A comparação privada das credenciais de teste retornadas pelo MCP com a configuração efetiva do sandbox confirmou Access Token e Public Key correspondentes. A tentativa de consultar credenciais da aplicação automática **4063897889583204** foi rejeitada por ownership: o acesso da conta principal não permite administrá-la diretamente por essa operação. Isso é distinto da falha anterior de renovação do cliente.

`save_webhook` confirmou a URL HTTPS atual como `callback_sandbox` da aplicação principal, preservando Order e Contestações; a resposta manteve o callback produtivo sem configuração. O túnel temporário está registrado em `.local/mp-webhook-url.txt`, com inspeção de requisições desativada. O teste de transporte obteve 404 fora do webhook e 401 em POST sem assinatura; isso não comprova HMAC ou entrega oficial. O segredo retornado pelo MCP é **parcialmente mascarado**, portanto não pode ser usado como segredo nem comparado integralmente com o `.env`. Nenhuma credencial foi alterada nesta rodada.

Após a parada do computador, o cluster PostgreSQL dedicado foi iniciado novamente e concluiu a recuperação normal de WAL, sem recriação do banco. O PostgreSQL de outro serviço na porta 5432 foi preservado. API e worker usam o mesmo Jar previamente verificado e o sandbox V22; `/health/ready` confirmou prontidão.

### Pix real de teste com isolamento da causa da aprovação

Antes da criação, a API oficial confirmou vendedor brasileiro com a marca `test_user`. Foi criado um único novo pedido sintético, com valor congelado pelo backend. O checkout foi realizado pela interface real, sem fixture de SDK ou upstream. O worker ficou temporariamente parado para distinguir entrega espontânea de recuperação por consulta.

| Evidência | Resultado em 15/10 |
|---|---|
| Pagamento interno | `acbcf227-33f3-470e-a2e7-051be8d9b80d` |
| Order oficial | `ORDTST01M502SZT8ZJATPMXHZHZ40R90` |
| Valor / taxa incluída | R$ 5,93 / R$ 0,59 |
| Criação às 12:27:31 | Snapshot verificado `PENDING`; QR e estado de espera exibidos |
| Observação às 12:27:49, worker parado | GET oficial `processed/accredited`, referência e valor correspondentes, aplicação **4063897889583204**; banco pendente, zero reservas e zero recebimentos |
| Notificações observadas da nova order | Nenhuma no diagnóstico durante o ensaio; histórico MCP da aplicação principal sem notificações, limitado ao escopo dessa aplicação |
| Recuperação às 12:33:04 | Após restaurar o worker, GET aplicado transacionalmente: `APPROVED`, uma reserva e um recebimento |
| Interface após recuperação | “Pagamento aprovado” e “Reserva confirmada”; screenshot privado `mp-oct15-pix-confirmed.png` |
| Ledger | Soma das entradas igual a zero antes e depois da recuperação |

**A primeira aprovação deste Pix foi aplicada pela conciliação do worker.** Nenhuma atualização financeira manual foi feita. A ausência de callback observado não estabelece se a causa é roteamento, configuração ou comportamento do ambiente de testes; a entrega espontânea autenticada continua sem comprovação.

Backup/restauração nativos concluídos às **12:33:50**: snapshot consistente V22, **46 tabelas e 1.074 registros**, esquema e dados correspondentes, ledger zero. A cópia temporária usada na conferência foi removida. A regressão combinada de 445 testes permanece a evidência de 13/10; não foi executada novamente nesta rodada sem alterações de fonte.

### Acesso à configuração correspondente

O navegador alcançou o login oficial do vendedor de teste, mas a senha anteriormente disponível foi recusada. A aba foi preservada e o usuário recebeu orientação para entrar com os dados atuais de **Credenciais de teste → Dados das credenciais de teste** da aplicação principal, sem enviar senha/código no chat. Nenhuma senha foi redefinida e nenhuma nova aplicação/conta foi criada.

A [documentação específica de notificações de Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/notifications), consultada em 15/10, orienta configurar o tópico Order na aba **Modo de produção**. Isso exige distinguir a conta real da conta automática de teste antes de qualquer alteração: **não foi configurado callback produtivo da conta real neste ensaio**. Após o login do vendedor de teste, conferir a aplicação automática e o modo correspondente, obter/comparar o segredo completo privadamente e validar a entrega. O `application_id` diferente, isoladamente, não prova qual configuração está faltando.

O próximo passo depende desse login e da conferência legítima da configuração. Permanecem separados os gates de Challenge 3DS interativo, ciclo externo de carência mensal, política de diferença financeira e habilitação operacional/comercial de repasses. A integração não está liberada para produção.

## Revalidação e autorização OAuth em 13/10

O ambiente local estava parado. PostgreSQL foi restaurado a partir do cluster existente, sem recriar/apagar bancos; API e worker voltaram com o mesmo Jar verificado em 10/10 e sandbox V22. A consulta oficial dos três pagamentos existentes trouxe os resultados abaixo; nenhuma nova cobrança foi criada.

| Caso | GET oficial | Aplicação após recuperar o worker | Invariantes |
|---|---|---|---|
| Pix `ORDTST01M4MC9DY4JN14A5RESQT2JVTW` | `processed/accredited`, R$ 5,93, referência correspondente | `APPROVED` preservado | Uma reserva e um recebimento |
| Challenge `ORDTST01M4MBFY8ES3BYJPQ30V7KXSPV` | `canceled/expired`, transação `expired` | Gateway `EXPIRED`, pagamento interno `failed` | Zero reservas/recebimentos; token apagado |
| Processamento `ORDTST01M4M212KEZNS00916J5QQJKX0` | `failed`, transação `rejected_by_issuer` | Gateway `REJECTED`, pagamento interno `failed` | Zero reservas/recebimentos; token apagado |

Antes da recuperação, os dois cartões tinham prazo interno expirado e gateway ainda pendente/em processamento. Depois do GET aplicado pelo worker, a UI exibiu “Pagamento expirado” e “Pagamento recusado” em 1440 px e 390 px, sem overflow, erro JavaScript, iframe, formulário de cartão ou botão para pagar novamente a reserva encerrada. A soma do ledger continuou zero. A expiração observada é um resultado oficial terminal; **não comprova a conclusão interativa do Challenge** nem a entrega de webhook autenticado.

Backup/restauração às **17:28:16**: snapshot consistente, V22, **46 tabelas e 1.042 registros**, esquema/dados correspondentes, ledger zero; a cópia temporária de verificação foi removida. Essa prova substitui a contagem anterior de 977 registros somente para o snapshot atual.

### OAuth autorizado pelo navegador

O usuário solicitou controle do navegador e confirmou o clique final que concede acesso ao Codex. A página mostrou permissões para administrar pagamentos/assinaturas, cancelamentos/devoluções e consultar perfil/transações, além da aceitação dos termos. O navegador integrado retornou 403 na autenticação; no Chrome foi possível acessar a conta do titular e o painel da aplicação principal. Após a autenticação, o cliente oficial registrou **login OAuth concluído**. A página de retorno local foi bloqueada pelo Comet (`ERR_BLOCKED_BY_CLIENT`), mas a confirmação do cliente demonstrou que o retorno foi processado; não foi necessário desabilitar proteção do navegador.

O `config.toml` foi alterado somente para remover a precedência do Bearer explícito da conexão `mercadopago-mcp-server`, com backup privado e comparação do restante da configuração. As credenciais do backend ficaram preservadas. O comando oficial `codex mcp list` mostrou o servidor **enabled / OAuth**, sem Bearer configurado. Tokens, códigos, nonces e identificadores de dispositivo não foram incluídos neste relatório.

**A conexão das ferramentas desta conversa ainda não foi renovada:** após a alteração, `application_list` continuou retornando apenas a aplicação automática de teste e `notifications_history` ainda rejeitou o token por ausência de ownership OAuth. Portanto, o login/configuração local foi comprovado, mas acesso às ferramentas de gestão por esta sessão permanece pendente. É necessário reconectar o MCP no Codex antes de afirmar que a configuração remota foi realizada; o CLI disponível não oferece comando de reload.

### Endpoint atual e próximo passo

O proxy e o túnel foram recriados às **17:42:48**, com inspeção de requisições desativada. HTTPS atual foi verificado: 404 fora do webhook e 401 em notificação sem assinatura. A URL atual está em `D:\UniCarona\.local\mp-webhook-url.txt`. Pelo navegador, o segredo do **modo de teste da aplicação principal 5555811490405924** foi revelado para comparação privada e voltou a ser ocultado; ele corresponde ao `.env`. A URL ainda apontava para o túnel antigo. Foi substituída pelo endereço atual e acionado **Salvar configurações**; o botão ficou desabilitado e o simulador exibiu a nova URL. Eventos **Order** e **Contestações** permaneceram selecionados.

Os dois envios do simulador para a order existente retornaram `1111 — Request failed with status code 503` e `1111 — timeout of 22000ms exceeded`. Nenhum callback assinado novo chegou ao diagnóstico. API pronta, túnel correspondente e inspeção desativada foram conferidos durante o ensaio. Não há evidência para atribuir o erro a HMAC ou ao backend. Depois de recarregar o portal para conferir a persistência, foi exigida nova **verificação por QR no aplicativo Mercado Pago**.

**Conferência após autenticação:** o usuário concluiu a verificação no aplicativo. O portal ainda mostrava a URL antiga, comprovando que o primeiro clique em Salvar **não persistiu** a alteração. A URL atual foi preenchida/salva novamente e, após novo reload e abertura de Configurar notificações, permaneceu cadastrada em modo de teste. Order e Contestações continuaram selecionados e o segredo permaneceu oculto. Screenshot privado registra a configuração persistida. Novo envio do simulador com essa sessão terminou em timeout de 22 segundos, sem callback assinado no diagnóstico. A URL salva da aplicação principal está comprovada; entrega e assinatura da aplicação da cobrança continuam pendentes.

Às **18:20:11**, nova consulta somente de leitura confirmou GET `processed/accredited`, R$ 5,93, aplicação **4063897889583204** e referência correspondente. Banco preservou `APPROVED`, uma reserva e um recebimento. Nenhuma cobrança foi criada para esses envios.

Depois de reconectar o cliente: conferir apps/ownership e relação entre a aplicação principal **5555811490405924** e as credenciais de teste da **4063897889583204**, configurar a URL/Order na aplicação correta, obter o segredo correspondente de forma privada e validar entrega espontânea com assinatura, GET, banco e UI. Não trocar a conta de cobrança ou criar outra aplicação apenas para contornar o vínculo dos pagamentos históricos. O painel Developers da aplicação principal foi preservado para continuar esse trabalho.

## Simulação autenticada após recarregar o segredo

Os próximos registros de horários 23:xx são históricos de **10/10/2026**. A configuração persistida e os erros de envio de **13/10** estão descritos na seção anterior. A regressão atual e a atualização de OpenAPI estão no [checklist de lançamento](MercadoPago_Checklist_Lancamento.md).

Às **23:35:58**, o usuário enviou a simulação da order `ORDTST01M4MC9DY4JN14A5RESQT2JVTW`. O diagnóstico e o backend comprovaram HMAC válido com a capitalização original do ID, timestamp fresco (3,02 s), GET oficial e **HTTP 200 em 1.031 ms**. A notificação do simulador indicava aplicação **5555811490405924**. Após o callback, permaneceram uma reserva e um recebimento de R$ 5,93; os dois snapshots verificados (pendente/aprovado) estavam processados. O valor fictício do corpo não substituiu o valor verificado pela API.

Esta prova valida a assinatura do simulador dessa configuração após o reload e a aplicação idempotente. A aprovação inicial deste Pix já tinha sido aplicada pelo worker às 23:26; o callback posterior não é a causa dessa primeira aprovação. O GET autenticado continua identificando a aplicação da cobrança como **4063897889583204**. A entrega espontânea autenticada não foi comprovada por essa simulação.

### Aplicação automática encontrada pelo MCP

O `application_list` oficial retornou uma aplicação: **4063897889583204**, nome **TestApp-1175c80a**, descrição de aplicação automática de teste. É a mesma aplicação identificada no GET das cobranças. Não foi necessário criar outra aplicação ou trocar credenciais.

Foi solicitada uma conferência de acesso distinta de repetir o simulador: entrar no Developers em janela privada com o usuário/senha de **Credenciais de teste → Dados das credenciais de teste** da aplicação principal e verificar se **TestApp-1175c80a** aparece em **Suas integrações**. A resposta está pendente. A [documentação de contas de teste de Checkout Transparente](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/resources/test-accounts) descreve login/verificação desse tipo de conta e limitações de seções do painel. Não solicitar senha/código pelo chat; não confundir o proprietário atual do simulador com a aplicação retornada na cobrança.

## Segredo salvo e novo ensaio Pix

O usuário confirmou o caminho `D:\UniCarona\.env`. A leitura privada constatou uma única entrada de segredo, sem aspas, espaços ou formato de Access Token; o arquivo foi salvo às **23:21:18**, depois da inicialização anterior da API às 23:19. API e worker foram reiniciados novamente às **23:26**, com o arquivo atualizado, mesmo Jar e readiness aprovado. Nenhuma credencial foi impressa.

| Evidência | Resultado |
|---|---|
| Pagamento interno criado pelo frontend | `365a5b46-0d9f-43f9-b978-48a4a34cf671` |
| Order confirmada pelo GET oficial | `ORDTST01M4MC9DY4JN14A5RESQT2JVTW` |
| Valor / aplicação no GET | R$ 5,93 / `4063897889583204` |
| Criação às 23:22 | HTTP 200; Pix/QR exibido em desktop/mobile; inicialmente pendente |
| Diagnóstico das três notificações | Timestamps frescos (2,31 s, 2,89 s e 2,34 s), assinatura/request ID presentes; HMAC não correspondeu ao arquivo privado atual nas duas variantes; respostas 401 |
| Observação isolada às 23:25, worker desligado | GET oficial `processed/accredited`, referência/valor correspondentes; banco ainda pendente, zero reservas e zero recebimentos |
| Recuperação às 23:26, worker restaurado | Consulta autenticada aplicou aprovação; uma reserva e um recebimento |
| UI às 23:27 | API `approved/confirmed`, “Reserva confirmada” em desktop/mobile, sem overflow ou erro JavaScript |

As notificações acima ocorreram antes da segunda reinicialização da API. O diagnóstico lê o segredo diretamente do arquivo a cada requisição e também não encontrou correspondência; isso não estabelece a causa da incompatibilidade. A aprovação local posterior veio da conciliação do worker, **não de um webhook autenticado**. Não houve atualização manual do estado financeiro ou nova cobrança para recuperar esse pagamento.

Foi solicitado um único envio no simulador da aplicação **4063897889583204**, evento **Order**, URL de teste atual e Data ID **ORDTST01M4MC9DY4JN14A5RESQT2JVTW**. O usuário respondeu com HTTP 200, mas a simulação pertenceu à **5555811490405924**, conforme verificação descrita acima. O acesso à **TestApp-1175c80a** ainda precisa ser conferido. A entrega espontânea autenticada continua pendente.

## Aplicativo confirmado por consulta autenticada

O GET oficial da order `ORDTST01M4MBFY8ES3BYJPQ30V7KXSPV` retornou **`integration_data.application_id = 4063897889583204`**. Portanto, o ID desse aplicativo foi confirmado pela API autenticada, além do diagnóstico dos corpos de notificações que ainda não passaram pela assinatura.

As simulações anteriores executadas pelo usuário indicavam **`application_id = 5555811490405924`** e passaram na assinatura/GET, com HTTP 200. Essa prova se refere à configuração usada pelas simulações. A notificação espontânea autenticada da aplicação da cobrança continua pendente.

### Configuração a conferir no portal

1. Conferir o acesso à conta de teste usando os dados de **Credenciais de teste → Dados das credenciais de teste**, em janela privada. Em Mercado Pago Developers, localizar **TestApp-1175c80a**, aplicação **4063897889583204**, correspondente ao GET da cobrança e ao `application_list` oficial. Se não aparecer mesmo na conta de teste, registrar essa limitação antes de solicitar outro envio.
2. Em Webhooks, conferir a URL **de teste** e o tópico **Order**, usando a URL atual registrada no arquivo privado `D:\UniCarona\.local\mp-webhook-url.txt`. O túnel é temporário; conferir seu estado antes de salvar.
3. Obter o segredo oficial dessa configuração e salvar somente como `MERCADO_PAGO_WEBHOOK_SECRET` no `D:\UniCarona\.env` privado. Avisar quando a alteração estiver salva; não compartilhar o segredo no chat.
4. Atualizar API/worker com a configuração privada e validar uma notificação espontânea: HMAC correspondente, timestamp fresco, GET da order, referência/valor, banco e UI. Uma simulação manual aprovada permanece uma evidência distinta.

Referência: [notificações de Checkout Transparente via Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/notifications). O acesso MCP atual por Bearer não permite configurar o webhook do proprietário por OAuth; por isso a configuração do portal foi solicitada ao usuário.

## Challenge 3DS com Device ID

Novo ensaio de cartão oficial `APRO-CHOK`, realizado com SDK/secure fields reais e Device ID presente:

| Evidência | Resultado |
|---|---|
| Pagamento interno | `eb3add91-3ea4-40c5-bd66-a111e0362278` |
| Order oficial | `ORDTST01M4MBFY8ES3BYJPQ30V7KXSPV` |
| Valor calculado pelo servidor | R$ 5,93 |
| Aplicação no GET autenticado | `4063897889583204` |
| Checkout interno | HTTP 200 |
| Primeira resposta de envio do cartão | HTTP 503 `PAYMENT_PROVIDER_UNAVAILABLE` |
| Recuperação pelo worker | Mesma cobrança identificada; comando concluído, payload/token apagado |
| Tokenização / envio | SDK oficial; backend recebeu token/metadados, sem PAN/CVV; Device ID presente (somente presença/comprimento registrados) |
| GET oficial em 23:09 | HTTP 200, `action_required/pending_challenge`, valor e referência correspondentes |
| iframe observado em 23:10 | Documento em `www.mercadopago.com.br` respondeu HTTP 403; iframe vazio, sem botão Confirmar |
| CSP | Nenhuma violação observada |
| Banco | Pagamento pendente, zero reservas e zero recebimentos |
| Responsividade | Desktop/mobile sem overflow |

A presença do Device ID não resolveu a abertura do iframe nesse ensaio. O 403, isoladamente, não identifica sua causa: permanece necessário verificar o acesso/configuração desse cenário no provedor. A causa não foi atribuída a antifraude, token, navegador ou segredo de webhook sem evidência.

Segundo a [documentação oficial de 3DS em Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-management/integrate-3ds), `APRO-CHOK` deve exibir o Challenge em iframe; a conclusão usa o botão Confirmar, seguida de consulta da order. Evento do iframe não aprova pagamento. Este ensaio não concluiu a autenticação e não deve ser apresentado como 3DS interativo aprovado.

### Evidência de assinatura da nova cobrança

Após a criação, o diagnóstico recebeu notificações com o mesmo ID de order e aplicativo indicado `4063897889583204`. Request ID/digest presentes, timestamps frescos (2,27 s e 2,68 s); HMAC não correspondeu ao segredo atual nem no manifesto normalizado nem na caixa original. Backend retornou 401 em 16 ms e 15 ms. Esses corpos não foram autenticados e não servem como prova de estado financeiro. O estado acima veio do GET oficial e da recuperação transacional do worker.

## Critério para liberar estas pendências

- Notificação espontânea com assinatura válida da configuração correspondente, GET autenticado e atualização idempotente de banco/UI.
- Challenge interativo oficial acessível e concluído, seguido de GET e resultado correto; autenticação adicional não pode ser desativada para contornar o 403.
- Resolver separadamente as decisões de diferença financeira e habilitação/condições de repasse registradas no [checklist de lançamento](MercadoPago_Checklist_Lancamento.md).

O código financeiro e o pacote desta rodada permaneceram iguais ao último estado verificado. O ensaio acrescentou evidência externa; não repetiu a suíte local nem substituiu a homologação pendente.
