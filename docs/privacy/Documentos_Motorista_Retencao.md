# Documentos de motorista e metadados de arquivos — V40

Implementação na fonte, sem publicação na demonstração V26. Este complemento
pertence aos 44 requisitos; não encerra a auditoria nem certifica operação real.
Validação delimitada de backend e confirmação visual móvel concluídas neste
checkpoint. Desktop tem conferência de conteúdo/geometria, mas as capturas
ficaram desfocadas ou recortadas no navegador e não certificam seus pixels.
Os resultados têm alcance próprio, sem certificação dos 44 itens.

## Diagnóstico

Uma referência em qualquer `driver_applications` impedia o coletor de uploads
abandonados de preparar a eliminação. Isso incluía solicitações substituídas,
recusadas e aprovadas há muito tempo. Não havia prazo executável para essas
cópias. Também não havia retirada dos metadados de arquivo após eliminação física.

Foram distinguidos três objetos: cópia do documento, decisão administrativa e
habilitação do motorista/veículo. Apagar a cópia não é revogar a aprovação. Uma
solicitação ainda não decidida também não pode ser aprovada quando seus anexos
deixaram de poder ser conferidos.

## Decisão por finalidade

| Objeto | Regra executável | Efeito |
| --- | --- | --- |
| Solicitação pendente | 30 dias desde criação | Vencida antes da análise, exige nova solicitação; sem aprovação automática ou reativação de aprovação anterior |
| Cópias após decisão | 30 dias desde decisão | Vínculos ordinários encerrados; decisão aprovada e veículo não são revogados por essa limpeza |
| Solicitação substituída | Conserva seu prazo já registrado | Substituir não renova a guarda da candidatura anterior |
| Documento usado em outra candidatura não vencida | Conferência de todos os vínculos sob trava do titular | Não eliminar uma cópia ainda necessária à outra solicitação |
| Novo reenvio explícito antes de concluir formulário | Janela de 24h desde último upload real | Preserva a nova preparação; não reabre a solicitação vencida |
| Preservação por caso | Recurso/fundamento/revisão/vencimento específicos | Cópia permanece privada no fluxo auditado do caso, sem restaurar link comum |
| Metadados de arquivo eliminado | Elegíveis após 90 dias da remoção física | Conferir ausência, referências, preservação e identidade antes de retirar `stored_files` |

30 dias e 90 dias são políticas operacionais propostas, **não prazos universais
impostos pela LGPD**. A janela pós-decisão suporta conferência/revisão próxima;
casos concretos usam preservação própria. Sem necessidade específica, guardar
todas as cópias por vários anos ampliaria a exposição sem melhorar automaticamente
a prova. Apagar imediatamente após decisão reduziria exposição, mas prejudicaria
conferência próxima. A opção implementada conserva prova mínima e permite casos
justificados sem tornar todo anexo permanente.

Aplicam-se finalidade, necessidade, segurança e término do tratamento, conforme
arts. 6º, 15 e 16 da [LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
A análise solicitada integra procedimentos de cadastro/contratação. Defesa e
segurança de casos concretos têm fundamento e escopo próprios; não se atribui
consentimento genérico a todas as cópias. O prazo e as bases aplicáveis ao conteúdo
real dos documentos, ao modelo de mobilidade e a eventual dado sensível exigem
**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Esta etapa não declara
inexistência de obrigações setoriais nem oferece garantia de autenticidade.

## Banco e compatibilidade

A V40 acrescenta `documents_expires_at`, `documents_retired_at` e
`documents_policy_version`; admite `expired` em candidaturas e permite que as
referências de documentos sejam removidas. O vínculo com veículo/decisão continua
distinto. Uma restrição impede marcar documentos retirados com links restantes
ou candidatura ainda pendente. Índices suportam seleção por vencimento e remoção
de metadados de arquivos eliminados.

Legados usam `coalesce(reviewed_at,created_at)+30 dias`, sem data nova de upload
ou de análise fabricada pela migration. Nenhuma linha, aprovação ou arquivo é
apagado pela migration. Migrations anteriores e o manifesto legado permanecem
inalterados; as alterações explícitas de nulabilidade/estado são verificadas
separadamente da comparação dos demais objetos legados.

## Preparação e remoção

`PrivacyDriverDocuments` confere o controle de preservação independente, trava
o titular e novamente a candidatura. Retira os links vencidos, marca a solicitação
pendente como vencida, encerra o veículo ainda pendente e notifica uma vez. Não
promove outro cadastro antigo a aprovado. Apenas arquivos sem outro vínculo,
foto atual ou preparação recente são marcados para eliminação.

Uma preservação não impede retirar o link ordinário. A captura independente do
caso também conserva os indicadores de indisponibilidade. O processamento físico
continua por `PrivacyFileErasure`, com instrução durável e prova após commit;
quota só é liberada após a remoção real. Falha mantém pendência e retentativa.

O job roda antes dos coletores de uploads/bytes órfãos e da remoção física.
A preparação de recuperação também encerra finalidades vencidas e remove
documentos já preparados sem preservação antes da prontidão. Problema no
armazenamento ou dependência não confirmada impede declarar essa etapa concluída.

`PrivacyFileMetadataRetention` roda depois da passagem de remoção física. Usa
cursores/lotes, não confunde linha ocupada com inexistente e não retira uma linha
se bytes reapareceram. A supressão conhecida deve tratar esses bytes primeiro.
A retirada da linha não elimina a instrução independente do arquivo nem registros
financeiros. O evento de limpeza conserva contagem, sem copiar novamente o UUID
do arquivo para esse evento.

## Consulta, revisão e exportação

O endpoint comum confere a finalidade no instante da leitura, mesmo antes do job:
documento tem uma preparação recente ou candidatura ainda vigente. Preservação
não concede um novo link comum a administrador ou titular. O fluxo do caso usa
senha novamente e registro de revelação, como antes.

Listas informam estado/prazo/política e não fornecem IDs de anexos retirados.
Administração oculta links e decisão quando não há documentos para análise. O
titular vê a solicitação vencida e o formulário de novo envio, ou a aprovação
mantida com consulta dos anexos encerrada. Formato/limite, janela inicial e regra
de guarda são explicados no cadastro com os componentes existentes.

Revisão confere estado atual do titular, prazo, dono/finalidade e bytes/tamanho/hash
reais antes da decisão. Idempotência não renova o prazo na repetição de uma decisão.
Upload e aprovação administrativa continuam sem provar autenticidade por si sós.

Exportação inclui metadados de ciclo de vida e não empacota cópias cuja finalidade
ordinária terminou. Uma cópia já removida durante a geração é informada em
`arquivos-indisponiveis.json`, sem tornar todo ZIP inválido. Erro de acesso ao
armazenamento não é presumido como eliminação; pode exigir nova tentativa. Não
há URL pública, credencial ou arquivo de exportação permanente.

## Recuperação de datas

Snapshot tipado de preservação formato 2 conserva `lastUploadedAt` real. Formato 1
continua verificável: para esse campo que antes não existia, utiliza-se a criação
do arquivo, conservadoramente. Recuperar não é reenviar. Forma legada misturada
com campos novos é recusada. Um arquivo ausente no banco restaurado não recebe
`now()` como se o titular tivesse feito upload novamente.

API/worker e operador precisam de publicação coordenada: ferramentas antigas
devem recusar formato desconhecido, sem ignorar a proteção ou reexpor anexos.

## Limites ainda abertos

Este complemento trata cópias e a linha de arquivo após eliminação. Não conclui
o prazo de todos os metadados de candidatura/decisões, nem financeiro, logs e
agregação anônima. Necessidade de solicitar identificação adicional à CNH e o
método de verificação efetiva também precisam do fechamento operacional.
Continuam os exercícios externos de recuperação, volumes/categorias, minimização
restante, jornadas materiais, consistência jurídica, red teams e os dezesseis
entregáveis finais. Versões anteriores das minutas foram preservadas em
`legal/history/`; Privacidade r3 e Motorista r2 continuam em catálogo `draft`.

## Evidência

A primeira rodada passou em migração e arquivos, mas a comparação com resposta
legada precisava reconhecer três campos novos sem deixar de comparar os demais.
A rodada seguinte identificou fixtures com cronologia inválida de remoção e
administrador ausente no backup; essas recusas não foram desativadas. A terceira
tentativa parou na compilação por variável local repetida na exportação e não
executou testes. Após as correções, a rodada direcionada passou com 32 unitários
e 46 integrações (13 específicas, 15 jurídicas e 18 de direitos). Essa rodada
antecede o reforço de preservação da data real de upload e seu caso legado.
A regressão ampliada passou nos 58 unitários e executou 128 integrações, com dois
erros no coletor de blobs que ainda recusava o novo formato tipado 2. Não foi um
`BUILD SUCCESS`. O coletor passou a reconhecer os formatos 1/2 conhecidos, sem
aceitar formato desconhecido. O cursor das passagens periódicas também avança
antes de uma tentativa para que um registro conflitante não monopolize a fila.

`privacy-driver-documents-final-25oct.log` passou em **49 unitários e 38
integrações**, sem falhas/erros/skips: 15 cenários específicos, onze recuperações
posteriores ao backup, cinco rotações de evidência e sete pontes de confirmação.
`privacy-driver-documents-js-final-25oct.log` passou em **46 testes JS**. O pacote foi
reconstruído após o último texto de interface, sem executar a aplicação real.
Não somar rodadas sobrepostas nem apresentar rodadas com falha como aprovadas.

O [registro de evidência](evidence/driver-document-retention-25oct.json) identifica
fontes, logs, resultados com falha e aprovação, sem somar execuções sobrepostas.
A [revisão delimitada de interface](../../.impeccable/review/privacy/driver-document-retention-review.md)
documenta a confirmação móvel, comparação com o DS e limites de desktop.
A fixture de navegador é separada da prova de recuperação; usa contas
`@example.test`, provedores `development` e base descartável, sem cobrar ou
enviar e-mail real. Nela o controle independente de recuperação está desativado;
a prova correspondente está nas integrações, não nas capturas.

Na primeira passagem visual foram encontrados: duplicidade do aviso de espera,
texto “Análise registrada” para candidatura vencida sem decisão e orientação de
rolagem somente depois de uma tabela longa. Os três foram corrigidos em um lote;
a confirmação final é delimitada a esses achados e aos estados acrescentados.
A cronologia dos dados sintéticos também foi alinhada a criação/upload anteriores
à análise, sem usar data de recuperação como se fosse envio real.
