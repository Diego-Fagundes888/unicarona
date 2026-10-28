# Localização estruturada sem finalidade — 24/10/2026

Implementação Java em validação, ainda não publicada. Demonstração protegida V26
e frontend congelado permanecem separados. Este complemento não declara encerrada
a retenção de toda localização, de registros financeiros ou de segurança.

## Diagnóstico confirmado

`RideDto.Create`, o contrato HTTP estrito e `RideRoute` aceitavam e gravavam
`originAddress`, `destinationAddress`, `originLat`, `originLng`, `destinationLat`
e `destinationLng`. O formulário corrente não os envia, o mapa não rastreia GPS e
o cálculo de preço usa distância informada. Os leitores da carona não consomem
esses campos. A edição de distância copiava os seis campos para uma nova rota,
criando novas cópias de dados sem função no fluxo atual. A exportação própria,
o atendimento autorizado e a exclusão eram os únicos leitores desses legados.

## Alteração implementada

- Novas publicações recusam os seis campos, inclusive valores válidos e nulos,
  com 422 antes de persistir oferta, rota ou resposta de idempotência. O contrato
  continua fechado a propriedades adicionais; não se ignora silenciosamente dado
  enviado por um cliente antigo.
- O DTO e a entidade operacional deixaram de mapear esses dados. Edição de
  distância conserva região/encontro e recalcula a distância/preço conforme as
  regras existentes, mas não duplica endereço ou coordenada de uma rota legada.
- `PrivacyLocationRetentionTransactions` limpa os seis campos de rotas antigas
  em lotes. Não limpa região, encontro, distância, oferta, agenda, pagamento,
  reserva, parcela ou lançamento contábil por essa rotina. Não exige excluir a
  conta para eliminar dados extras sem finalidade.
- Uma retenção de localização vigente, específica à rota ou global legada do
  seu titular, impede a limpeza. Preservação de outra categoria ou de outra rota
  não conserva todas as rotas. Liberação ou vencimento permite a próxima limpeza.
- Uma ocorrência não resolvida vinculada a uma reserva daquela rota também
  impede essa limpeza; a resolução permite voltar à regra normal. A operação
  ainda precisa revisar e concluir casos, não usar um caso abandonado como
  justificativa de guarda ilimitada. Se uma prova continuar necessária após
  resolver a ocorrência, a administração deve criar/revisar a retenção específica
  fundamentada antes de concluir o caso; resolver não cria guarda automática.
- Manutenção e retenções administrativas usam a mesma trava transacional de
  PostgreSQL. A preparação de prontidão reaplica a redução ao banco restaurado,
  antes de abrir API/worker, em lotes de 500 na transação de recuperação.
  Diferentemente da manutenção, não pula uma linha travada na inicialização:
  falha de trava/limpeza impede prontidão, mantendo os dados fora do uso normal.

As colunas físicas antigas permanecem para tratamento controlado de legado e
exportação do que ainda estiver legitimamente disponível. Não foi alterada
migration publicada nem foi necessário criar tabela ou migration nova. O backend
TypeScript legado não passa a ser uma alternativa aprovada para executar a
política Java atual. A publicação precisa trocar os componentes em conjunto.

Não se cria cópia cifrada adicional de toda coordenada descartada. Uma retenção
administrativa já possui seu mecanismo próprio de cópia delimitada. A auditoria
da limpeza registra quantidade e escopo, sem registrar endereços, coordenadas,
IDs de rota ou titular. Isso não é certificação de estatística anônima nem de
expurgo integral dos demais logs.

## Fundamentação e limites

A decisão aplica finalidade, adequação e necessidade ao funcionamento observado:
[LGPD, art. 6º, I–III](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
Conservar um campo tecnicamente disponível exige finalidade, não apenas um
checkbox. Não se atribui à LGPD um prazo universal inventado para coordenadas.
Retenções e apuração de casos dependem da finalidade e da base legal efetiva;
**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

Campos livres ainda podem conter um endereço digitado na região, no encontro ou
nas notas: esta rotina não identifica nem torna anônimo texto livre. Retenção de
encontro/notas em contas ativas, agenda antiga, IDs e documentos vinculados exige
o restante do trabalho. Backups não são fisicamente reescritos por essa rotina;
a redução é reaplicada antes de usar um banco recuperado. Recuperação independente
das retenções criadas após o snapshot e controles externos/chaves continuam
pendentes no objetivo integral e devem ser resolvidos antes da publicação.

## Verificação

`privacy-unused-location-first-24oct.log` terminou com **BUILD SUCCESS: oito
unitários e 107 integrações**, sem falha/erro/skip. São Payment24, Ride18,
Lifecycle30, AccountControls11, FileRetirement10 e Holds14. O launcher recusou
antes disso um nome inexistente de suíte, sem iniciar Maven; não se contabiliza
essa tentativa como execução. Seis casos novos de Ride cobrem recusa dos seis
campos antes da gravação, edição sem propagação, lote limitado, retenção vigente
específica/global versus expirada/liberada ou categoria diferente, ocorrência
aberta versus resolvida, disputa real da trava PostgreSQL e reaplicação antes da
prontidão. Inclui banco restaurado sintético com mais de um lote e recusa de
uso enquanto uma linha impede a limpeza. A corrida confirma no próprio PostgreSQL
que o expurgo espera a trava da retenção não confirmada e, após o commit, conserva
o dado protegido. A publicação válida e seus valores continuam testados.

Após essa rodada, a minuta foi atualizada e o nome de esquema de entrada passou
a `RideCreateInput`, para tornar inequívoco o contrato OpenAPI. A confirmação
`privacy-unused-location-restore-final-24oct.log` passou com quatro unitários e
21 integrações: um caso de Ride para os campos/esquema gerado, 15 de documentos,
dois de restauração física de conta/arquivo e três de fence. Mensagens de falha
de inicialização nesses testes correspondem à recusa deliberada de banco de outro
dataset ou anterior ao marco independente; o resultado final não contém falhas.
Rodadas sobrepostas não são somadas.

`privacy-unused-location-physical-proof-24oct.log` também terminou com BUILD
SUCCESS: quatro unitários sobrepostos e um caso específico com `pg_dump` e
`pg_restore`. O arquivo real continha uma rota comum e uma sob retenção vigente.
O teste leu o endereço reaparecido no banco recém-restaurado antes de iniciar a
aplicação; após a inicialização e prontidão, os seis campos da rota comum estavam
nulos, a rota retida continuava preservada, assim como encontro, conta/sessão e
fingerprint do ledger. Não se confunde a presença da retenção no próprio backup com
recuperar uma retenção criada depois dele; essa última pendência continua aberta.

A confirmação final `privacy-unused-location-physical-funded-final-24oct.log`
passou com quatro unitários sobrepostos e esse mesmo caso físico, agora contendo
um lançamento sintético balanceado de 700 centavos, duas entradas e crédito
positivo. O fingerprint foi preservado e o crédito continuou em 700 após
restauração e limpeza. Isso evita atribuir preservação financeira apenas à
comparação de um ledger vazio. É reforço do mesmo caso, não outro caso único.

Prova consolidada por rodada e hashes de fonte em
`evidence/location-retention-24oct.json`. A prévia recompilada respondeu com
`RideCreateInput` sem os seis campos e o OpenAPI canônico foi exportado dela.
O corpo do aviso de privacidade efetivamente servido pelo catálogo sintético
também contém a mudança de coleta e a linha de retenção. Isso não publica nem
registra novos aceites de pessoas reais. A revisão visual integral do documento
com o texto final permanece na etapa de consistência/publicação.
