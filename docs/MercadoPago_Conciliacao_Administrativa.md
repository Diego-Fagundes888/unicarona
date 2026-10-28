# Conciliação administrativa de pagamentos

## Regra confirmada pelo usuário em 15/10/2026

Quando uma contestação definitiva retira o valor de uma carona já realizada, sem crédito de cancelamento envolvido, **manter em análise para decisão administrativa**. Não criar dívida do passageiro, descontar do motorista ou atribuir a perda à plataforma automaticamente.

A regra de crédito de cancelamento continua distinta: registrar dívida sobre a parcela já utilizada e bloquear novas compras até regularizar. Concluir uma análise não quita essa dívida.

## Operação

1. Em **Administração → Conciliação → Casos financeiros em análise**, abrir **Examinar caso**.
2. Informar o motivo e selecionar **Consultar novamente no Mercado Pago**. A API consulta a order oficial e mantém a evidência sanitizada.
3. Conferir a prévia de **Concluir conciliação**: crédito a ajustar por valor já devolvido, dívida anteriormente registrada naquela origem e dívida atual do passageiro. Os valores vêm do servidor.
4. Informar a justificativa e confirmar. O servidor consulta o provedor novamente; se a evidência mudou, exige nova conferência. Reenviar a mesma solicitação não repete o ajuste.

Casos com devoluções em processamento, histórico incompleto, saldo incompatível, contestação indefinida ou perda fora do crédito de cancelamento permanecem em análise. A interface informa o impedimento. Não limpar flags ou corrigir valores por SQL.

## Efeitos permitidos

- Devolução externa comprovada de crédito de cancelamento: ajustar uma única vez a parcela ainda retida no crédito e registrar as operações externas conciliadas. A dívida da parcela já gasta permanece na conta a receber.
- Contestação definitiva confirmada na order **e na transação**, integralmente coberta pela avaliação de crédito de cancelamento: conciliar a parcela retida sem alterar direitos pagos de outras caronas.
- Reversão da contestação confirmada na transação: preservar a baixa da dívida da origem e devolver crédito previamente regularizado ou ajustado, quando houver prova suficiente. O histórico não é apagado.
- Nova devolução sobre uma origem já conciliada: reabrir somente o valor ainda não conciliado; não repetir operações anteriores.
- Carona consumida sem crédito de cancelamento: manter em análise administrativa, sem destino financeiro automático, conforme decisão do usuário.

As páginas oficiais de [status da order](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/payment-management/status/order-status) e [status da transação](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/payment-management/status/transaction-status), consultadas em 15/10, apresentam descrições distintas de contestação. Por isso o sistema não cria/desfaz dívida com base apenas no detalhe da order: exige status e detalhe concordantes na transação correspondente. Identidade, valor e versão são validados antes dos efeitos, inclusive para recebimentos anteriormente reembolsados.

## API e banco

- `GET /api/v1/admin/payment-reviews/{id}` inclui prévia de resolução, impedimentos e chave da evidência; continua restrito a administrador.
- `POST /api/v1/admin/payment-reviews/{id}/resolve`: exige `reason`, `evidenceKey`, sessão administrativa, Origin/CSRF e chave de idempotência. Não aceita valor ou destino financeiro do navegador.
- V25: `mercado_pago_review_resolutions` e `mercado_pago_resolved_refunds` conservam autor, justificativa, origem, evidência, journal e identidades externas. Histórico imutável, referências únicas e guard de ledger diferido.
- `review_receipt_id`/`review_checked_at` conservam a consulta verificada separadamente do ciclo do recebimento; a versão não regride.
- O ajuste de crédito usa `external_credit_reversal`, ligado ao comprovante de resolução. Crédito já ajustado deixa de contar como retenção pendente. Contestação revertida registra a reposição quando aplicável.

Auditoria: `mercadopago_resolution_requested` e `mercadopago_review_resolved`. A notificação ao passageiro não afirma que outras análises ou dívidas foram encerradas.

## Verificação

Os testes HTTP/PostgreSQL usam upstream explicitamente simulado e cobrem repetição, autorização, Origin/CSRF, valores extras rejeitados, indisponibilidade do provedor, evidência alterada/omitida, histórico imutável, estornos parciais cumulativos, contestação/reversão com transação discordante e preservação do saldo de motorista após carona efetivamente concluída. Operações oficiais de sandbox e capturas da interface são evidências separadas e devem ser registradas abaixo após o ensaio.

### Evidência oficial — 15/10/2026, 20:05–20:11

O caso anterior `5071ee79-e520-4025-9eba-47ddefb66404`, cuja dívida já havia sido regularizada, foi consultado e concluído pela interface administrativa. Não gerou novo desconto nem nova dívida; o crédito de R$ 69,26 foi preservado.

Foi criada outra carona por APIs normais, paga por Pix oficial, cancelada pelo motorista e devolvida integralmente no provedor de teste. Pagamento `5b6d0fb4-17a5-4947-949f-160914e15f34`, order `ORDTST01M50X1GM9NQ2MFD43GXA194Q1`, devolução `REF01M50X1P29ZF3DGBFMRBFK4933`, R$ 5,93. A primeira tentativa de abrir revisão ocorreu antes da notificação e retornou 404; após a conciliação, a mesma preparação foi retomada sem criar outra cobrança ou devolução.

O crédito passou a R$ 75,19 com o cancelamento. A prévia mostrou ajuste de R$ 5,93 e dívida zero. A conclusão na interface consultou novamente o Mercado Pago e registrou um único journal de ajuste, resolução `6a7430d2-811c-4f2c-a29d-77f8f0d553b8`. O crédito voltou a **R$ 69,26**, preservando o saldo anterior. Permaneceram um recebimento original, uma resolução, nenhuma análise pendente do passageiro e soma do ledger zero. Nenhum estado financeiro foi alterado por SQL de preparação.

Callbacks assinados da order foram capturados com HTTP 200. O worker também conciliou o estorno; não atribuir seu primeiro reconhecimento exclusivamente ao callback. A resolução administrativa exige uma nova consulta verificada, independentemente de quem observou o estorno primeiro.

Desktop de 1280 × 900 e celular de 390 × 844 foram conferidos, sem overflow horizontal. Capturas e evidência sanitizada: `.local/mp-oct15-review-*.png` e `.local/mp-oct15-review-official.json`.

**205 testes distintos aprovados em execuções complementares:** 198 na regressão ampla, mais os cenários de contestação da regularização, dois administradores simultâneos e cinco verificações de contratos antigos. A última execução passou **31 unitários + 60 HTTP Mercado Pago + 5 CompatibilityAuditIT**, após corrigir a serialização de idempotência entre JVMs. Os testes cobrem as duas ordens anteriores do comando de cartão e as seis ordens da conciliação: a mesma chave/corpo recupera o resultado, mas dados alterados continuam rejeitados. O conjunto de concorrência também foi executado isoladamente. Não somar repetições como testes novos. Logs: `.local/mp-oct15-review-final-verified.log`, `.local/mp-oct15-review-final-identity.log`, `.local/mp-oct15-review-concurrency.log` e `.local/mp-oct15-review-final-idempotency.log`.

API PID 17716 e worker PID 15440, mesmo Jar SHA-256 `533992c9a0d988aa990da198fd51c20b23140bec2ac3bb9d62799954da54cd9d`, sandbox V25, prontos às 20:11. Detector Impeccable sem achados e JavaScript válido. Backup restaurado às 20:09:03: 52 tabelas, 1.939 registros, esquema/dados correspondentes e ledger zero; cópia temporária removida. Relatórios privados preservam o snapshot e a identidade do runtime.

**Revalidação às 20:19:** API PID 9248, worker PID 14304, Jar SHA-256 `6bcb583323e636d94510113401c5dc05f167a375e8a97f758cf8dd090e260fe0`. A nova consulta oficial pelo fluxo normal de sincronização preservou a mesma resolução, ajuste único, crédito de R$ 69,26, zero dívida/análises do passageiro e ledger zero.

**Artefato final após o ajuste de idempotência, às 20:34:** API PID 19108 e worker PID 13320, Jar SHA-256 `834906c92d735d75b40006054e0dfe66cefa696513ca90de7cc6b7042eaa9f4e`. A repetição da solicitação original após o reinício retornou HTTP 200 com a mesma resolução, um journal e ledger zero; neste caso nativo o hash antigo já coincidia com a ordem canônica. As outras ordens foram verificadas no banco descartável dos testes. Nova consulta oficial preservou os mesmos saldos e nenhuma análise pendente. Auditoria somente de leitura: `.local/mp-oct15-goal-completion-audit.json`; replay privado: `.local/mp-oct15-review-restart-replay.private.json`; runtime: `.local/mp-oct15-review-idempotency-runtime.private.json`.
