# Recuperação independente de retenções — desenvolvimento

Checkpoint de fonte em 25/10/2026. Esta frente pertence ao objetivo integral dos
44 requisitos, que permanece ativo. A fonte agora conecta decisões administrativas,
cofre e arquivos ao controle independente e reaplica decisões confirmadas posteriores
ao backup. As provas e seus limites estão abaixo. Recuperação fora deste computador,
publicação coordenada e o objetivo integral **ainda não estão encerrados**.

Complemento V40: snapshots tipados de preservação agora usam formato 2 para
conservar o último upload real. O formato 1 continua legível com a criação como
fallback conservador, sem transformar recuperação em reenvio. Limpeza de blobs
reconhece os dois formatos conhecidos e continua recusando formato desconhecido.
Prazos da candidatura não são renovados pela recuperação. Ver
[documentos de motorista](Documentos_Motorista_Retencao.md) para fontes e provas.

Complemento de confirmação em 25/10: a manutenção pode finalizar a preparação
exata cujo commit já está comprovado pelo marcador correspondente do banco,
inclusive durante a janela normal antes do callback original. Esse callback
continua idempotente. Preparação sem marcador correspondente continua bloqueando
trabalho destrutivo e retirando prontidão; nenhum resultado é presumido. O
complemento [Confirmação de supressões](Confirmacao_Supressoes.md) registra o
teste concorrente real e a fronteira entre supressão, commit e expiração.

## Fundação implementada

`PrivacyHoldControlJournal` grava snapshots de controle cifrados e assinados fora
do banco e dos contêineres expirantes `backups`/`archives`, sob a raiz operacional
privada configurada. O conteúdo é vinculado ao dataset e à operação por AES-GCM;
a assinatura usa o anel dedicado do diário, com domínio específico. IDs opacos,
sequência e hash ficam no checkpoint, sem razão ou identificação de titular em
claro nesse índice. Isso continua sendo dado protegido/pseudônimo, não estatística
anônima. O componente não aceita caminhos de HTTP.

O catálogo operacional versão 2 conserva os campos anteriores e inclui cabeçalho
confirmado e preparação pendente. Versão 1 continua sendo verificada com sua
assinatura original; alterações posteriores preservam as âncoras novas. Não se
pode voltar a executar ferramentas antigas sobre um catálogo já atualizado.
A publicação precisa coordenar API, worker e ferramentas operacionais.

Uma preparação não é decisão aprovada. Reiniciar o leitor não a promove nem a
descarta. Registro ausente, alterado, de outro dataset, posterior ao cabeçalho ou
com chave indisponível provoca falha. Repor somente um índice antigo não oculta
um registro mais novo ainda presente. Restaurar **todo** o controle confiável para
uma cópia antiga continua exigindo uma âncora externa e procedimento de recuperação
confiável; não se promete detectar qualquer rollback por um administrador do host.

Versões antigas assinadas só são eliminadas em lotes depois de validar o registro
confirmado atual. Arquivos atuais ou preparados não expiram por essa limpeza.
Temporários reconhecidos, antigos e dentro do limite de tamanho têm limpeza
própria. Arquivo desconhecido ou redirecionado não é apagado como se fosse lixo.
Essa limpeza é interna e agora está ligada à manutenção de retenção, sob a mesma
trava de banco que coordena a preparação das cópias e decisões.

## Ponte transacional implementada

V38 cria `privacy_hold_control_state`: sequência, operação e indicação de
confirmação pendente. Não contém motivo, documento ou dado de perfil. O marcador
não pode regredir, trocar identidade na mesma sequência, ser apagado/truncado ou
ser confirmado por atualização comum. Contextos internos de confirmação/replay
não são APIs oferecidas ao navegador nem uma proteção contra um DBA privilegiado.

`PrivacyHoldControlTransactions.stage` é chamado em transação sincronizada, sob
a coordenação existente de retenção. Coalesce várias chamadas no mesmo comando;
um snapshot idêntico não cria revisão nova. O fornecedor do snapshot é interno.

1. Antes do commit, grava a preparação durável e associa seu identificador ao
   marcador no **mesmo commit** da alteração de retenção.
2. Depois de commit comprovado, confirma o arquivo independente e, em transação
   nova, limpa o indicador pendente do banco.
3. Depois de rollback comprovado, remove somente a preparação correspondente,
   conservando o estado confirmado anterior.
4. Se a execução interromper ou a confirmação falhar, não presume o resultado.
   O marcador pendente bloqueia trabalho destrutivo de privacidade, e a prontidão
   é retirada quando o callback não termina.
5. A recuperação de resultado conhecido só confirma a preparação cujo marcador
   foi realmente persistido naquele banco. Banco antigo sem esse marcador não
   prova rollback. Commit externo concluído com confirmação do banco interrompida
   pode ser reconciliado pelo mesmo identificador, sem criar outra operação.

A guarda de inicialização foi ligada e testada na conferência desse resultado
antes de reaplicar supressões e limpar localização. O primeiro conjunto da ponte
precede essa ligação; as rodadas finais abaixo a exercitam no aplicativo completo.

Depois que um banco possui checkpoint estabelecido, remover a configuração da
raiz operacional não volta ao comportamento de desenvolvimento sem controle.
A prontidão e a preparação são recusadas até recuperar o controle correspondente.
Essa regra adicional passou na última rodada; não se usa ausência
de configuração para fingir que o controle anterior nunca existiu.

## Captura e replay tipados — complemento de 25/10

`PrivacyRetentionSnapshot` define um esquema fechado: versão/tipo, retenções,
eventos, cópias pertinentes do cofre e descritores de arquivos. Não aceita nomes
de tabelas, SQL, comandos, campos adicionais ou caminhos de recuperação. Valida
UUIDs, titular/recurso, contexto das cifras, revisões, datas, duplicações e relações.
Há limites de dez mil itens por coleção e de 16 MiB para o JSON claro de controle;
ultrapassar o limite recusa a transação, sem confirmar uma preservação parcial.

`PrivacyHoldRecovery.stage` está ligado à criação/revisão/liberação dentro do
executor idempotente, à remoção de perfil, à limpeza do cofre/metadados e à rotação
de holds/eventos/cofre. A coordenação também cobre a rotação do cofre. Repetir a
mesma chave idempotente não publica outra revisão de controle. Estado idêntico é
no-op, exceto quando a cifra/assinatura externa precisa de troca de chave.

Na inicialização, o coordenador primeiro resolve somente resultados de commit
comprováveis. Se o cabeçalho **confirmado** independente é mais novo, aplica o
esquema tipado em uma transação e só então avança o marcador. Isso acontece antes
do replay de exclusões e da limpeza de localização. Revisão/liberação posterior
prevalece sobre a retenção antiga; prazos originais não são reiniciados. Registros
do cofre já sem finalidade vigente não são reintroduzidos.

O replay recusa divergência de titular, autoria, identidade imutável, regressão
de revisão, conteúdo conflitante e retirada indevida de prova ainda vigente.
Ele não cria usuários, sessões, recibos de aceite, viagens ou contratos. Titular
ou ator ausente exige recuperação assistida; recursos sem cópia no cofre e
ausentes do banco também impedem a prontidão. As cópias externas são conservadas.
Uma preparação sem marcador comprovado continua incerta: não é promovida nem
descartada para permitir restaurar um backup velho.

`PrivacyRetainedFileBlobs` conserva cada arquivo de até 5 MiB separadamente em
`hold-control-files`, cifrado e assinado com vínculos a dataset, blob, titular e
arquivo. SHA-256 e tamanho são conferidos no conteúdo e no registro cifrado.
Referências ficam dentro do snapshot cifrado; não são URLs públicas. A cópia
anterior é verificada antes da reutilização. Rotação gera blob imutável novo.
Parsers privados têm limites próprios compatíveis com esses tamanhos; os limites
do JSON das requisições públicas não foram relaxados.

Recuperação de arquivo ausente verifica a cópia e grava somente no armazenamento
privado; metadados recuperados ficam indisponíveis no endpoint comum. Um arquivo
existente com conteúdo diferente não é sobrescrito automaticamente. O acesso à
evidência continua dependendo de retenção ativa, administrador, senha e auditoria.
O replay de exclusão preserva bytes sob hold sem republicar o perfil da conta.

Ao vencer/liberar o caso, a captura seguinte retira as referências pertinentes.
A manutenção valida o esquema atual e suas cópias antes de retirar controles
antigos, depois elimina blobs sem referência em lotes. Atual e preparação pendente
protegem seus arquivos; blobs órfãos têm tolerância de um dia para cobrir uma
gravação ainda não publicada. Desconhecidos, redirecionados ou inválidos interrompem
a limpeza. A finalidade de acesso vence na data original; a remoção física segue
o ciclo em lotes e retentativas, sem promessa de apagar todos os bytes naquele segundo.

## Limites operacionais ainda abertos

- Recuperar controle, checkpoint confiável e anéis de chaves fora deste computador;
  simular perda completa do host e restauração simultânea do diretório independente.
  Chaves antigas não podem ser retiradas enquanto cópias, outro domínio ou arquivo
  de backup ainda dependerem delas. A prova isolada de rotação não autoriza apagá-las
  da operação real.
- Exercitar o pacote final com todas as categorias, volumes e falhas operacionais;
  os cenários novos delimitam perfil, arquivo e localização, não comprovam cada
  recurso de cada domínio nem substituem a recuperação assistida de dependências.
- Instalar conjuntamente API/worker/ferramentas compatíveis com o catálogo V2 e o
  schema atual (V39 com órfãos); a demonstração protegida permanece V26 e o frontend congelado segue
  preservado. Nenhum deploy foi realizado neste complemento.
- Fechar os demais requisitos de retenção financeira/logs, órfãos, minimização,
  consentimentos, consistência documental, red teams e operação pública.

Complemento posterior: `Arquivos_Orfaos.md` registra a implementação V39 para
bytes sem metadados e a revisão da coordenação de upload/worker. O mesmo complemento
corrige a limitação do snapshot de arquivos a PNG/JPG: WebP de foto e PDF de
documento enviados pelo `FileService` agora têm caso físico próprio. Isso não
reclassifica as rodadas históricas abaixo como provas desses formatos.

## Evidência disponível

- `privacy-hold-control-foundation-first-24oct.log`: 36 unitários aprovados,
  anteriores ao reforço de integridade que impede limpar versões antigas quando
  o arquivo atual não confere com seu hash.
- `privacy-hold-control-foundation-regression-24oct.log`: **36 unitários e 31
  integrações aprovados**, depois desse reforço. Abrange catálogo/cifra/controle,
  retenções HTTP, rotação, schema e restaurações físicas/fence existentes. Não
  atribuir replay de holds novos aos casos físicos anteriores, que não o executam.
- `privacy-hold-control-commit-first-24oct.log`: **36 unitários e 32 integrações
  aprovados**, incluindo seis cenários novos da ponte com commit/rollback reais.
  Guarda de inicialização e no-op foram acrescentados depois da compilação dessa
  rodada e exigem confirmação posterior.
- `privacy-hold-control-guard-final-24oct.log`: 36 unitários aprovados; 28
  integrações executadas com três falhas de contagens antigas na compatibilidade
  (37 em vez de 38 migrations; 24/26 em vez de 25/27 após baselines). Os seis
  cenários da ponte e cinco de fence passaram, incluindo os dois casos novos do
  aplicativo Spring/JPA completo: reinício do mesmo banco após commit interrompido
  e restauração de arquivo anterior quando o resultado da preparação não pode ser
  comprovado. Preservação física e schema também passaram. Não classificar essa
  rodada como BUILD SUCCESS.
- `privacy-hold-control-compat-confirmed-24oct.log`: **oito unitários e 13
  integrações aprovados**, com contagens
  ajustadas à V38, preservando comparações de todos os objetos legados e acrescentando
  a verificação do marcador novo inicial. Inclui o fechamento do stream de arquivos
  no caso de evidência incerta e os cinco casos atuais de inicialização/fence.
- `privacy-hold-control-no-downgrade-final-24oct.log`: **13 unitários e 26
  integrações aprovados**, incluindo sete casos da ponte (um para configuração
  removida depois de estabelecer controle), cinco de inicialização/fence e 14 de
  retenções HTTP existentes. Essa rodada sucede a correção de compatibilidade,
  sem mudar migrations já aplicadas.

Não somar rodadas sobrepostas. Minutas jurídicas não receberam promessa de
recuperação que ainda não foi implementada. **VALIDAÇÃO POR ADVOGADO BRASILEIRO
RECOMENDADA** para a operação, finalidade e duração dos casos de preservação.

Hashes e alcance das rodadas históricas, inclusive a falha/correção de contagem,
em `evidence/hold-control-24oct.json`. Esse checkpoint histórico precede o replay
tipado e não deve ser apresentado como evidência do código novo de 25/10.

## Provas do complemento de 25/10

- `privacy-held-file-blobs-first-25oct.log`: compilação recusada por declaração
  composta com `var` no teste novo; corrigida antes de executar os casos. Não é
  build aprovado.
- `privacy-held-file-blobs-second-25oct.log`: **45 unitários aprovados**, com os
  oito cenários de blobs, parser privado de controle e regressões de catálogo/cifra.
- `privacy-typed-control-first-25oct.log`: **17 unitários e 17 integrações**
  aprovados (ponte, rotação e inicialização/fence).
- `privacy-post-backup-holds-first-25oct.log`: **17 unitários e 22 integrações**
  aprovados; cinco casos novos usam pg_dump/pg_restore e transações Spring/JPA:
  criação/revisão/arquivo posterior e exclusão; liberação posterior; cópia corrompida;
  titular ausente; campo não reconhecido. Inclui também 14 HTTP e três restaurações
  físicas anteriores.
- `privacy-post-backup-holds-rotation-25oct.log`: **46 unitários e 25 integrações
  aprovados**, rodada com oito casos novos,
  acrescentando expiração original, rotação/retirada de chaves no conjunto isolado
  e conflito de arquivo existente. A fonte recebeu depois reforço de validação antes da
  limpeza e dois casos adicionais (HTTP real com controle e localização posterior).
- `privacy-post-backup-holds-final-25oct.log`: **46 unitários e 76 integrações
  aprovados**, zero falhas/erros/skips. Dez casos de `PrivacyPostBackupHoldsIT`,
  incluindo os dois adicionais e a recusa de limpar o controle anterior quando o
  esquema atual é inválido. Também passaram oito compatibilidades, cinco rotações,
  sete pontes de commit, 14 HTTP de retenção, três restores físicos anteriores,
  cinco guardas de inicialização, 18 direitos HTTP e seis invariantes de schema.
  Nenhuma migration legada nem asset de frontend foi alterado.

Hashes da fonte e alcance do complemento em `evidence/hold-control-25oct.json`.
A prévia sintética foi retomada em `http://localhost:4190`, prontidão HTTP200 e
provedor de pagamento `development`. Essa fixture de navegador tem controle
operacional independente desabilitado; é somente smoke da fonte. A prova de
replay vem dos testes de integração, que habilitam seu próprio diretório isolado.

Não somar rodadas sobrepostas nem atribuir ao runtime V26 resultados da fonte V38.
