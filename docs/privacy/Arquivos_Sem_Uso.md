# Arquivos sem uso e supressão individual

Implementação original V37, com complemento V39 para bytes sem metadados descrito
em `Arquivos_Orfaos.md`; ainda não publicada na demonstração
V26. Não altera caronas, saldo, comissão, pagamento ou encerramento da conta.

## Decisão de produto e finalidade

Uploads sem vínculo podem ser preparados para remoção após **24 horas do último
envio**. O prazo acomoda o preenchimento e envio de formulários. Reenviar um arquivo
deduplicado renova esse intervalo de uso; reutilizar o mesmo conteúdo não permite
que o coletor o remova com base apenas na data original de criação.

Foto atualmente vinculada ao perfil e identificação/habilitação vinculadas a
qualquer candidatura de motorista não são classificadas como abandonadas. Arquivos
com preservação ativa por caso também não entram na seleção. A política específica
dos documentos de candidaturas antigas foi acrescentada separadamente na V40,
descrita em [Documentos de motorista](Documentos_Motorista_Retencao.md). Este
coletor de abandonados continua sem descartar um documento vinculado por
conveniência; a rotina própria encerra primeiro a finalidade e os vínculos.

O intervalo de 24 horas é uma escolha operacional proposta, **não um prazo imposto
pela LGPD**. A revisão de necessidade e de fim de finalidade considera os arts. 6º,
15 e 16 da [LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Preparação, remoção e quota

O worker seleciona candidatos por data do último envio. Sob a trava compartilhada
com preservações e o bloqueio da linha do titular, verifica novamente idade do
upload, titular, vínculos e preservação vigente. Upload de foto e candidatura já
bloqueiam essa mesma linha, impedindo usar uma lista antiga para remover um arquivo
que acabou de ser associado ao perfil ou à candidatura.

Preparar a remoção marca o arquivo como indisponível no endpoint comum. A remoção
física exige nova verificação de preservação, registro independente durável e
eliminação verificada do arquivo. Falha conserva a pendência para nova tentativa.
Quota só deixa de contar o arquivo depois da confirmação física; não é liberada
apenas por marcar um registro como apagado. Arquivos preservados continuam contando
enquanto seus bytes precisam permanecer armazenados.

## Diário por arquivo

O diretório independente existente aceita entradas `file-<UUID>` além das entradas
de encerramento por conta. Cada entrada de arquivo contém titular, arquivo, preparo,
prazo mínimo e assinatura. Não contém conteúdo, e-mail, nome ou caminho arbitrário.
É evidência pseudonimizada e de supressão, **não anonimização**.

A publicação é registrada antes da remoção física e participa do mesmo índice
assinado, exclusão mútua entre processos e recuperação de operação interrompida.
Entrada perdida, trocada, adulterada ou conflitante com o índice impede avançar.
Retentativas e rotação de assinatura preservam a data inicial e o prazo original.

O prazo mínimo é o intervalo configurado de backups mais dois dias operacionais,
contados do primeiro preparo durável para remoção. Não se conta desde um pedido
antigo que ficou meses aguardando falha ou preservação. Expiração só retira a
entrada quando não resta cópia anterior capaz de reintroduzir o dado. Contas e
arquivos compartilham uma fila cronológica com limite de lote. Após retirar a
entrada, o marco independente recusa bancos de dados anteriores a esse limite.

## Restauração

Antes da prontidão, o servidor verifica o catálogo/diário, aplica encerramentos e
reaplica supressões individuais de arquivos. Um registro antigo que aponta para a
foto apagada perde esse vínculo, sem excluir ou suspender a conta ativa. O endpoint
comum continua sem acesso ao arquivo. Um caso vigente pode preservar bytes privados
sem tornar o arquivo restaurado disponível pelo endpoint comum.

A entrada individual permite remover bytes restaurados mesmo quando o banco de
dados não contém a linha do arquivo. Se a linha existe, titular e chave de
armazenamento precisam corresponder à evidência assinada; conflito falha fechado.
Falha física no replay impede a prontidão até que a supressão seja concluída.

O comando de cópias informa `fileJournalEntries`. A publicação coordenada deve
atualizar **API, worker e ferramenta de operações**. O binário antigo da ferramenta
não entende as novas entradas e recusa o diário; não substituir o catálogo por uma
cópia antiga para contornar essa recusa.

## Limites que continuam no objetivo

- Recuperação posterior ao snapshot foi implementada/testada localmente em escopo
  delimitado (`Recuperacao_Retencoes.md`); demais categorias/dependências, operação
  assistida e recuperação externa do controle/chaves continuam abertas.
- A V39 acrescenta classificação de bytes sem linha/entrada anterior, observações
  estáveis por 24 horas, coordenação de upload e supressão exata confirmada. Não é
  varredura cega nem classificação automática de qualquer nome no diretório.
- Prazos dos documentos vinculados a candidaturas antigas, metadados de arquivos
  removidos, dados financeiros e logs continuam na revisão final de retenção.
- O coletor trabalha em lotes e depende de worker ativo. “Após 24 horas” é o limiar
  de elegibilidade; não promete remoção pontual no segundo exato nem apaga cópias
  privadas preservadas, backups anteriores ou arquivos em sistemas de terceiros.
- As demais frentes dos 44 requisitos, incluindo inspeção real da interface,
  administração de direitos e relatório final, permanecem em andamento.

## Verificações

Foram conferidos **26 unitários e 87 integrações**, sem falha/erro/skip nas evidências
finais de cada suíte. São execuções complementares; rodadas anteriores não são
somadas como casos distintos.

- `PrivacyCopiesTest`: 23 unitários de assinatura/índice, integridade, recuperação
  interrompida, rotação, expiração condicionada a cópias e ordenação de lotes mistos.
- `PrivacyUnusedFilesMaintenanceTest`: três unitários de parada e falha. A primeira
  falha encerra o lote, evitando repetir a espera da trava por todos os uploads e
  atrasar desnecessariamente o restante do worker.
- `PrivacyFileRetirementHttpIT`: dez integrações, incluindo vínculo atual/documento
  associado, arquivo recente, preservação depois da seleção, concorrência com uso,
  quota antes/depois da remoção física, replay com/sem linha no banco, falha física
  impedindo prontidão, reenvio deduplicado e ordem real das tarefas no Spring.
- `PrivacyPhysicalRestoreIT`: dois ensaios com arquivo real `pg_dump`/`pg_restore`,
  cobrindo exclusão de conta e supressão individual de foto de conta ativa.
- `FlywayCompatibilityIT`: oito integrações. O upgrade V36→V37 preserva a criação
  como data inicial do último envio, sem fabricar atividade recente. Asserções de
  estrutura legada mantêm os demais campos/triggers e distinguem as adições novas.
- Regressão complementar: arquivos 13, candidaturas de motorista 11, preservações
  13, avisos de encerramento dez, marco de restauração três e direitos próprios 17.

`privacy-file-retirement-first.log` passou na fundação inicial; a rodada ampliada
`privacy-file-retirement-final.log` encontrou duas contagens antigas de migrations.
Elas foram corrigidas em `privacy-file-retirement-compatibility-final.log`, que
passou. A nova verificação de ordem do worker inicialmente não iniciou por banco
local parado (`privacy-file-retirement-worker-final.log`); depois da recuperação
automática do PostgreSQL, `privacy-file-retirement-worker-recovered.log` passou.
Não apresentar os logs com falha como `BUILD SUCCESS`.

A demonstração foi retomada com o jar V26 e frontend privado congelado. Conferência
somente por leitura em 22/10, 20:10: schema 26, prontidão 200 e ledger equilibrado.
A V37 foi aplicada exclusivamente ao banco descartável de testes. Nenhuma cobrança
ou mensagem real foi iniciada pelos ensaios sintéticos.

O complemento V39 não altera esses resultados históricos. Logs e limites próprios
em `Arquivos_Orfaos.md`; a versão da ferramenta de operações também precisa ser
publicada junto da API/worker para compreender `orphan-<UUID>`.
