# Entrega técnica — Mercado Pago no UniCarona

Estado verificado em **15/10/2026**. Integração funcional no sandbox oficial e preparação de produção documentada. Não houve ativação de cobranças reais nem de repasse automático a motoristas.

## Arquitetura e decisão

Checkout Transparente via **Orders**, Pix e **Card Payment Brick oficial**. A API Java/Spring calcula valores, congela comandos e aplica efeitos transacionais no PostgreSQL; o navegador só tokeniza cartão, inicia a cobrança e apresenta o estado do servidor. Outbox e worker recuperam comandos incertos com a mesma identidade e conciliam Orders por GET.

Mensalidade tem cobrança manual por período: nova confirmação de agenda/vagas, carência de até 72h, renovação e reativação. O MVP não usa débito recorrente silencioso. Pro, boleto, assinatura automática e split foram comparados no [relatório de integração](MercadoPago_Integracao.md).

A alternativa de repasse permitida no objetivo foi implementada: bruto, comissão, líquido, saldo devido e estado da operação registrados. A conta atual não tem capacidade de Pix automático/split comprovada; `UnavailableTransferProvider` impede saque antes de debitar o motorista. O ledger não é comprovante de transferência bancária.

## Webhook e estado confiável

`POST /api/v1/webhooks/mercadopago` verifica HMAC/timestamp e identidade, consulta a API oficial, confere referência/valor/transação e aplica o snapshot. Eventos repetidos, atrasados e consultas concorrentes não duplicam reservas, recebimentos ou ajustes. Recebimento original e resultados de devolução/contestação são registros distintos.

Pagamento sem vaga elegível gera crédito, não overbooking. PIN não libera dinheiro. Cancelamentos e conclusão de trechos usam as regras de parcelas já existentes. Mensalidades renovadas preservam os direitos pagos de períodos anteriores.

## Endpoints centrais

Todos os caminhos abaixo têm prefixo `/api/v1`.

| Operação | Rotas |
| --- | --- |
| Configuração pública e cobrança própria | `GET /payments/config`, `POST /payments/{id}/checkout`, `GET /payments/{id}`, `GET /payments/{id}/summary` |
| Cartão, estado externo e recuperação | `POST /payments/{id}/card`, `GET /payments/{id}/gateway`, `POST /payments/{id}/sync` |
| Callback oficial | `POST /webhooks/mercadopago` |
| Carteira e devoluções | `GET /wallet`, `GET /wallet/refund-review`, `POST/GET /transfers`, `GET /transfers/{id}/refunds` |
| Regularização da dívida própria | `GET /wallet/debt`, `POST /wallet/debt/checkout`, `GET /wallet/debt/payments/{id}`, `POST /wallet/debt/payments/{id}/sync` |
| Administração financeira | `GET /admin/reconciliation`, `GET /admin/payment-reviews`, `GET /admin/payment-reviews/{id}`, `POST /admin/payment-reviews/{id}/recheck`, `POST /admin/payment-reviews/{id}/resolve` |

Ownership, sessão, papel administrativo, Origin/CSRF, contratos estritos e idempotência são aplicados conforme a operação. Valores/destinos financeiros não são aceitos do navegador. A documentação OpenAPI do runtime inclui a nova resolução e os três headers obrigatórios.

## Banco e arquivos

Migrations até **V25**, sem editar migrations antigas para acomodar resultados. Pagamentos, pedidos, reservas, contratos, parcelas, ledger e transferências foram preservados. V14–V22 acrescentam Orders/comandos/devoluções/evidências e direitos mensais; V23–V24 acrescentam avaliação de dívida, recebíveis, Pix de regularização, alocações e reversões; V25 acrescenta resoluções administrativas e identidades externas conciliadas.

Arquivos centrais da implementação:

- `backend-java/src/main/java/com/unicarona/payment/provider/MercadoPagoOrdersClient.java`, `MercadoPagoStatus.java`, `MercadoPagoSignature.java` e configuração em `payment/config`.
- `payment/service/MercadoPagoService.java`, `MercadoPagoTransactions.java`, `MercadoPagoReconciliation.java`, serviços/transações de devolução, dívida e resolução; controllers correspondentes em `payment/controller`.
- `job/config/MaintenanceClockConfiguration.java`, `job/repository/MaintenanceRepository.java`, `job/service/MaintenanceTransactions.java` para fronteiras mensais testáveis sem alterar o tempo de pagamentos.
- `backend-java/src/main/resources/db/migration/V14*` até `V25*`, `api/request-contracts.json` e `security/SecurityConfiguration.java`.
- `dist/mercado-pago.js` e `dist/backend-ui.js`: SDK/tokenização, checkout/estados, carteira, regularização e administração.
- `shared/IdempotencyExecutor.java`: serialização estável dos comandos de cartão e conciliação, com compatibilidade para hashes anteriores; demais contratos preservados.
- Testes de provider/assinatura, `MercadoPagoHttpIT`, regressões financeiras/mensais/identidade, contratos antigos e compatibilidade Flyway; harnesses ficam apenas no classpath de teste.
- `.env.example`, exemplos em `config/`, scripts de sandbox/backup em `backend-java/scripts/` e os relatórios de operação/homologação em `docs/`.

## Devoluções, dívidas e decisão administrativa

Devoluções integral/parcial/lote usam a API oficial, valor e origem do servidor, identidade estável e confirmação por GET. O histórico de devoluções é conservado mesmo se uma consulta posterior omitir operações. Resposta perdida não autoriza criar outro comando ou restaurar saldo sem prova.

Crédito de cancelamento já gasto, cujo pagamento foi estornado: dívida separada e novas compras bloqueadas até Pix confirmado. Estorno da regularização reabre somente o valor comprovado. Crédito retido e dívida não se confundem com saldo de motorista.

Conciliação administrativa exige justificativa, prévia do servidor e nova consulta oficial. Ajusta uma vez o crédito já devolvido e preserva a dívida. **Contestações de caronas consumidas sem crédito de cancelamento permanecem em análise para decisão administrativa**, como o usuário escolheu; não há alocação automática dessa perda. [Procedimento e prova oficial](MercadoPago_Conciliacao_Administrativa.md).

## Evidências e limites

| Fluxo | Evidência |
| --- | --- |
| Pix → callback autenticado → GET → banco → reserva → interface | Ensaios oficiais, incluindo worker pausado para atribuir a confirmação ao callback; relatórios de homologação |
| Cartão tokenizado, aprovação, recusa e saldo insuficiente | Orders/SDK oficiais, recibo/reserva ou ausência deles, payload apagado e interface |
| 3DS interativo | `authenticated` e `processed/accredited`, uma reserva/recebimento; [prova e causalidade](MercadoPago_3DS_Homologacao.md) |
| Mensalidade e carência | Pix oficiais; renovação, expiração, crédito tardio e reativação, com relógio de manutenção controlado; [limites do ensaio](MercadoPago_Carencia_Homologacao.md) |
| Cancelamento, devolução integral/parcial/lote | Operações oficiais, GET, ledger, histórico e recuperação documentados |
| Dívida/regularização | Estorno de crédito já consumido, bloqueio e Pix oficial de quitação; [evidência](MercadoPago_Regularizacao_Dividas.md) |
| Conciliação administrativa | Estorno oficial de R$ 5,93, ajuste único pela interface, crédito anterior de R$ 69,26 preservado, zero análises do passageiro e ledger zero |
| Regressão da etapa final | 205 testes distintos aprovados em conjuntos complementares; última execução: 31 unitários + 65 de integração. Testes HTTP usam PostgreSQL real e upstream explícito; não são contabilizados como operações oficiais |
| Runtime e recuperação | API/worker no mesmo Jar, V25, backup/restauração de 52 tabelas/1.939 registros com esquema/dados correspondentes |

Inspeção final cobriu identidade/valor/versão, duplicação, concorrência de dois administradores, histórico imutável, efeitos antes da verificação, liberação de vagas, cancelamentos, cálculo cumulativo de estornos e UX em desktop/celular. Fixtures, relógio controlado, capturas e resultados oficiais estão identificados separadamente. Não houve validação em aparelhos físicos, espera real de 72h nem cobrança produtiva.

A repetição da solicitação administrativa original após reiniciar a API retornou HTTP 200 com a mesma resolução, um único ajuste e ledger zero. A nova consulta oficial preservou o crédito de R$ 69,26 e nenhuma dívida ou análise pendente do passageiro do ensaio. Isso verifica também a estabilidade das chaves de idempotência entre processos; as permutações de hashes antigos foram cobertas nos testes, sem alterar valores financeiros do sandbox por SQL.

## Produção: configuração externa necessária

Usar os exemplos de [teste](../config/mercadopago.test.env.example) e [produção](../config/mercadopago.production.env.example), complementando a configuração de autenticação/e-mail/armazenamento do exemplo raiz. API e worker devem compartilhar o banco e suas próprias chaves do mesmo ambiente.

Configurar privadamente `MERCADO_PAGO_ACCESS_TOKEN`, `MERCADO_PAGO_PUBLIC_KEY`, `MERCADO_PAGO_WEBHOOK_SECRET`, `PAYMENT_PROVIDER`, `MERCADO_PAGO_ENVIRONMENT`, `NODE_ENV`, `APP_ORIGIN`, `DATABASE_URL` e segredos da aplicação. Teste/produção têm bancos e chaves separados. Não inferir ambiente pelo prefixo `APP_USR`.

Antes de ativar cobranças reais: domínio HTTPS estável, webhook/segredo da aplicação correta, conta apropriada, validação comercial/financeira do modelo, tarifas e meio de repasse reais, responsáveis e monitoramento/backups com recuperação das chaves. Manter `MERCADO_PAGO_MARKETPLACE_APPROVED=false` até essa aprovação externa. Essa flag local não concede capacidades no Mercado Pago.

Não se incluem nesta entrega a contratação/habilitação comercial do provedor, o deploy da infraestrutura produtiva, pagamentos reais, recorrência automática ou Pix automático a motoristas. O recebimento/repasse produtivo continua bloqueado até haver configuração e capacidade comprovadas. [Runbook](MercadoPago_Operacao.md) · [auditoria dos 30 requisitos](MercadoPago_Checklist_Lancamento.md).
