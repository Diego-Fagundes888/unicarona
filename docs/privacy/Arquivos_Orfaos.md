# Bytes privados sem metadados — fonte V39

Complemento de desenvolvimento de 25/10/2026. A demonstração protegida permanece
V26; esta camada, seus comandos e as minutas não foram publicados. O objetivo dos
44 requisitos continua ativo. Os resultados anteriores de V37 tratavam uploads
com metadados e supressões já registradas; não provavam a limpeza descrita aqui.

## Lacuna e regra operacional

O processo pode terminar depois de gravar bytes e antes de confirmar `stored_files`.
Uma retentativa de limpeza também pode falhar. Não é correto inventar um titular
para esses bytes nem depender apenas da idade do arquivo no sistema operacional.

O coletor novo aceita somente o namespace plano de nomes UUID do armazenamento
privado. Faz uma primeira observação de tamanho, SHA-256, identidade física e
datas do arquivo; a impressão digital não contém caminho ou conteúdo em claro.
Exige outra observação igual **pelo menos 24 horas depois**, usando o relógio do
banco. A data antiga trazida por um backup não elimina essa espera. Alteração
do conteúdo ou identidade física reinicia a observação.

Antes de classificar e novamente na remoção, coordena a operação por arquivo,
verifica ausência de qualquer linha por ID/chave e verifica preservações ativas.
Uma preservação específica do UUID impede a remoção; uma preservação global
legada também impede inferir que os bytes pertencem a outra pessoa. Ao terminar
essa proteção, começa uma observação nova. Metadados existentes, inclusive os
marcados para exclusão, ficam sob o fluxo de remoção de arquivos conhecidos.

Os intervalos de 24 horas e sete dias abaixo são escolhas operacionais propostas,
não prazos legais universais. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA**
para a política completa de retenção e a finalidade dos casos de preservação.
A revisão considera necessidade, término do tratamento e conservação delimitada,
conforme os arts. 6º, 15 e 16 da [LGPD em texto oficial compilado](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm),
conferido nesta etapa. A lei não foi usada para inventar um prazo universal de
24 horas ou sete dias.

## Concorrência e uploads

`PrivateFileLock` usa uma trava transacional no PostgreSQL por UUID, compartilhada
entre processos. O upload mantém a coordenação desde a gravação física até a
conclusão da transação de metadados. O coletor tenta a trava sem esperar: um envio
em andamento não vira arquivo abandonado nem retém o restante da fila de trabalho.
Colisões do hash da trava só serializam arquivos sem relação, sem dar acesso a eles.

`FileTransactions.upload` remove seus bytes novos depois de rollback conhecido ou
de uma deduplicação confirmada. Não remove bytes por uma exceção genérica depois
de um possível commit. Uma falha depois do commit não pode deixar a linha confirmada
apontando para um arquivo que o tratamento da exceção acabou de apagar. Resultado
ambíguo conserva os bytes para conferência; falha física da limpeza pode ser
classificada posteriormente pelo coletor, sem criar metadados pessoais fictícios.

## Supressão independente e confirmação

Antes de remover bytes classificados, `PrivacyErasureJournal` publica uma entrada
`orphan-<UUID>`, assinada e ligada ao índice independente e ao dataset. Contém UUID,
tamanho, hash de conteúdo, preparo, prazo mínimo e estado de confirmação; **não
contém titular inferido, e-mail, documento ou caminho escolhido por uma requisição**.
São metadados pessoais/pseudonimizados de controle, não estatística anônima.

A entrada nasce pendente. Somente depois de um commit conhecido o coordenador
verifica ausência física e publica `confirmedAbsentAt`. Rollback ou resultado
incerto não transformam a intenção de apagar em confirmação. Falha da publicação
da confirmação mantém a instrução pendente, gera código operacional e é retomada
em uma passagem posterior. Essa fila tem cursor próprio para não ficar presa
atrás de arquivos que ainda precisam ser preservados.

O prazo mínimo original é o intervalo configurado de backups mais dois dias;
retentativas e troca de assinatura não o renovam. A expiração exige também ausência
confirmada e eliminação de todas as cópias capazes de reintroduzir dados anteriores
à **confirmação**, inclusive uma cópia feita entre preparo e remoção efetiva. O marco
de restauração avança de forma conservadora até essa confirmação. Uma instrução
pendente ou um arquivo ainda presente não é considerado lixo de diário.

Se os bytes reaparecerem, a confirmação antiga é invalidada antes da nova remoção.
As datas originais do preparo e do prazo mínimo são mantidas; a nova ausência terá
sua própria confirmação. A ordem cronológica do preparo continua sendo usada na
fila mista de supressões de conta, arquivo conhecido e órfão.

Esse reforço foi inicialmente aplicado ao novo tipo de órfão. O complemento
posterior [Confirmação de supressões](Confirmacao_Supressoes.md) estende o protocolo
aos tipos de conta/arquivo conhecido com conferência de banco, commit e horizonte
de restauração. Seus testes e limites são registrados separadamente; os testes
de órfão não certificam automaticamente as formas anteriores de supressão.

## Restauração e conflitos

Depois da recuperação das preservações confirmadas e antes da prontidão, a guarda
reaplica instruções de órfãos. Isso funciona sem linha no banco e quando um backup
antigo volta a ligar o mesmo UUID ao perfil. A linha restaurada precisa corresponder
ao ID/chave/tamanho/hash esperado; dados conflitantes interrompem a recuperação.
Uma foto antiga perde o vínculo e o endpoint comum permanece indisponível, sem
encerrar a conta ou alterar saldo, contratos ou lançamentos contábeis.

A remoção verifica conteúdo exato. Bytes diferentes, nomes inesperados, diretórios,
links/junctions ou raiz redirecionada exigem análise operacional. Nenhum diretório
é apagado recursivamente nem uma cópia é sobrescrita para fazer a verificação passar.
Preservação ativa pode manter bytes privados, sem reabrir o download comum. Falha
física ou conflito durante replay impede prontidão até a recuperação ser concluída.

## Dados, limites e operação

| Controle | Armazenamento/acesso | Retenção e ação final |
| --- | --- | --- |
| UUID, tamanho, hash, impressão digital e instantes de observação | `privacy_orphan_file_observations`, somente coordenadores internos; sem titular inventado ou exportação para outra pessoa | Removidos quando há metadados, confirmação da remoção ou classificação renovada. Observações antigas de arquivos ausentes são retiradas em lotes após sete dias sem observação; arquivo ainda presente não perde sua conferência por uma limpeza cega de linhas |
| Instrução e confirmação de supressão | Diário privado assinado, índice independente e dataset; ferramenta mostra somente contagens | Prazo mínimo original, prova de ausência e barreira física de backups antes da retirada; rotação conserva identidade/datas |
| Ocorrência de remoção | Auditoria sem ator inventado e sem bytes/hash em metadata pública | Retenção geral da auditoria ainda exige conclusão própria; não foi declarada resolvida por esta etapa |

`PrivacyOrphanFiles` roda antes do apagamento de arquivos conhecidos, somente com
controle independente configurado, prontidão concluída e checkpoint atual
conferido. Seleciona até 25 arquivos e 25 confirmações por passagem, com cursores e
cadência mínima de um minuto. O inventário de nomes é plano e limitado a cem mil
entradas; não se promete custo constante de enumerar o diretório nem capacidade
ilimitada. O limite deve ser confrontado com o volume real antes de publicar.

O worker passa a retirada de prontidão às fronteiras das tarefas e confere novamente
a prontidão antes de iniciar e-mail. Um processo que descobre controle independente
pendente/incompatível não continua o apagamento por confiar em um sinal antigo.

`PrivacyCopiesCommand status` acrescenta `orphanJournalEntries` e
`orphanCompletionPending`. API, worker e ferramenta devem ser publicados juntos;
ferramentas antigas recusam o novo tipo em vez de ignorá-lo. A V39 não cria
observações ou proprietários para arquivos antigos durante a migração.

A minuta de Privacidade passa à revisão 2, com novo hash/versionamento preparado,
mantendo o catálogo em `draft`. Não houve publicação ou recibo retroativo. A
ciência da política não se transforma em autorização promocional. A revisão
visual anterior do leitor não certifica automaticamente o novo conteúdo.

## Evidência e limites da conclusão

- `privacy-orphan-unit-first-25oct.log`: 33 unitários aprovados na fundação, antes
  do reforço de confirmação de ausência.
- `privacy-orphan-integration-first-25oct.log`: unitários passaram; as quatro
  suítes de integração não iniciaram por PostgreSQL local indisponível. **Não é
  build aprovado.** O cluster existente foi recuperado, sem inicialização/substituição.
- `privacy-orphan-integration-recovered-25oct.log`: 50 unitários e 43 integrações
  aprovados, incluindo 12 cenários novos, arquivos HTTP, V37 e compatibilidade.
  Essa rodada antecede a confirmação independente e os casos adicionais de formatos.
- Rodadas posteriores de confirmação e regressão devem ser conferidas no log e
  registro de evidências do complemento, sem somar execuções sobrepostas.
- `privacy-orphan-confirmation-first-25oct.log`: **51 unitários e 43 integrações
  aprovados**, com 13 casos de órfãos, 11 de recuperação posterior ao backup
  (incluindo WebP/PDF reais), dez de remoção anterior e nove compatibilidades.
  A redução adicional de observação após confirmação foi conferida na rodada seguinte.
- `privacy-orphan-final-25oct.log`: **76 unitários aprovados; 169 integrações
  executadas, com um erro no cenário de atualização não material**, que tentava
  usar revisão 2 fixa após a minuta corrente passar a revisão 2. A trava de
  publicação recusou corretamente outro conteúdo na mesma revisão. Os demais
  cenários passaram; **esse log não é BUILD SUCCESS**. O cenário passou a usar
  revisão corrente +1, sem mudar a trava ou o código de publicação. A rodada
  corretiva deve ser conferida antes de declarar fechado o conjunto jurídico.
- `privacy-orphan-legal-confirmed-25oct.log`: configuração e **15 integrações
  jurídicas aprovadas**, com a revisão seguinte calculada a partir da minuta
  corrente. O código de publicação não foi afrouxado. O conjunto ampliado anterior
  continua registrado como falha de build; a correção teve repetição direcionada,
  sem somar os 15 cenários repetidos aos 169 como se fossem novos.

Fonte, hashes, seleção e limites em `evidence/orphan-files-25oct.json`. O HTML/CSS,
DS, assets e o jar protegido V26 não foram publicados ou migrados nesta etapa.

Continuam pendentes documentos ligados a candidaturas antigas, expurgo de metadados
conhecidos, revisão integral de financeiro/logs e supressões anteriores, recuperação
externa de chaves/controle, demais categorias/volumes, minimização, jornadas/aceites,
consistência jurídica, red teams e publicação coordenada. Não houve cobrança ou
envio real de e-mail nos ensaios. O resultado não encerra os 44 requisitos.
