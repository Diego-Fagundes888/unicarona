# Retenção de transferências definitivamente falhas — V45

26/10/2026. Implementação em desenvolvimento. A demonstração nativa V26 e seu
frontend congelado permanecem preservados. Não é conclusão dos 44 requisitos.

## Problema e limite da solução

O diário financeiro proibia toda exclusão, inclusive de uma tentativa falha que
separou um valor e depois devolveu exatamente o mesmo valor à mesma conta. Isso
mantinha tentativa, referência do provedor, razões, vínculo do usuário, recibos,
avisos e lançamentos indefinidamente.

O novo caminho admite **somente um conjunto de efeito zero em cada conta**:

| Ato | Conta de origem | Conta de valor separado |
| --- | ---: | ---: |
| Solicitação de 500 centavos | −500 | +500 |
| Falha definitiva e devolução à origem | +500 | −500 |
| Efeito total do conjunto | 0 | 0 |

Não basta a soma geral ser zero: todo lançamento de dupla entrada tem essa soma.
A verificação exige saldo zero **por conta**, mesmo titular, moeda BRL, exatamente
dois diários, quatro lançamentos, tipos e valores correspondentes, nenhum vínculo
com pagamento/parcela e nenhum diário adicional no mesmo namespace da tentativa.
Saldos anteriores ou posteriores, inclusive créditos ainda devidos, permanecem.

O protocolo comprovado nesta versão é `development-transfer`. Qualquer recibo
de outro provedor, incompleto, contraditório ou não processado impede a retirada.
O reconhecimento exige o UUID canônico em minúsculas no recibo; outra
formatação é preservada para revisão, mesmo quando sua referência permite
localizar a tentativa. Ampliar formatos reconhecidos exige sua própria prova.
Transferências com devoluções Mercado Pago vinculadas são excluídas do escopo.
**Esta entrega não habilita saques/repasses reais, não comprova uma devolução
real e não implementa retenção de pagamentos recebidos.**

## Prazo e finalidade

A proposta operacional de defesa é de **cinco anos de calendário**, calculados em
`America/Sao_Paulo`, do último ato pertinente já registrado: criação, diários,
lançamentos, eventos classificados da transferência e apuração financeira do
titular. A solicitação antiga não permite apagar uma falha concluída recentemente.
Como ocorrências não têm vínculo direto com a transferência, a regra conserva o
conjunto também durante ocorrência aberta do titular e considera a data da
conclusão de sua disputa/apuração financeira. Essa abrangência conservadora deve
ser revista se a operação introduzir vínculo específico entre caso e tentativa.

`updated_at`, polling, novo recibo semanticamente igual, processamento de entrega,
liberação de hold, conclusão de pedido de acesso e restauração não reiniciam o
prazo. Um ato novo de disputa tem finalidade própria; não equivale a polling.

Este prazo é uma **proposta de política**, não prescrição universal. O
[Código Civil, art. 206, §5º, I](https://www.planalto.gov.br/ccivil_03/leis/2002/l10406compilada.htm)
trata de uma hipótese específica de cobrança; os arts. 205, 206 e causas de
suspensão/interrupção precisam ser avaliados conforme a pretensão. A
[LGPD, arts. 6º, 15 e 16](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm)
exige finalidade/necessidade e tratamento adequado do término e conservação.
Não foi inferida obrigação contábil/fiscal específica para o responsável pessoa
física. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Impedimentos e dependências

- Hold financeiro vigente, ocorrência aberta, pedido de acesso/portabilidade/
  restrição não resolvido e cache de resposta ainda válido impedem o expurgo.
- Uma notificação sob preservação específica ou com vínculo/contexto desconhecido
  também impede a retirada do conjunto.
- Eventos de auditoria precisam corresponder exatamente à solicitação do titular
  ou ao resultado de falha do provedor. Ato desconhecido conserva o histórico.
- Recibos precisam coincidir com titular indireto, ID, referência, valor, evento e
  resultado; todos os recibos vinculados precisam estar processados.
- Entrega pendente, em execução, com lease vigente ou de tipo/conteúdo desconhecido
  conserva a tentativa. Só tarefas conhecidas encerradas podem acompanhá-la.
- Cópia privada que ainda testemunha a transferência ou seus lançamentos impede
  apagar sua origem. Referências explícitas a diário, lançamento, recibo ou evento
  de auditoria também impedem. Não se inventa quitação pela ausência desses fatos.
- Nova chave estrangeira desconhecida faz a transação reverter, em vez de executar
  cascata irrestrita ou desabilitar as proteções existentes.

## Execução e proteção contábil

`PrivacyFailedTransferRetention` confere o controle independente de preservações,
bloqueia o titular, a transferência e suas contas, e verifica novamente a regra.
Um único comando com CTEs dependentes retira cache vencido, avisos dispensáveis,
eventos classificados, recibos conhecidos, tarefas encerradas, quatro lançamentos,
dois diários e a tentativa. Os guardas `STABLE` observam o conjunto original desse
comando; os testes executam isso no PostgreSQL real.

Restrições adiadas impedem confirmar exclusão de apenas parte do dinheiro ou
deixar recibos e registros operacionais órfãos. Atualizações de diário/lançamento
continuam proibidas. `TRUNCATE` dos três objetos financeiros não oferece atalho.
Antes de concluir, o serviço compara todos os saldos do titular por conta/moeda;
qualquer diferença reverte a transação. O checkpoint independente é preparado no
mesmo ciclo de confirmação. Remover artificialmente fatos de uma finalidade
privada já conhecida não permite contornar esse checkpoint.

O processamento normal pula titular ocupado e percorre os demais candidatos.
Na recuperação, aguarda os bloqueios e termina a supressão elegível antes de
liberar prontidão. Preservações posteriores ao backup são reaplicadas primeiro.
Uma falha de expurgo na recuperação impede prontidão; o worker registra apenas
código técnico e tenta novamente, sem imprimir conteúdo financeiro pessoal.

## O que permanece aberto

Repasse concluído e pagamento recebido têm efeito por conta diferente de zero.
Não podem seguir este expurgo; exigem política e arquitetura próprias para manter
conciliação, obrigações, provas e recuperação sem recriar dinheiro. A retenção dos
contratos realizados e das demais classes de webhook continua aberta. O snapshot
de finalidades não é backup integral do ledger. O conjunto retirado não vira
estatística anônima; UUID e cofre reidentificável continuam sendo dados pessoais.

Privacidade r9 é minuta; r8 foi preservada integralmente em `legal/history`.
Sem publicação, sem chamada a provedor real, sem envio real de e-mail e sem
alteração visual. A conferência no navegador continua adiada pelo usuário.

## Evidência de testes

Resultados, hashes e limites das rodadas são registrados em
[failed-transfer-retention-26oct.json](evidence/failed-transfer-retention-26oct.json).
As rodadas se sobrepõem e suas contagens não devem ser somadas. A evidência
distingue a primeira versão dos reforços finais; não apresenta prova parcial
como conclusão da retenção financeira inteira.


Resultado final desta etapa: **176 testes unitários e 232 de integração aprovados**, incluindo **21 cenários próprios** e 15 de compatibilidade de migração. A primeira prova teve 5 unitários/13 integrações; a regressão intermediária teve 176/215, antes dos reforços de integridade do conjunto e prazo de disputa. Nenhuma dessas contagens deve ser somada. O escopo final também cobre carteira, pagamentos, transferências, Mercado Pago, cotações nunca enviadas, identificação financeira, direitos, documentos legais e auditoria de segurança.
