# Dados transitórios de checkout — V41

26/10/2026. Implementação na fonte, com demonstração privada V26 preservada.
Este complemento não encerra a retenção financeira/contratual, não publica
minutas e não certifica os 44 requisitos. Backend/JS validados no escopo abaixo;
conferência visual pendente da liberação já solicitada.
Privacidade r5 continua em `draft`, com a fonte r4 preservada em `legal/history/`;
Motorista r3 e os outros documentos conservam suas revisões.

## Diagnóstico e decisão por finalidade

Os registros `mercado_pago_pix_commands.payer_email` e
`passenger_debt_payments.payer_email` conservavam o contato de criação depois
de conhecido o pedido externo. QR Code, imagem e links podiam permanecer em
`mercado_pago_orders` e voltar em leituras após o checkout perder finalidade.
`ticket_url` era armazenado sem consumidor na interface. O fluxo de regularização
também permitia começar um envio nunca iniciado mesmo depois do seu prazo.

| Dado | Coleta e uso real | Fim da finalidade e ação |
| --- | --- | --- |
| E-mail de criação Pix | Cópia do e-mail já informado, para formar a solicitação externa e recuperar seu resultado com a mesma identidade | Resultado da criação conhecido ou falha definitiva: retirar a cópia. Envio nunca iniciado e vencido: encerrá-lo sem chamar provedor. Envio iniciado sem resultado mantém o necessário à recuperação; vencimento interno não prova ausência de recebimento |
| Token e dados do comando de cartão cifrado | Token de uso específico e dados necessários ao provedor; sem PAN/CVV no servidor | Resultado conhecido já elimina conteúdo pelo fluxo existente. A V41 trata também comando legado com referência conhecida ou nunca iniciado e vencido. Resposta incerta não é descartada como se fosse uma falha definitiva |
| QR Code/imagem Pix | Apresentar um checkout próprio ainda utilizável | Ocultar nas leituras assim que vencer/perder finalidade; remover do registro no processamento. Pagamento/regularização recebidos ou em análise não têm QR para iniciar outro pagamento |
| Link de autenticação do cartão | Passo de desafio vigente do cartão, enquanto o checkout é utilizável | Remover ao sair do passo ou encerrar o prazo/finalidade. Não liberar o link como uma URL histórica permanente |
| Link de ticket do provedor | Nenhum consumidor existente no produto | Não persistir nem devolver essa URL; contrato da resposta mantém o campo anulável |
| Valores, referências, estados verificados e prova de conciliação | Recebimento, regularização, restituição e defesa | Não são removidos por esta limpeza. Exigem política financeira/contratual própria, ainda aberta |

Para pagamento normal, a finalidade do checkout depende de pagamento ainda não
recebido, estado inicial do provedor, prazo do pagamento e solicitação aceita
ainda vigente. Para regularização, depende de pedido externo conhecido, estado
inicial, ausência de recebimento e prazo original. A remoção de QR/contato não é
confirmação de pagamento, cancelamento ou estorno.

O papel operacional do QR/link está descrito na documentação oficial do
[Pix em Orders do Mercado Pago](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/payment-integration/websites/pix).
As condições internas de finalidade decorrem do produto auditado, não de uma
suposta regra do provedor sobre prazo legal de guarda. Aplicam-se necessidade,
finalidade e término do tratamento dos arts.6º,7º,15 e16 da
[LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
A base da operação pedida é contratual; conciliação/defesa/obrigações têm base
própria, não consentimento promocional. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Banco e serviço

A V41 permite contato nulo somente quando a finalidade de criação terminou;
comando pendente que ainda exige o contato não aceita uma retirada arbitrária.
Não permite substituir um e-mail por outro ou repor o e-mail já removido. A
guarda de regularização conserva as demais identidades, datas, recebimento,
referências e monotonicidade de valores/reversões/versão. A guarda nova do Pix
preserva valor, identidade, criação e expiração do comando. Históricos financeiros
continuam com suas guardas existentes.

As funções de finalidade do banco são compartilhadas por triggers, consultas
e coletor. Writers antigos que atualizem dados de checkout também passam pelas
guardas. A migration não executa um expurgo dos registros existentes: índices e
restrições são instalados; a manutenção confere o controle independente antes de
limpar o legado.

`PrivacyCheckoutRetention` usa checkpoint de preservação, trava do titular e nova
conferência. `PrivacyCheckoutMaintenance` trabalha com lotes/cursor e roda antes
da prontidão após recuperação. Um titular ocupado não monopoliza a fila; uma
preparação independente não confirmada impede o ato destrutivo. A auditoria nova
guarda contagem/tipo/política, sem repetir e-mail, QR, token ou ID individual.

Inputs de criação com referência externa conhecida deixam de ser necessários
para reenviar uma criação. Marcar esse comando como concluído não aprova o
pagamento; o resultado continua vindo da conciliação real. Campos financeiros,
comprovantes, pedidos de devolução e ledger não são expurgados por essa rotina.

## Interface e restauração

Leituras próprias ocultam credenciais sem finalidade antes do worker. A exportação
não entrega o e-mail transitório já dispensável de uma regularização. A interface
interrompe a apresentação do formulário/QR quando termina o prazo interno e
mantém “Atualizar status”, pedidos e carteira. Não afirma que a cobrança externa
foi cancelada. A passagem do prazo força uma atualização de tela mesmo quando
campos do provedor não mudam; a nova tela encerrada não entra em um loop de reload.
Essa atualização usa a verificação periódica existente, não promessa de revogar
instantaneamente cópias já vistas, clipboard ou dados sob responsabilidade do banco.

Recuperação reaplica finalidade com datas originais e estado conhecido no banco,
sem criar outro prazo de pagamento. Snapshot antigo pode registrar envio incerto
ainda sem resultado; isso exige conciliação e não autoriza inventar que o provedor
recusou, recebeu ou cancelou. Esta etapa não resolve rollback financeiro externo
nem prova recuperação fora deste computador. Essa distinção faz parte dos testes
e dos limites do objetivo integral.

## Evidência e limites

A primeira rodada passou em49 unitários e10 migrações, mas10 dos13 cenários novos
foram recusados na preparação de cobrança: fixture tentava inserir diretamente
`pending`, sem a criação `awaiting` e linhas contratuais coerentes. A fixture foi
corrigida para respeitar as guardas financeiras; nenhuma delas foi desligada.
A rodada seguinte passou em49 unitários e92 integrações:13 cenários novos,
61 casos HTTP do provedor e18 de direitos de privacidade. A fonte final passou
em47 unitários e39 integrações:11 de migração (incluindo legado V40 sem expurgo
na migration),13 de checkout e15 jurídicos com a minuta r5.47 JS passaram antes
do reforço da passagem de prazo;48 JS finais passaram depois dele. Detector
Impeccable executado uma vez no JS final: `[]`. Não somar rodadas sobrepostas.

O [registro de evidência](evidence/checkout-transient-purpose-26oct.json)
identifica fontes/logs e seus hashes. A
[revisão de interface delimitada](../../.impeccable/review/privacy/checkout-transient-purpose-review.md)
registra o estado de prazo, testes do formulário/poll e ausência de nova prova
renderizada. Verificação automatizada não é certificação visual ou de produção.

Todos os dados são sintéticos, em `unicarona_java_test` na porta55432. O cliente
do teste específico aponta somente para loopback; a suíte do provedor usa seu
servidor de protocolo local. Não há cobrança, conta ou e-mail real nesses testes.
A conferência visual continua pendente da liberação do navegador já solicitada;
não atribuir capturas antigas aos estados novos.

Continuam retenção integral de financeiro/contratos/logs, anonimização real,
tratamento operacional dos envios sem resultado, recuperação externa,
minimização restante, jornadas/aceites, matriz de consistência/red teams e os
dezesseis entregáveis. Na conclusão original desta etapa, a interação entre
mudança de e-mail e resposta perdida do cartão ainda exigia ensaio específico.
O [complemento de contato original](Comando_Cartao_Contato_Original.md) registra
a reprodução no caminho de produção contra fixture local, a correção do comando
cifrado e a recuperação segura do legado. A evidência V41 acima permanece o
registro histórico daquela rodada; os hashes/resultados novos são separados.
