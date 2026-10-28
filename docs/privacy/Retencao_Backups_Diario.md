# Retenção de cópias e diário independente

Complemento 26/10: [base física e WAL cifrados](Recuperacao_Transacional_PITR.md).
O recurso contém a base e os segmentos de uma captura finita, com vencimento
original único e reserva anterior ao início. O ponto do catálogo é o início da
base, mesmo quando WAL posterior permite avançar o banco: usar o alvo de replay
como se a base não contivesse dados antigos liberaria supressões cedo demais.
O ensaio recupera eventos posteriores e reaplica controles independentes antes
da API; não instala backup contínuo/offsite nem certifica RPO zero. Os resultados
históricos abaixo conservam seu alcance; não são prova desse novo ensaio.

Complemento 25/10: a fonte V38–V39 acrescenta controle cifrado/assinado e
reaplicação tipada de preservações posteriores ao backup, descritos em
`Recuperacao_Retencoes.md`. A confirmação de conclusão das supressões antigas de
conta/arquivo tem provas delimitadas em `Confirmacao_Supressoes.md`. Recuperação externa
de arquivos/chaves continua pendente. Preparações incertas são conservadas;
somente um marcador efetivamente confirmado pelo banco permite finalizar uma
proposta correspondente. Os checkpoints históricos abaixo mantêm seu alcance.

Implementação V33 em validação local, sem publicação da camada completa na demonstração V26. O prazo padrão de 35 dias é uma política operacional configurável, não um prazo geral imposto pela LGPD. A retenção de evidência de um caso deve ser justificada separadamente. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Problema resolvido

Remover um perfil do banco atual não basta se uma restauração posterior recolocar o perfil, a sessão e seus arquivos. O diário independente já reaplicava essas supressões. Porém, manter esse diário com identificadores indefinidamente também seria excessivo, e apagá-lo apenas por idade permitiria reativação por um backup antigo.

A V33 liga o fim do diário à eliminação física das cópias anteriores e conserva um marco mínimo de recuperação sem UUID do titular ou da exclusão. Esse é um controle técnico da base, não uma estatística anônima nem uma afirmação de anonimização do histórico financeiro. Um banco anterior ao marco não abre a API nem libera o worker. Uma cópia mais recente permanece recuperável, com os arquivos já marcados como apagados novamente suprimidos antes da readiness.

## Inventário independente

`PRIVACY_OPERATIONS_ROOT` aponta para uma pasta privada própria do ambiente. Somente os filhos imediatos de `backups/` e `archives/` são recursos sujeitos a expurgo. `config/`, `runtime/`, `data/`, o PostgreSQL ativo e o VHDX ativo não são alvos dessa limpeza.

Cada cópia tem identificação aleatória, contêiner, nome de recurso, data de captura, ponto conservador de restauração, vencimento, instante de eliminação e assinatura. O catálogo fica em `retention/copies/`, fora das cópias e do banco restaurado. O arquivo `retention/store.json` identifica o armazenamento, o banco vinculado, o índice de integridade do diário e o marco de recuperação. As chaves ficam somente na configuração privada.

- Registro repetido não prolonga o vencimento. Rotação de assinatura também preserva os prazos.
- Redução posterior da política pode antecipar a limpeza; aumentar a configuração não estende um prazo já registrado.
- Uma cópia desconhecida, assinatura inválida, chave de verificação ausente ou caminho redirecionado interrompe o expurgo e conserva o diário.
- A limpeza é limitada por lote, confirma a ausência física e só então registra conclusão. Cópias parcialmente apagadas continuam impedindo a expiração correspondente do diário.
- Registros de cópias recém-eliminadas não monopolizam o lote: cópias vencidas ainda presentes continuam sendo processadas. Metadados mínimos do catálogo expiram 90 dias após a eliminação, se o recurso não reapareceu.
- Arquivos temporários de escrita atômica reconhecidos expiram após um dia. Um arquivo desconhecido não é tratado como temporário seguro.
- Evidência relevante a um processo deve ser extraída para o cofre específico do caso. Um hold não é autorização para conservar indefinidamente todo backup.

`PrivacyOperationFiles` verifica caminhos reais, recusa links/junctions, usa arquivos temporários com substituição atômica e força gravação antes de confirmar. A limpeza não recebe caminhos de requisições HTTP. Não existe endpoint público para registrar ou apagar backups.

Ausência é confirmada por leitura de atributos: acesso negado ou erro de armazenamento não é tratado como eliminação bem-sucedida. Este adaptador exige os contêineres e seus filhos no sistema de arquivos local configurado, recusando montagens distintas. Armazenamento remoto/offsite precisa de um adaptador verificado próprio. A trava compartilhada aguarda contenção breve entre API, worker e operador; não confirma operações concorrentes sobre um estado desatualizado.

## Captura de novos backups

O escritor deve reservar o recurso **antes** de iniciar a captura transacional do banco. Enquanto estiver pendente, o recurso é conservador e impede a expiração do diário. Depois do dump completo, finaliza-se o registro com o ponto de captura do snapshot. Nome de novo recurso é um UUID aleatório, sem nome/e-mail de pessoa.

Cópias históricas e coleções que podem conter outros backups são importadas como `historical`: continuam barrando expiração até sua eliminação, independentemente de uma data recente de cópia do diretório. Copiar arquivos antigos novamente não é evidência de que os dados são recentes.

O ensaio `postgres-sandbox-backup-verify.mjs` foi ligado ao protocolo: reserva um UUID no catálogo privado antes de iniciar a transação de snapshot, usa seu instante de início como ponto conservador e só finaliza após comparar dados e schema restaurados. Se houver falha, a reserva continua conservadora e o resultado é registrado. Uma pasta criada por outra ferramenta sem registro impede a expiração do diário; não é ignorada.

## Fim do diário e restauração

Uma entrada só pode expirar depois do seu prazo mínimo, de confirmação assinada da supressão efetivamente concluída e quando nenhuma cópia registrada anterior ou igual à confirmação sobrevive. O verificador consulta novamente o banco e o armazenamento; uma intenção antiga, isoladamente, não comprova conclusão. Isso inclui cópias feitas entre o preparo do pedido e a remoção efetiva. Uma cópia histórica em qualquer idade continua relevante. Cópias posteriores, obtidas de um banco já atualizado, não conservam indefinidamente a entrada de cada usuário.

Antes de remover a entrada, o banco confirma seu horizonte de observação em transação própria. Uma confirmação que chegue depois desse horizonte aguarda outra passagem. O sistema então grava uma intenção assinada contendo as impressões anterior/posterior e eleva o marco de recuperação até a confirmação de conclusão. A operação pode ser retomada se o processo parar antes ou depois da escrita/remoção física. Perda seletiva ou rollback de um arquivo do diário é detectada pelo índice independente, mesmo quando as assinaturas das outras entradas são válidas. A ferramenta que conhece somente arquivos não recebe autorização para atestar o estado do banco ou retirar suas instruções.

Após a conclusão, o marco permanente contém somente dados técnicos de armazenamento/banco, data mínima e impressão do conjunto vigente. O UUID do titular e o identificador da exclusão removida não permanecem nesse marco. O arredondamento do corte de tempo é conservador para não abrir uma janela inferior a um milissegundo.

`privacy_restore_state` começa com `observed_at=NULL`. Aplicar a migration sobre um banco antigo não fabrica uma data recente. O banco tem identidade técnica própria, vinculada uma vez ao armazenamento independente; outra base não pode reutilizar suas supressões. O watermark não pode regredir ou mudar de identidade por um UPDATE normal. O guard verifica esses dados antes de reaplicar entradas e liberar o serviço.

A reaplicação de uma exclusão antiga conserva o instante de preparo separado do momento em que foi reaplicada. O prazo mínimo do diário pode já ter passado; isso não autoriza reexpor um perfil de uma cópia que continuou existindo.

Arquivos conhecidos como apagados são verificados novamente na inicialização e por varredura periódica em lotes. Assim, restaurar só os bytes de uma foto não a conserva indefinidamente só porque a entrada de usuário no diário já expirou. Arquivos preservados por hold ainda não apagados seguem sua regra específica.

## Chaves e compatibilidade

Há anéis independentes para assinatura de catálogo e de diário:

```dotenv
PRIVACY_BACKUP_KEY_ID=
PRIVACY_BACKUP_KEYS=
PRIVACY_JOURNAL_KEY_ID=
PRIVACY_JOURNAL_KEYS=
```

Cada `*_KEYS` é um objeto JSON `id → segredo aleatório base64url`. O instalador local gera 32 bytes aleatórios para cada finalidade, sem imprimir os valores. As duas finalidades não podem reutilizar o mesmo segredo. Produção exige configuração explícita; a derivação local de desenvolvimento é identificada e não é utilizada como fallback silencioso em produção.

Para rotacionar: adicionar a chave nova mantendo as de verificação antigas, selecionar o ID atual, re-assinar em lotes e conferir os registros ativos e as cópias independentes de recuperação. Só depois se retira uma chave antiga. Retirar cedo causa falha segura, sem apagar ou reescrever a evidência. Prazos, identidade e estado de confirmação não mudam por re-assinatura. Conta v1 continua verificável com o pepper original, conta v2 com a chave dedicada e ambas são re-assinadas como v3 pendente de prova de conclusão. Arquivo conhecido v1 passa a v2 sob a mesma regra. Uma ferramenta antiga pode recusar o novo formato: API, worker e operador precisam de publicação coordenada.

Essa implementação **não rotaciona** `TOKEN_PEPPER`, chaves financeiras, sessão, SMTP ou Mercado Pago. A separação/rotação das chaves AES do cofre e de outros textos de privacidade ainda precisa ser concluída; não foi considerada resolvida pelos anéis de assinatura.

## Operação separada da aplicação

`PrivacyCopiesCommand` é um comando de arquivos. Não cria contexto Spring, datasource, migrations ou conexão com provedor. O launcher privado valida o SHA256 do jar e disponibiliza apenas configurações necessárias à operação. Não exige JWT, token de pagamento, senha SMTP ou chave AES. Pepper legado só é necessário quando há entradas V1 a verificar.

O instalador `backend-java/scripts/install-private-operations-local.ps1` prepara o artefato imutável e as chaves dedicadas sem trocar o jar da API/worker. O launcher instalado fica em `Private/runtime/scripts/private-copies-command.ps1`.

Operações: `initialize`, `import-legacy`, `status`, `reserve`, `finalize`, `prune`, `rekey`. Importação precisa da captura original; reexecutá-la não renova prazos. O comando de arquivos pode expurgar cópias vencidas, mas **não expira o diário por conta própria**: essa parte depende do worker com a proteção de banco V33.

`PrivacyCopiesMaintenance` integra o worker, com tentativa em lotes e retentativa após falha. Um erro na retenção de arquivos é registrado por código e não impede o processamento de pagamentos e mensagens. O worker só recebe a liberação inicial quando a recuperação de privacidade conclui; um sinal o acorda imediatamente, independentemente do intervalo de fila configurado.

## Limites ainda abertos

- O comando foi instalado e ensaiado no ambiente privado: oito recursos históricos importados e um novo backup reservado/finalizado. O ensaio nativo de 17/10, 13:25 confirmou 52 tabelas, 2.016 linhas, schema V26 e ledger com diferença zero; o banco de restauração temporário foi removido. O catálogo ficou com nove recursos, zero entradas de usuário e nenhum trabalho pendente. A sondagem de expurgo removeu zero arquivos porque ainda não havia cópias vencidas.
- Ligar as demais ferramentas de restauração à recusa operacional de cópias vencidas. Não restaurar uma versão antiga do catálogo/diário sobre a versão independente atual. A barreira de inicialização V33 foi testada com arquivos reais, mas ainda não foi publicada na demonstração V26.
- Na publicação coordenada, atualizar os launchers/artefatos permitidos para impedir executar um binário anterior à privacidade contra a base migrada. A barreira do código V33 não é implementada retroativamente dentro do jar V26 preservado.
- Manter backup independente e protegido das chaves e do armazenamento de controle, com procedimento de recuperação fora do mesmo computador. O controle local não prova resistência a perda completa do disco ou restauração simultânea de toda a pasta privada para uma versão antiga.
- Executar a variante Docker quando a virtualização estiver disponível. Os ensaios desta fase usam PostgreSQL nativo real e banco descartável.
- A separação/rotação AES das evidências foi implementada depois desta etapa, em V34; ver `Criptografia_Evidencias.md` para escopo, testes e limites do correio original. Permanecem retenção financeira/logs e estatística anônima, gestão de holds, confirmações finais, minimização e interface. Documentos continuam minutas até a publicação coordenada.

## Evidências desta etapa

- `Private/logs/privacy-copies-unit-first.log`: 14 unitários aprovados na fundação inicial.
- `Private/logs/privacy-copies-integration-first.log` terminou com falha: os testes novos encontraram que reaplicar uma entrada com prazo mínimo já passado violava a constraint antiga. A V33 separou o preparo original do momento de reaplicação; a rodada corrigida passou com 14 unitários e 16 integrações. Não contabilizar a primeira como build aprovado.
- `Private/logs/privacy-copies-operator-worker.log` também terminou com falha: dois cenários expuseram a espera indevida do worker após a readiness. O teste unitário passou a usar uma espera de fila longa e a correção adicionou sinal de liberação/encerramento durante a espera de recuperação.
- `Private/logs/privacy-copies-worker-wakeup-final.log`: 30 unitários e 79 integrações aprovados, sem skips, incluindo worker real, jobs, e-mail, correção de identidade, direitos, migrações e as duas formas de restauração física.
- `Private/logs/privacy-copies-concurrency-final.log`: 31 unitários e sete integrações aprovados após o teste de contenção e a espera limitada da trava.
- `Private/logs/privacy-copies-io-legal-final.log`: 31 unitários e 20 integrações aprovados após a verificação de ausência por atributos e do sistema de arquivos; inclui 13 cenários de documentos/aceites. Rodadas se sobrepõem: não somar como casos distintos.
- A execução do comando instalado confirmou nove recursos, zero entradas individuais no diário, marco zero, armazenamento ainda não vinculado a banco V33 e ausência de operação pendente. A sondagem de `prune` removeu zero caminhos vencidos. Isso não é prova de expurgo automático já em execução na demonstração V26.
- O ensaio real de reserva/snapshot/dump/restore/finalização está em `Private/backups/<UUID>/verification.private.json`; é conteúdo privado, sem linhas pessoais no relato público. A consulta posterior confirmou V26, ledger equilibrado e zero parcelas antigas `no_class` retidas. Não houve cobrança ou novo envio real de e-mail nesta fase.

## Complemento V37: supressão individual de arquivos

O mesmo índice assinado agora inclui entradas `file-<UUID>` para arquivos removidos
sem encerramento de conta. Publicação/remoção interrompidas, rotação sem extensão
de prazo e expiração condicionada às cópias anteriores usam o mesmo protocolo.
O comando de operações atualizado informa `fileJournalEntries`; deve ser instalado
junto com API e worker na publicação coordenada. O comando antigo recusa esse novo
formato, em vez de ignorá-lo silenciosamente.

`Arquivos_Sem_Uso.md` descreve seleção, revalidação, quota e replay antes da
prontidão. Há ensaio adicional com `pg_dump`/`pg_restore` que restaura uma foto
apagada como foto atual de uma conta ativa: a instrução individual remove o arquivo
e o vínculo antigo, preservando conta, sessão e estado financeiro. Recuperação
externa do controle/chaves e preservações criadas depois do snapshot continuam
pendentes; o complemento não autoriza restaurar uma versão antiga do diretório
independente ou apagar arquivos desconhecidos indiscriminadamente.

## Complemento V38 — decisões posteriores ao backup

A fonte de 25/10 liga `PrivacyHoldRecovery` aos comandos administrativos, cofre,
remoção, rotação e manutenção. Uma restauração reaplica o controle tipado mais
novo **confirmado**, com arquivos privados cifrados independentes, antes das
supressões e da limpeza de localização. Resultados incertos e dependências
ausentes impedem prontidão sem inventar usuários, autores, contratos ou aceites.
Revisões/liberações posteriores e prazos originais prevalecem.

As limitações anteriores sobre holds posteriores passam a ter esse complemento
local implementado e testado em escopo delimitado; não significam mais ausência
de qualquer replay. Detalhes, rodadas físicas, expiração, rotação e pendências em
`Recuperacao_Retencoes.md`. Não houve atualização da ferramenta instalada, migração
da demonstração V26, recuperação fora do host ou publicação conjunta nesta etapa.
