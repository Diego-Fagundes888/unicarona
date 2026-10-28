# Challenge 3DS — comprovação oficial de 15/10/2026

O Challenge interativo foi concluído no sandbox oficial do Mercado Pago, sem substituir SDK, upstream ou resposta por fixtures. A interface exibiu a validação de segurança do ambiente de teste, recebeu a confirmação e terminou em **Reserva confirmada**.

| Evidência | Resultado |
| --- | --- |
| Pagamento interno | `a430f2f3-e081-4dc0-8171-98cf163ad52d` |
| Order | `ORDTST01M50A23HZM19T67Z5YT2M3X9Q` |
| Aplicação do vendedor de teste | `4063897889583204` |
| Valor calculado no servidor | R$ 5,93 |
| Antes da confirmação, 14:35:11 | GET oficial `action_required/pending_challenge`; banco pendente; nenhuma reserva ou recebimento |
| Após a confirmação, 14:36:11 | GET oficial `processed/accredited`, `transaction_security.status=authenticated`, sem URL de Challenge |
| Efeito interno | Uma reserva e um recebimento, estado `APPROVED` |
| Segurança | Payload cifrado do comando apagado; soma do ledger zero; nenhum PAN/CVV enviado ao backend |
| Callback espontâneo às 14:36:00 | HMAC válido, HTTP 200 em 281 ms |

**Causalidade:** o snapshot aprovado verificado foi registrado às 14:35:59.412, antes do callback acima. A primeira aprovação deste ensaio foi aplicada pela consulta autenticada de conciliação/sincronização. O webhook posterior foi autenticado e processado sem duplicar os efeitos. Não atribuir a primeira aprovação ao callback nem ao evento `COMPLETE` do iframe.

Documentação consultada na data do ensaio: [3DS para Orders](https://www.mercadopago.com/developers/pt/docs/checkout-api-orders/payment-management/integrate-3ds). Foi usado o titular sintético documentado `APRO-CHOK`. O cenário de recusa interativa ainda deve ser distinguido deste resultado aprovado.

Capturas privadas conferidas: `.local/mp-oct15-3ds-fresh-challenge.png` e `.local/mp-oct15-3ds-fresh-approved.png`. Observações sanitizadas antes/depois e IDs estão em `.local/mp-oct15-3ds-fresh-*.json`; não publicar URLs temporárias de autenticação, dados de cartão, sessões ou credenciais.

Este resultado encerra a pendência específica do **Challenge 3DS aprovado interativamente**. A carência mensal externa e a preparação operacional/financeira de produção são verificações separadas. Os registros anteriores de iframe 403 são históricos e não descrevem este ensaio.
