# Recuperação transacional com base física e WAL cifrados

26/10/2026. Complemento de desenvolvimento ao schema V46, sem nova migration,
alteração de frontend, publicação jurídica ou configuração do cluster ativo.
Objetivo integral de 44 requisitos e 16 entregáveis permanece em andamento.

## Problema e decisão

O dump anterior ao embarque pode conter uma reserva confirmada e um PIN ainda
válido. Aplicar somente a expiração do PIN sobre esse dump não recupera o
embarque ou o cancelamento posterior. Da mesma forma, o diário de exclusões e o
controle de preservações não são um backup dos lançamentos financeiros.

O complemento usa recuperação nativa do PostgreSQL 18: base física verificada,
sequência de WAL e ponto de recuperação nomeado. Não reconstrói dinheiro,
embarque ou quitação a partir de um snapshot de privacidade. A distinção entre
dump lógico e base apta ao replay é expressa na documentação oficial de
[arquivamento contínuo](https://www.postgresql.org/docs/18/continuous-archiving.html).
Uma base abrange o cluster inteiro; não se deve capturar um cluster compartilhado
presumindo que somente o banco do UniCarona estará na cópia.

## Implementação na fonte

| Parte | Comportamento e limite |
| --- | --- |
| `PitrRecoveryBundle` | Confere o dataset vinculado ao catálogo e reserva recurso UUID antes da captura. Captura interrompida permanece pendente e impede expiração prematura de supressões |
| `PitrEncryptedArchive` | Base ZIP e objetos WAL cifrados com AES-256-GCM; assinatura do índice, propósito de chave separado e vínculo autenticado a recurso, dataset, cluster e nome do objeto |
| Índice formato 1 | Identidade técnica, criação, vencimento fixo, estado, alvo nomeado, LSN e WAL final; sem nomes/e-mails. Esses vínculos técnicos não tornam a base anônima |
| Captura WAL | Aceita somente arquivo ordinário do `pg_wal` configurado, confere identidade do cluster no cabeçalho dos segmentos e estabilidade durante leitura. Repetição com bytes iguais é idempotente; nome com bytes diferentes é recusado |
| Base | Exige `backup_label`, manifesto com identidade correspondente e ausência de PID ativo. ZIP é escrito diretamente na cifra; o ensaio executa `pg_verifybackup` antes de cifrar e depois de restaurar |
| Selagem | Autentica a base e o WAL final, congela alvo/LSN/WAL e recusa novas escritas. Não certifica sozinha todos os segmentos intermediários: isso exige recuperação nativa bem-sucedida |
| Restauração | Destino ausente, separado do catálogo; autenticação completa antes de extrair/publicar. Caminhos absolutos, travessia, links e arquivos especiais são recusados. WAL inválido não substitui o destino anterior |
| Memória | Decifragem em blocos de 64 KiB com provedor Bouncy Castle já dependente do projeto; `doFinal` autentica o objeto inteiro antes da publicação. Saída transitória fica privada, nunca é exposta como restauração concluída |
| Coordenação | Trava longa por recurso para I/O e trava curta compartilhada com catálogo para publicação; revalida índice e expiração ao publicar. Não segura a trava global durante todo o streaming |
| Expiração | Base, WAL e eventual staging de captura ficam no mesmo recurso finito do catálogo: padrão 35 dias, limite configurável de 90. Nenhum novo segmento ou reenvio prolonga esse prazo |
| Ponto conservador | O catálogo registra o início da base, e não o alvo de replay posterior. Dados antigos ainda contidos na base continuam exigindo instruções de supressão |

Limites de formato: 16 GiB de conteúdo por objeto cifrado, 128 GiB na extração,
um milhão de entradas. São limites de proteção, não capacidade operacional
certificada nesses volumes. A base física não aceita tablespaces externos ou
links no adaptador atual. Staging interrompido não desaparece por suposição;
permanece no recurso catalogado até remoção segura/expiração.

## Comando de arquivo

`com.unicarona.privacy.ops.PitrArchiveCommand` tem somente operações `seal` e
`fetch`, com três argumentos: ação, caminho fornecido pelo PostgreSQL e nome WAL.
Não inicializa Spring, datasource ou provedor financeiro. Recebe exclusivamente
configuração privada no ambiente do processo:

```text
UNICARONA_PITR_ROOT
UNICARONA_PITR_DATA_ROOT
UNICARONA_PITR_KEYS
```

O último campo é um anel JSON de IDs para chaves aleatórias de 32 bytes em Base64,
separado das chaves de assinatura do catálogo, do cofre, SMTP e Mercado Pago.
Nenhuma chave real foi criada, impressa ou instalada nesta etapa. As do ensaio
são sintéticas e transitórias. O comando não provisiona ACL nem aceita segredos
por argumentos. Configuração em diretório público ou volume sem proteção não é
um modo de operação permitido pelo procedimento.

O índice fixa uma chave por recurso; não há rotação AES interna desses objetos.
Conservar a chave necessária enquanto a cópia recuperável existir e criar novas
capturas com chave nova. A rotação do catálogo não recifra os WALs. Perda de chave
impede recuperação; teste e custódia independente continuam necessários.

## Procedimento ensaiado

1. Criar dois clusters novos, isolados e sintéticos, com portas aleatórias e
   marcadores de escopo; nunca usar os servidores existentes em 5432/55432.
2. Subir a aplicação de teste e cadastrar contratos pagos coerentes sob os
   guardas monetários. Vincular dataset ao catálogo independente.
3. Reservar recurso, configurar `archive_command` no cluster novo e capturar
   `pg_basebackup --wal-method=stream`. Verificar o manifesto, cifrar a base e
   remover somente o staging concluído pertencente ao ensaio.
4. Avançar o segmento WAL; executar embarque e cancelamento por serviços reais
   transacionais. Criar ponto nomeado, trocar WAL e aguardar seu arquivamento.
5. Selar recurso e finalizar catálogo com início da base. Encerrar a captura
   finita. Publicar preservação e exclusão mais recentes no controle independente.
6. Restaurar em outro diretório/porta. Verificar novamente a base; configurar
   `restore_command`, alvo nomeado, timeline capturada e promoção. Hot standby
   fica desligado: PID ativo não equivale a banco pronto.
7. Aguardar SQL disponível e `pg_is_in_recovery() = false`. Conferir fatos e
   contabilidade antes da aplicação. Só então iniciar API, que reaplica os
   controles independentes antes da prontidão.
8. Repetir com WAL final ausente e adulterado: recuperação deve terminar em
   falha, sem abrir API em um ponto anterior. Expirar o recurso somente depois
   de parar os clusters sintéticos; controles independentes ficam preservados.

`pg_verifybackup` verifica o manifesto, mas não substitui a recuperação e a
conferência dos dados, conforme sua
[documentação oficial](https://www.postgresql.org/docs/18/app-pgverifybackup.html).
O teste não considera somente o retorno do processo `pg_ctl` como sucesso.

## Evidência e rodadas

A execução `privacy-native-pitr-readiness-26oct.log` passou com **12 unitários e
um cenário nativo de integração**. Esse cenário contém a recuperação positiva,
controles posteriores ao alvo, recusa de WAL ausente/adulterado e expiração.
Não apresentar essas verificações internas como múltiplos testes JUnit.

O cenário comprovou: timestamp de embarque original, credencial consumida
ausente, reserva cancelada, crédito de 500 centavos, pagamentos/parcelas/diários/
lançamentos idênticos ao ponto esperado e conciliação zero. Antes de abrir a API,
o banco restaurado ainda tinha a conta ativa e nenhuma preservação posterior ao
alvo; depois do bootstrap, a conta estava excluída e a preservação reaplicada,
sem mudança dos valores. Não houve chamada a PSP, saque ou envio de e-mail.

A rodada inicial foi recusada pelo próprio helper porque o diretório criado pelo
JUnit não estava na raiz temporária privada configurada. A seguinte chegou à
recuperação, mas tentou consultar o banco antes de concluir o replay. O helper
passou a criar o diretório explicitamente na raiz privada e aguardar promoção e
SQL; essas rodadas com erro não são evidência de aprovação.

A versão final acrescentou decifragem em blocos, prova com heap limitado e
guardas de dataset. A rodada `privacy-native-pitr-streaming-final-26oct.log`
passou em **191 unitários e dois cenários de integração**, sem falhas, erros ou
skips: um cenário de recuperação nativa e outro de arquivo maior que o heap.
`PrivacyPitrMemoryIT` cifrou/restaurou 96 MiB em subprocessos limitados a 64 MiB,
comparou SHA-256 e confirmou que tag inválida conserva o destino anterior e
remove o temporário. Esse arquivo tem formato de cabeçalho sintético para testar
a fronteira criptográfica; não é prova adicional de replay PostgreSQL.

Tempos observados: cerca de 150 segundos para o cenário nativo e 22 segundos para
o de memória; a rodada inteira levou 3min43s. São dados deste ensaio, não RTO
contratual. Evidência com hashes em `evidence/native-pitr-26oct.json`.
Rodadas sobrepostas não devem ser somadas. A compilação incorporou apenas a
fonte de desenvolvimento; não instalou o jar novo na aplicação protegida.

## O que continua pendente

Este é um mecanismo de captura finita e um ensaio real delimitado. Ainda não é
um serviço de backup contínuo instalado na aplicação protegida. Permanecem:

- Orquestração de novas bases, rotação sem lacunas, métricas/alertas de atraso e
  garantia operacional de que o alvo exigido contém todos os eventos necessários.
- Cópia fora do computador, recuperação de chaves e controles independentes após
  perda do host, teste de desastre, dimensionamento e publicação coordenada.
- Integração operacional de prontidão com atraso/falha do arquivo, política de
  RPO/RTO e reconciliação com o provedor após recuperação. WAL assíncrono pode
  perder a cauda não arquivada; este ensaio não promete perda zero.
- Política para eventos de negócio posteriores ao alvo: não tratá-los como
  recuperados só porque o alvo anterior foi alcançado. O ensaio cria controles
  de privacidade posteriores ao alvo, não um backup de negócios além dele.
- Recuperação externa dos uploads/cofre/diário/catálogo, retenção dos próprios
  recebimentos/contratos realizados e agregação realmente anônima.
- Minimizacão restante, revisão final de jornadas/aceites, consistência jurídica,
  red teams, os 16 entregáveis e conferência visual adiada pelo usuário.

Nenhuma migration, serviço do computador, configuração privada ativa, jar V26,
asset congelado ou minuta legal foi alterado por este complemento. A fonte
continua com Privacidade r10 e Motorista r3 em `draft`.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Prazos de cópia e procedimentos
de preservação/restauração são escolhas operacionais a avaliar no contexto real;
não foram apresentados como prazos universais impostos pela LGPD.
