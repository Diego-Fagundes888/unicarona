# Confirmação de supressões de conta e arquivos conhecidos

Fonte V39, complemento de protocolo em 25/10/2026, sem migration nova ou
publicação na demonstração V26. Validação delimitada concluída; os resultados
ficam registrados abaixo e em evidência própria. O objetivo integral de 44 itens
permanece ativo.

## Falha encontrada e decisão

Os formatos antigos comprovavam o preparo da supressão, mas não distinguiam
conclusão real de uma remoção que falhou durante várias semanas. Usar apenas a
data de preparo para retirar a instrução deixava escapar uma cópia capturada
antes de a exclusão terminar. A assinatura de uma intenção não prova resultado.

O novo formato de conta é v3 e de arquivo conhecido é v2. Acrescentam
`confirmedAt` assinado. Contas v1/v2 e arquivos v1 continuam verificáveis e são
tratados como pendentes até conferência real. Trocar a chave atualiza o formato
sem inventar confirmação, alterar o preparo ou renovar o prazo mínimo original.
Assinatura inválida, propriedade desconhecida, JSON duplicado e conflito de
identidade impedem a interpretação normal da instrução.

## Prova após commit

`PrivacySuppressionConfirmationTransactions` consulta a base atual em transação
separada. Somente depois de commit publica a confirmação independente:

- Conta: perfil reduzido, credencial desabilitada, ausência de sessões/tokens,
  perfis acadêmico/motorista e conteúdos abrangidos pelo scrub; tarefas de
  exclusão concluídas. UUIDs contratuais/financeiros pseudônimos e cofre por
  finalidade seguem suas próprias políticas, sem alegação de anonimato.
- Arquivos da conta: metadados indisponíveis ao uso ordinário; arquivos sem
  preservação removidos fisicamente e marcados como apagados. Evidência sob
  preservação vigente precisa corresponder a tamanho/hash esperados e continua
  privada; a exclusão da conta não equivale à destruição dessa evidência.
- Arquivo individual: dono/UUID/chave coerentes, metadados suprimidos, ausência
  física verificada e ausência de preservação ativa. Remover só o arquivo não
  encerra a conta.

Falha, rollback e resultado de commit desconhecido não fabricam conclusão. A
passagem periódica retoma provas pendentes com cursores e lotes. Nenhum endpoint
HTTP ou comando de operador de arquivos foi criado para aprovar provas.

O upload obtém a trava do dono antes de criar bytes. Um ator de sessão antigo
não pode gravar um novo documento quando o banco já restringiu a conta. Travas
por arquivo continuam cobrindo o intervalo até a conclusão de metadados.

## Restauração e expiração

Reintrodução de dados torna a prova anterior pendente antes da nova supressão;
preparo/prazo mínimo permanecem originais. Perfil com `deleted_at` preenchido mas
nome, veículo, endereço ou outro conteúdo abrangido ainda identificável é
reprocessado. Uma inicialização já limpa preserva a confirmação existente.

O reprocessamento não duplica cópia autônoma de identidade/veículo/rota existente
nem renova sua data de retenção por reinicialização. Cópia mantida apenas por hold
não substitui uma finalidade financeira autônoma. Os prazos da nova cópia
autônoma partem do preparo original da tarefa, e o prazo do aceite não ganha
mais cinco anos a cada replay.

Para expirar, exigem-se conjuntamente prazo mínimo vencido, confirmação,
ausência física de cópias anteriores ou iguais à confirmação e nova conferência
do banco/armazenamento. Bloqueio de linha adia a retirada sem impedir para sempre
outras instruções elegíveis do lote. Constatar incompletude reabre a prova.

Antes de elevar o marco independente, o horizonte correspondente é confirmado
no banco. Arredondamento para milissegundo é para cima. Um backup anterior ao
marco recusa prontidão; atualizar o schema não fabrica um horizonte recente.
Uma cópia posterior válida continua recuperável. Preservações são recuperadas
antes das supressões e arquivos conflitantes são conservados para análise.

Quando uma preservação de arquivo termina, a presença de arquivo ainda não
eliminado também aciona o replay da conta, mesmo se o perfil já está reduzido e
a tarefa antiga consta como concluída. Essa pendência não depende de haver dados
novos no perfil para ser reconhecida.

Na pequena janela entre commit de preservação e callback, a manutenção só pode
finalizar a proposta que corresponda exatamente ao marcador já confirmado no
banco. Proposta sem esse comprovante continua impedindo trabalho destrutivo e
retira prontidão; não se presume aprovação nem rollback.

## Dados e finalidade

As instruções conservam UUIDs, datas mínimas, chave/signatura e confirmação;
arquivos conhecidos também conservam o UUID do dono. São dados pessoais
pseudônimos de supressão, não estatística anônima. Não há CPF, e-mail, conteúdo
do documento ou nova credencial nesse protocolo. O marco final sem identificação
individual permanece técnico. Aplicam-se minimização, segurança e necessidade
da política de recuperação; não foi criado novo prazo legal universal.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Conferir, no fechamento da
política integral, as finalidades/prazos do cofre e o inventário efetivo de cópias
externas. Esta confirmação não resolve por si a recuperação fora do dispositivo,
os prazos de documentos ligados, a retenção financeira integral ou a publicação
coordenada dos documentos e serviços.

## Validação delimitada

As primeiras rodadas de protocolo passaram com 36 testes unitários e depois
31 testes unitários + nove integrações reais. A segunda rodada antecedeu o
reforço de conteúdos dependentes e justiça do lote; não certifica a fonte final.
Foi usada somente a base descartável `unicarona_java_test`, em loopback:55432,
arquivos privados temporários e provedores sintéticos. Nenhum e-mail ou pagamento
real foi enviado.

`privacy-confirmation-regression-25oct.log` passou com **63 unitários e 86
integrações**, sem falhas/erros/skips. Abrangeu `PrivateFileStorageTest`,
`MaintenanceServiceTest`, `WorkerLifecycleTest`, `PrivacyCopiesTest`,
`PrivacyHoldControlJournalTest`, `LegalHttpIT`, `PrivacyFileRetirementHttpIT`,
`PrivacyOrphanFilesIT`, `PrivacyPhysicalRestoreIT`, `PrivacyPostBackupHoldsIT`,
`PrivacyRestoreFenceIT`, `PrivacyRightsHttpIT` e os onze primeiros casos de
`PrivacySuppressionConfirmationIT`. Essa rodada antecede o último ajuste de
reconhecimento de arquivo após fim de preservação e seus dois casos adicionais.
`privacy-confirmation-release-final-25oct.log` passou com **32 unitários e 37
integrações**, sem falhas/erros/skips, após esse ajuste. Conferiu os 13 casos de
`PrivacySuppressionConfirmationIT`, três de `PrivacyPhysicalRestoreIT`, dez de
`PrivacyFileRetirementHttpIT`, onze de `PrivacyPostBackupHoldsIT` e os 32 de
`PrivacyCopiesTest`. Os dois casos adicionais verificam arquivo preservado durante
a exclusão e removido após fim da finalidade, e adiamento real por linha de dono
travada em outra transação. As rodadas sobrepõem casos e não devem ter suas
contagens somadas. Os hashes e o alcance estão em
[`evidence/suppression-confirmation-25oct.json`](evidence/suppression-confirmation-25oct.json).
