# Conferência dos trechos antes de iniciar o pagamento

Desenvolvimento em 24/10/2026. A demonstração protegida permanece em V26 com
frontend congelado. Esta mudança exige publicação conjunta do backend e do
frontend de desenvolvimento; não foi implantada sobre a demonstração.

## Problema e comportamento implementado na fonte

O checkout mostrava motorista reduzido, número de trechos e valores, mas não
mostrava encontro nem instruções. O leitor jurídico já informava a disponibilidade
dos detalhes pertinentes após aceite válido do pedido e antes do pagamento.

`GET /api/v1/payments/{id}/summary` agora inclui os trechos da própria cobrança,
ordenados por data, horário e identificador. Cada trecho contém data/horário,
sentido, regiões do trajeto, valor contratado em centavos, encontro e observações
quando a finalidade permite. O valor vem de `request_lines.cents`, já com o
desconto contratado. Não usa o preço corrente da oferta nem divide o total pelo
número de trechos. A interface associa cada valor à respectiva data e horário;
mantém total, desconto e comissão. Sem valores íntegros cuja soma confira com o
total, não oferece o formulário de primeiro pagamento.
Não inclui coordenadas precisas, endereço armazenado separado, placa completa,
telefone, documentos ou dados de outros passageiros.

Encontro e observações só aparecem para o passageiro proprietário com pedido
aceito e não expirado, cobrança aguardando/iniciada não expirada, trecho futuro
previsto, motorista ativo não excluído e sem bloqueio bilateral. Nas demais
situações, esses dois campos são nulos e não há revisão apta a iniciar cobrança.
O histórico financeiro não autoriza permanentemente detalhes operacionais.

A interface apresenta esses dados antes do resumo financeiro. No mensal,
instruções idênticas de um trajeto são agrupadas, mantendo cada data, horário e valor;
instruções distintas de um trecho permanecem em grupo próprio. Falta da revisão
autorizada mostra recuperação e impede apresentar o formulário de primeiro envio.
Conteúdo fornecido por pessoas é escapado na renderização.

## Contrato do primeiro envio e recuperação

O resumo fornece `tripReview`, um SHA-256 dos campos delimitados por comprimento,
identificador da cobrança, valor total, valor de cada linha e versões de todos os
trechos. Uma redistribuição de centavos entre linhas, mesmo sem alterar o total,
invalida a revisão anterior. O frontend
envia essa revisão no primeiro `POST /api/v1/payments/{id}/checkout`. O servidor
compara com o estado atual depois de adquirir as travas dos trechos e antes de
lançar crédito, confirmar reserva ou chamar o provedor. Mudança ou ausência da
revisão gera `409 TRIP_REVIEW_REQUIRED`, sem iniciar pagamento. A interface
atualiza o resumo e exige nova ação da pessoa; não reenvia automaticamente.

O campo é opcional no esquema de entrada porque a retomada de cobrança já
iniciada conserva a operação original. Se informado, exige 64 caracteres
hexadecimais minúsculos; nulo e formato inválido são recusados. O serviço exige
revisão válida para a transição inicial de `awaiting` para `pending`, inclusive
quando o crédito cobre o valor inteiro. Método e opção de crédito continuam
imutáveis na recuperação da cobrança preparada.

Revisão não integra o hash de idempotência da operação financeira, que mantém
identificador/método/crédito. Depois de preparar a operação com revisão válida,
repetir sua chave devolve a mesma operação; não inicia outra nem transforma
mudança posterior de instruções em outra cobrança. Esse comportamento preserva
recuperação de respostas perdidas e registros já existentes. A revisão é um
controle de atualidade, não assinatura digital, aceite de novos termos, prova de
leitura efetiva nem arquivo histórico imutável das instruções.

Não foi criada tabela/migration nem armazenamento adicional de encontro ou
observações. Obrigações de evidência histórica e retenção continuam sujeitas à
matriz integral; não se deve atribuir a este digest uma capacidade que ele não tem.

## Verificação deste checkpoint

Os 46 testes JavaScript passaram, incluindo renderização/escape, agrupamento
mensal, preços distintos por data, inconsistência/ausência de valores, ausência de
revisão e recuperação sem reenvio. São contratos em contextos
VM, não substituem a verificação visual no navegador.

A rodada Java delimitada `privacy-checkout-trip-review-boundary.log` terminou
com BUILD SUCCESS: 11 unitários e 23 PaymentHTTP. Quatro casos HTTP novos usam
requisições sem preenchimento automático de revisão: ausência/formato/nulo e
mudança por edição real; propriedade/expiração/bloqueio/finalidade encerrada;
todos os trechos do mensal; e alteração concorrente enquanto o pagamento espera
uma trava real de PostgreSQL. Incluem contagem zero de chamadas ao provedor nos
casos recusados e retentativa sem duplicação após preparação válida.

Os testes antigos de outros comportamentos financeiros recebem uma precondição
explícita em `CheckoutTestFixtures.reviewedBody`, somente no banco descartável
`unicarona_java_test`. Isso não substitui os quatro casos específicos, que usam
HTTP bruto e não passam por esse preenchimento. Uma primeira rodada e uma
repetição falharam durante desenvolvimento: a validação de entrada ainda recusava
o campo novo. O contrato HTTP estrito foi atualizado, sem abrir propriedades
arbitrárias. Não somar rodadas repetidas como cobertura adicional.

A revisão independente completa conferiu 23 capturas e confirmou as correções
CP-01–04: link de pagamento legível, painel sem altura artificial, recuperação
específica de prazo encerrado e aviso persistente de nova conferência. Indicou
CF-01, ausência de preço por trecho apesar da promessa documental. O servidor e
o cliente foram corrigidos conforme descrito acima. Quinze novas capturas de
entrada direta avulsa/mensal em 1280, 834, 390 e 320px foram validadas; o parecer
`checkout-finish-verdict.md` deu **ship limitado a CF-01 resolvido**, preservando
o parecer completo anterior. Não se atribui a esse veredito uma nova auditoria
integral nem aprovação dos 44 requisitos. Fonte visual em
`.impeccable/review/privacy/ui/checkout-first` e `checkout-final`.

A primeira regressão ampliada de 24/10 executou 263 casos HTTP: seis falharam
porque os usuários sintéticos de `AdminCatalogHttpIT` não tinham os aceites
obrigatórios, impedindo publicar rotinas. A precondição explícita de usuário de
negócio foi acrescentada somente à fixture; nenhuma guarda de produção foi
removida. A nova regressão inclui essa correção e CF-01 e terminou com BUILD
SUCCESS em `privacy-checkout-cf01-regression-24oct.log`: 11 unitários e 263 casos
HTTP, zero falhas, erros ou testes ignorados. Abrange Payment, Mercado Pago,
contratos, viagens, manutenção, segurança, perfil, administração de contas e
catálogo e documentos. O conjunto atual de JavaScript tem 46 casos aprovados.
Não atribuir aprovação integral à rodada anterior nem somar rodadas repetidas.

O relatório JS atual fica em `ui/checkout-final/checkout-js-tests.txt`; a cópia
na raiz da pasta de revisão pertence ao checkpoint anterior. Evidências de CF-01
em `cf01-capture-index.json` e `cf01-test-proof.json`.

A conferência do OpenAPI detectou colisão com outro DTO chamado `Summary`, que
ocultava os campos reais da resposta do pagamento. Nomes exclusivos
`PaymentSummary`/`PaymentTrip` e um teste do esquema gerado corrigiram a colisão.
A rodada `privacy-checkout-schema-24oct.log` passou: quatro unitários e 24
PaymentHttpIT, incluindo a resposta documentada e o tipo/valor dos campos novos.
Essa rodada sobrepõe os testes financeiros anteriores; não somar como casos
únicos novos. É uma correção de contrato/documentação, sem alteração da interface
capturada e fora do veredito visual CF-01.

O OpenAPI foi novamente obtido da prévia descartável recompilada; os tipos
`PaymentSummary`, `PaymentTrip.cents` e `PaymentCheckoutInput.tripReview` foram
conferidos antes da exportação de `docs/api/openapi-java.json`. A prévia é local
de desenvolvimento, não um servidor público ou o runtime protegido V26.

O restante dos 44 requisitos, recuperação, retenção, minutas finais, revisão
profissional e implantação não estão certificados por esta etapa.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**
