# Recuperação do cartão e correção de e-mail

26/10/2026. Complemento técnico à V41, sem nova migration. Fonte de desenvolvimento;
demonstração privada V26 e frontend congelado preservados. A implementação não
publica minutas nem habilita operação financeira real.

## Falha reproduzida

O comando durável guardava o token e os dados necessários ao cartão cifrados,
mas `MercadoPagoService.submitCard` buscava o e-mail atual do perfil a cada envio.
Se o provedor criasse o pedido, a resposta se perdesse e o titular corrigisse o
e-mail, a recuperação usava a mesma chave de idempotência com outro contato.

O primeiro ensaio passou em sandbox porque `MercadoPagoOrdersClient` usa o
contato sintético exigido nesse ambiente. Isso não comprovava o caminho que
envia o contato do usuário. O ensaio seguinte ativou apenas essa ramificação do
cliente contra um servidor de protocolo em loopback, com credenciais sintéticas:
o teste esperava HTTP200 e recebeu422 por mudança no corpo. Nenhuma configuração
de produção foi habilitada no aplicativo ou no provedor real.

A [referência oficial de criação de Orders](https://www.mercadopago.com.br/developers/en/reference/online-payments/checkout-api/create-order/post)
documenta a chave de idempotência para repetir uma operação sem duplicá-la. A
exigência de preservar o contato nesta recuperação decorre também do corpo
efetivamente construído pelo nosso cliente. A fixture recusa outro corpo para
a mesma chave; não se afirma que ela reproduz todos os comportamentos de erro
ou disponibilidade da infraestrutura real do Mercado Pago.

## Correção implementada

`MercadoPagoCardCommand` escreve um envelope privado versionado, dentro da coluna
cifrada existente. Ele contém tipo/formato, cobrança, titular, valor, contato
original e dados do cartão já aceitos no produto. Não acrescenta coleta de PAN,
CVV ou documento novo. O AES/GCM autentica o conteúdo; a leitura confere o contexto
de cobrança/titular/valor e o HMAC original do input. Rejeita campos inesperados,
chaves duplicadas, tipos incorretos, conteúdo extra, formato desconhecido e
cifra adulterada. Os objetos decodificados têm `toString` redigido.

O contato é congelado ao preparar o comando, sob a trava do titular. O início
do envio passa a ler/validar o comando e marcar a tentativa na mesma transação,
sob as travas de titular, cobrança e comando. A chamada HTTP continua fora da
transação. Recuperação usa o contato congelado; a correção de e-mail continua
exigindo senha, link da própria conta, sessão e novo login após revogação das
sessões antigas. A recuperação não repõe o e-mail antigo no perfil.

O `body_hash` mantém a codificação do `CardInput` para compatibilidade de
idempotência. O envelope não é devolvido em gateway, checkout ou exportação, e
e-mail/token/device não entram em mensagens de erro novas. A confirmação do
pedido externo elimina todo o envelope pelo fluxo já existente, inclusive
quando o pagamento continua pendente: conhecer a criação encerra a necessidade
de reenviá-la, sem representar aprovação financeira. A V41 continua tratando
expiração de comando nunca iniciado e preservando os envios de resultado incerto.

## Legado e recuperação verificável

| Situação | Comportamento |
| --- | --- |
| Envelope novo | Repetir input e contato originais; conferir contexto antes da chamada |
| Input antigo, nunca iniciado | Validar hash/dados e congelar contato atual sob trava antes da primeira tentativa |
| Input antigo, já iniciado, sem identidade externa conhecida | Recusar reenvio automático com `PAYMENT_COMMAND_CONTACT_UNKNOWN`; não adivinhar contato original e não descartar o input como falha definitiva |
| Identidade externa já vinculada | Concluir comando e limpar envelope; consultar o pedido pelo fluxo de conciliação existente |
| Callback autêntico do pedido antigo | Validar assinatura, consultar o pedido no provedor, conferir referência/valor e aplicar uma vez; limpar o comando |
| Envelope de outra cobrança/titular/valor | Recusar antes de incrementar tentativa ou chamar o provedor |

Uma notificação sem assinatura válida não libera assento nem registra recebimento.
A recuperação demonstrada do legado já enviado usa a identidade do pedido real,
callback assinado e consulta verificada; status/valor alegados no corpo do
callback não são tratados como prova. Não foi criado endpoint para um operador
inventar aprovação ou fornecer um e-mail supostamente original. Se o provedor
não disponibilizar a identidade e o estado verificável, esse caso exige
investigação operacional, sem instruir o passageiro a pagar outra vez.

## Publicação e limites

API e worker devem receber o leitor novo juntos, antes de produzir envelopes
novos. Um binário antigo não sabe ler o formato; não misturar processos antigos
e novos ou executar rollback para o leitor antigo com comandos novos pendentes.
Antes de uma futura troca de ambiente/credencial/versão de construção do pedido,
conciliar comandos pendentes e conferir compatibilidade. Este complemento não
certifica recuperação de chaves fora do computador nem mudanças futuras do
protocolo. A demonstração V26 permanece separada e não recebeu esse leitor.

Privacidade r5 continua `draft`: já descreve criação/recuperação do comando de
cartão e limpeza no fim da finalidade. Nenhuma fonte legal foi sobrescrita ou
aceite real criado por esta correção. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Evidência

- Reprodução sandbox:15 unitários e um HTTP passaram antes da correção; a
  normalização de contato tornava esse ensaio insuficiente.
- Reprodução do caminho de produção em fixture local:15 unitários passaram e
  o HTTP falhou com422, quando esperava200, antes da correção.
- Primeira fonte corrigida:22 unitários e quatro HTTP passaram nesse caminho.
- Regressão ampla:59 unitários e106 integrações passaram, sem falhas,
  erros ou skips:65HTTP do provedor,13checkout,13correção e15jurídicos.
- Após o último reforço de validação de formato numérico e contato, conferência
  final da ramificação de produção contra loopback:22 unitários e quatro HTTP
  passaram, sem falhas, erros ou skips. A rodada ampla antecede esse reforço;
  seus resultados não são apresentados como nova execução da fonte posterior.

As rodadas se sobrepõem; não somar como casos únicos. O
[registro de evidência](evidence/card-frozen-contact-26oct.json) identifica fontes,
logs, hashes e limites. Os testes novos incluem troca confirmada por HTTP/link,
revogação de sessão, novo login, legado enviado/nunca enviado, ciphertext de
outra cobrança, assinatura inválida e callback repetido com um recebimento.
Os sete testes do codec cobrem privacidade do objeto, contexto, legado, esquema,
duplicatas, trailing data, adulteração, campos de cartão bruto e hash sem device.

Dados sintéticos somente em `unicarona_java_test`, loopback55432, com provedor
HTTP local. Nenhuma conta, cobrança, credencial financeira ou mensagem de e-mail
real é usada. Não há nova tela ou estilo neste complemento; as conferências
visuais pendentes da V41 não foram convertidas em aprovação. Permanecem as
frentes financeiras/contratuais/logs, anonimização real, recuperação externa,
matriz final/red teams e entrega integral dos44requisitos.
