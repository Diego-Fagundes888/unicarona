# Dívida e regularização do passageiro

Regra definida pelo responsável pelo produto em 15/10/2026: registrar a dívida do passageiro e bloquear novas compras até regularizar quando um pagamento for estornado ou contestado depois de o crédito de cancelamento ter sido utilizado.

## Comportamento implementado

1. A assinatura do webhook é validada e a aplicação consulta a order na API oficial. A dívida nunca nasce do status informado pelo navegador ou pelo corpo da notificação.
2. O servidor identifica o pagamento de origem, seu titular, o crédito concedido, as devoluções próprias e o crédito consumido. Não atribui ao passageiro uma contestação de viagem já consumida por essa regra.
3. Devoluções próprias em andamento e histórico externo incompleto exigem conferência antes de atribuir dívida. Uma contestação `in_process` não é perda confirmada: a avaliação exige `settled`.
4. A avaliação separa crédito ainda retido e valor devido. O saldo a receber é registrado na conta `passenger_receivable`, separada de crédito para compra, saldo do motorista e comissão. Lançamentos anteriores permanecem imutáveis.
5. Com dívida positiva, novos checkouts Pix e cartão são recusados com `409 PASSENGER_DEBT`, com ou sem crédito. O bloqueio é conferido sob lock do passageiro e também antes de enviar comandos nunca iniciados. Comandos já enviados com resultado incerto preservam sua identidade para recuperação.
6. A carteira mostra o valor devido e oferece **Regularizar com Pix**. O servidor calcula o valor e cria uma cobrança Orders exclusiva de regularização, sem juros ou taxa adicional. O cliente não envia preço.
7. Repetições e cliques concorrentes reutilizam a cobrança aberta. Timeout mantém o mesmo comando congelado. Webhook e conciliação usam o cliente oficial existente e a referência `UNICARONA_{id}`.
8. Aprovação verificada baixa a dívida, em transação com o lançamento financeiro e a auditoria. A regularização não cria carona, reserva ou comissão. Copiar o QR e retornar à página não quitam a dívida.
Se a primeira consulta já encontra uma devolução parcial processada, recebimento e reversão são lançados atomicamente: somente o valor líquido quita a dívida. Devolução integral observada antes da aprovação nunca a libera; aprovação antiga posterior não revive a cobrança. Histórico parcial incompleto permanece sob análise.

9. Um estorno verificado da própria regularização reabre somente o valor efetivamente perdido, uma vez. Crédito excedente ainda disponível é revogado; crédito já consumido volta a ser valor devido.
10. Se o provedor confirmar `reimbursed` após uma contestação definitivamente perdida, a dívida correspondente é revista uma vez. Valores já regularizados retornam como crédito. Outros estornos associados à mesma origem continuam exigindo análise para evitar baixas indevidas.

## API e persistência

| Operação autenticada | Finalidade |
|---|---|
| `GET /api/v1/wallet/debt` | Valor devido, crédito retido, bloqueio e cobrança aberta do próprio usuário |
| `POST /api/v1/wallet/debt/checkout` | Preparar e enviar o mesmo Pix de regularização; exige idempotência, Origin e CSRF |
| `GET /api/v1/wallet/debt/payments/{id}` | Consultar cobrança própria; outro titular recebe 404 |
| `POST /api/v1/wallet/debt/payments/{id}/sync` | GET oficial ou recuperação do comando já preparado |

O webhook permanece `POST /api/v1/webhooks/mercadopago`. O despacho identifica se a referência pertence a uma regularização ou a um pagamento de carona. Ambos passam pela validação oficial de identidade e valor. O worker processa `mercadopago_debt_pix` e verifica orders pendentes com lease.

V23: `passenger_debt_assessments`. V24: `passenger_debt_payments`, `passenger_debt_allocations`, `passenger_debt_releases` e a categoria de ledger `passenger_receivable`. Identidades, valores congelados e baixas não podem ser apagados ou reescritos; reversões possuem lançamentos próprios. A conta a receber pode ser negativa, mas não pode gerar saldo positivo. Crédito para caronas e saldo do motorista mantêm a proteção contra saldo negativo.

O crédito retido por estorno externo mantém a análise financeira existente. Regularizar a parte consumida remove o bloqueio de novas compras por dívida; não libera automaticamente todo crédito sob análise nem outras restrições da conta.

## Verificação em 15/10/2026

- 19 testes unitários do cliente Orders e da assinatura: passaram.
- 51 testes HTTP de Mercado Pago com PostgreSQL real e upstream simulado: passaram; incluem bloqueio, dívida, regularização, acesso por outro titular, preço adulterado, duplicação, concorrência, recuperação de timeout, devolução e contestação revertida.
- 11 testes de identidade e inventário/contratos da API: passaram.
- 38 testes de regressão de ledger, pagamentos e transferências: passaram.
- `node --check dist/backend-ui.js`: passou. Detector estático Impeccable: nenhum achado no arquivo alterado.
- Carteira pendente e confirmada inspecionada no navegador em desktop e viewport móvel de 390 × 844; sem rolagem horizontal. O ensaio de interface isolado usa provedor simulado e não comprova integração externa.
- Jar compilado e sandbox local atualizado por Flyway para V24. Backup anterior à atualização foi restaurado e comparado: V22, 46 tabelas, 1.119 linhas, esquema e dados correspondentes, ledger zero. A cópia isolada de restauração foi removida.

Logs e screenshots ficam na pasta privada ignorada `.local/mp-oct15-debt-*`. Não contêm credenciais nos relatórios públicos. A prova de regularização com o sandbox oficial é registrada separadamente na homologação, sem atribuir ao ensaio simulado uma confirmação externa.

Backup posterior ao ensaio oficial: V24, 50 tabelas, 1.223 linhas, dump de 312.908 bytes, snapshot consistente, esquema e dados correspondentes, ledger zero. Restauração verificada às 13:58:59 e cópia isolada removida. O dump privado não substitui backup de produção.

## Ensaio oficial

Uma dívida de R$ 5,93 foi gerada por estorno oficial após uso integral do crédito. Os quatro checkouts Pix/cartão, com/sem crédito, foram bloqueados. A regularização foi iniciada pela carteira e aprovada por webhook espontâneo, com o worker parado: dívida zero, um recebimento e nenhuma reserva adicional. A preparação de nova compra foi aceita após a baixa; o pedido não enviado ao provedor foi cancelado. Veja o registro completo e a causalidade em [Homologação](MercadoPago_Homologacao_Pendencias.md). A integração geral ainda depende das etapas restantes descritas naquele documento.
