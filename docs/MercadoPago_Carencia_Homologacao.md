# Mensalidade — carência, expiração e reativação

## Resultado de 15/10/2026, 14:49–14:54

Foram usados Orders e Pix do vendedor de teste oficial `3748838311`, aplicação `4063897889583204`, PostgreSQL dedicado e os serviços normais do produto. Não foram alterados estados financeiros ou datas por SQL para montar estes resultados.

**Limite da evidência:** somente o relógio da manutenção mensal foi controlado no classpath de testes. A data virtual passou de 31/10 para 01/11 e para o fim das 72 horas em 04/11. Não decorreram 72 horas reais. Datas de pagamentos, assinatura HMAC, consultas Orders, API principal e callbacks usaram o relógio real. O ensaio comprova integração com o provedor real sob fronteiras temporais controladas; não substitui observação prolongada do calendário em produção.

O worker ficou parado durante as cinco cobranças deste ensaio. Não houve sincronização de aprovação pela interface ou chamada de `/sync`: os callbacks espontâneos autenticados consultaram Orders e aplicaram os pagamentos. O worker foi restaurado às 14:54 com o mesmo Jar da API.

| Etapa | Resultado |
| --- | --- |
| Mensalidade inicial do cenário de renovação | R$ 37,30, 14 reservas, um recebimento; order `ORDTST01M50AX6XW3TNATV93CZ1D2P0Z` |
| Mensalidade inicial do cenário de expiração | R$ 37,30, 14 reservas, um recebimento; pagamento interno `2b404fa6-5ff8-48c8-a0de-14e1cc039045` |
| Um milissegundo antes da virada local | Contratos ainda ativos, sem transição antecipada |
| Entrada na carência, 01/11 às 00h em São Paulo | 26 vagas retidas por contrato; 14 reservas anteriormente pagas preservadas; nenhuma reserva nova paga pela carência |
| Renovação dentro da carência | R$ 69,26; order `ORDTST01M50B14S898PABQR88MAB12MZ`; 26 reservas, um recebimento, contrato anterior `renewed`, retenções zeradas |
| Um milissegundo antes das 72h | Retenções preservadas, sem expiração antecipada |
| Limite exato em 04/11 às 00h | Contrato `expired`, retenções zeradas; 14 reservas pagas anteriores preservadas |
| Pix aprovado depois da expiração controlada | R$ 69,26; order `ORDTST01M50B1AP21X3GN0TQDA8YYY9T`; zero reservas novas, um `receipt_credit` integral; contrato anterior continua expirado |
| Reativação por nova contratação | Pix de R$ 69,26, order `ORDTST01M50B5B222PSK7S7NXBXK13ZC`; novo contrato ativo, 26 reservas e um recebimento; contrato antigo preservado |

Todos os callbacks utilizados tiveram HMAC válido e HTTP 200. Repetir a manutenção não duplicou transições; os callbacks adicionais da renovação preservaram um único recebimento. A soma global do ledger permaneceu zero.

### Crédito em análise

A tentativa de usar crédito na reativação retornou `409 REFUND_REQUIRES_REVIEW`: esta conta sintética possuía uma análise financeira anterior. O bloqueio foi preservado. A reativação foi paga por novo Pix de teste, com crédito utilizado igual a zero. O crédito gerado pela aprovação tardia está registrado; este ensaio não comprova sua disponibilidade para gasto nem a resolução de análises anteriores.

### Interface

O pagamento tardio informa que o valor voltou ao crédito e oferece a carteira. A nova contratação mostra **Reserva confirmada** e 26 trechos. Desktop e viewport de 390 × 844 foram inspecionados, sem overflow horizontal; capturas privadas em `.local/mp-oct15-grace-*.png`. Nenhuma mudança de layout foi necessária.

## Implementação e testes

- `MaintenanceClockConfiguration`: `Clock.systemUTC()` como padrão, sem variável de ambiente ou endpoint para alterar o tempo.
- `MaintenanceRepository`: seleção de contratos e cálculo da data civil usam o mesmo relógio de manutenção, convertido pelo fuso da universidade.
- `MaintenanceTransactions`: compara o prazo de carência com esse relógio.
- `MutableMaintenanceClock` e `MonthlyGraceSandboxHarness`: somente classpath de testes; canal stdin, banco/vendedor de teste e contratos sintéticos permitidos. Não entram no Jar executável.
- `MaintenanceHttpIT`: dois testes novos cruzam o mês e o limite exato da carência, preservam direitos pagos, verificam repetição, renovação e bloqueio do vínculo expirado sem editar suas datas financeiras.

**95 testes distintos aprovados nesta etapa:** cinco unitários de manutenção, 20 HTTP de manutenção, 19 unitários de cliente/assinatura e 51 HTTP Mercado Pago. Os HTTP usam PostgreSQL real com fixtures explícitas; os cinco Pix acima são evidência externa separada. Logs: `.local/mp-oct15-grace-clock-tests.log` e `.local/mp-oct15-grace-payment-regression.log`.

Relatórios privados: `.local/mp-oct15-grace-official.json`, `.local/mp-oct15-grace-reactivate.json` e `.local/mp-oct15-grace-runtime.private.json`. Não executar novamente o orquestrador completo sobre contratos que já transitaram; os relatórios são evidência histórica, não um comando para reaplicar as fases.

## Conferência final, 14:59

API PID 14904 e worker PID 11108 prontos, ambos no Jar SHA-256 `d952211f795ec35d2bd1c4a6c9e4d07ea3af012f24ef0fff3edc1aadfcba894a`, sandbox V24. O Jar contém a configuração padrão de relógio; não contém o relógio mutável nem o harness de testes. Prova privada: `.local/mp-oct15-grace-runtime-final.private.json`.

Após a conciliação normal do worker, uma leitura sem mutações confirmou os cinco pagamentos aprovados, um recebimento por pagamento, zero reservas no pagamento tardio e 26 na renovação e na nova contratação. Os vínculos anteriores continuaram `renewed`/`expired`, os dois novos ficaram ativos, nenhuma retenção restante e ledger zero. Prova: `.local/mp-oct15-grace-final-readonly.json`.

Backup e restauração concluídos às 14:56:16: V24, 50 tabelas, 1.865 registros e 364.268 bytes; esquema e dados correspondem ao mesmo snapshot consistente, ledger zero. A cópia temporária criada para verificação foi removida. Relatório privado: `.local/mp-backup-proof/2026-10-15T17-55-52-575Z_1d12af9f71b3/verification.private.json`. Isso verifica a recuperação local e não a infraestrutura produtiva ou a restauração das chaves.
