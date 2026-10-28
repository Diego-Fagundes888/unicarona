# Eventos operacionais de segurança — V42

26/10/2026. Implementação na fonte, sem publicação. Demonstração privada V26 e
frontend congelado preservados. Este complemento resolve uma família delimitada
de eventos; não encerra retenção financeira/contratual, todos os logs ou os 44 requisitos.

## Diagnóstico

`audit_logs` registrava ações com UUID do ator/recurso, data, tipo e metadados.
O trigger `audit_immutable` da V2 rejeitava qualquer alteração ou exclusão.
Isso protegia a integridade, mas também impedia expiração de eventos operacionais
de segurança. Remover tokens/sessões não removia esses registros. A exportação
continuava retornando eventos antigos; restaurar um backup podia reintroduzi-los.

A V42 conserva a proibição de editar o histórico. Permite somente excluir
eventos explicitamente classificados, com prazo original terminado e proteção
dos casos de preservação. Não desliga guardas do ledger, pagamento ou aceite.
A migration não expurga registros existentes: o aplicativo deve primeiro
conferir o controle independente de preservações e executar a recuperação.

## Regra, necessidade e limites jurídicos

| Família | Eventos efetivamente abrangidos | Prazo operacional proposto |
| --- | --- | --- |
| Confirmação de e-mail | `email_requested`, `email_verification_invalid`, `email_verification_replaced`, `email_verification_used`, `email_verification_expired`, `email_verified` | Seis meses de calendário desde `created_at` original |
| Credenciais e preferências | `password_reset`, `password_changed`, `settings_updated`, `email_change_cancelled` | Mesmo prazo, sem conservar senha ou valor anterior do perfil |
| Troca de e-mail | `email_change_requested`, `email_changed`, no contexto `identity_change` com ator/recurso identificados | Mesmo prazo; endereço/link continuam no fluxo próprio, não passam a ser copiados no log |
| Caso específico | Evento ainda disponível, identificado por UUID e vinculado ao titular | Cópia cifrada privada, fundamento, justificativa, revisão e vencimento próprios; acesso administrativo reautenticado/auditado |

O contexto também é conferido: eventos do tipo `user` exigem ator e recurso
compatíveis; o evento de verificação inválida sem pessoa identificada pode
expirar sem inventar um titular. A função retorna prazo nulo para ações/contextos
não classificados, preservando sua guarda até a política correspondente.
Cadastro/aceites, elegibilidade etária, recebimentos, contestações, decisões
comunitárias, operação administrativa e outros históricos **não** recebem um
prazo genérico de seis meses por este complemento.

O prazo é uma proposta de política operacional: atende à investigação de
comprometimento de conta e abuso recente, reduzindo guarda permanente de sinais
que perdem utilidade. A base precisa ser avaliada por finalidade, considerando
segurança, legítimo interesse e defesa em caso concreto; consentimento de
marketing não autoriza essa guarda. Necessidade, término do tratamento e
hipóteses de conservação devem ser examinados sob a
[LGPD, arts.6º,7º,10,15 e16](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
O [material da ANPD sobre legítimo interesse](https://www.gov.br/anpd/pt-br/centrais-de-conteudo/materiais-educativos-e-publicacoes/guia_orientativo_hipoteses_legais_tratamento_de_dados_pessoais_legitimo_interesse)
integra as referências para validar necessidade e salvaguardas; não impõe esse
prazo específico ao UniCarona. Revisar sua adequação na operação e com profissional.

Esses eventos não equivalem, por si, aos registros de acesso definidos no
[Marco Civil, arts.5º e15](https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2014/lei/l12965.htm).
O art.15 trata de obrigação com condições de aplicação próprias, incluindo
atividade organizada/profissional com fins econômicos por pessoa jurídica.
O responsável informado hoje é pessoa física. Este complemento não declara
dispensa geral, cumprimento integral dessa obrigação ou suficiência desses
eventos como registros legais de acesso. Não introduz novo log de IP.
**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Funcionamento técnico

`privacy_security_audit_deadline` calcula o prazo por data original e fuso fixo
`America/Sao_Paulo`. Seis meses não são convertidos em 180 dias; fim de mês é
considerado. Atualizar data, ator, ação ou metadados continua proibido. A nova
guarda de exclusão aceita somente evento elegível e não permite remover o
original quando existe preservação ativa sem a cópia privada necessária.

O índice por criação ajuda a seleção. Um limite amplo anterior ao vencimento
serve só à busca indexada: a decisão final sempre usa a função dos seis meses.
`PrivacySecurityAuditRetention` confere checkpoint independente, trava do titular
quando conhecido, elegibilidade e cópia do caso. A cópia precisa autenticar e
conter o evento/titular esperados. Falha de autenticação interrompe a prontidão;
não se apaga o original nem se segue como se a preservação estivesse íntegra.
O registro do expurgo contém tipo/política/contagem, sem repetir UUID do evento,
titular, conteúdo ou identificador de caso.

`PrivacySecurityAuditMaintenance` processa lotes com cursor, respeita interrupção
e não deixa um titular ocupado impedir o avanço sobre outro. No startup,
`PrivacyRestoreGuard` aplica o expurgo elegível depois de recuperar controles e
antes de abrir API/worker. A data do backup ou da execução não vira novo prazo.
Exclusão de linha não significa sobrescrever instantaneamente páginas, WAL ou
cópias de backup: o ciclo de backups e o controle externo continuam necessários.

## Preservação, exportação e interface

A categoria `security_audit`/`audit_log` exige recurso específico e pertence ao
ator daquele evento. Não permite preservar um evento já vencido que apenas
ainda aguarda limpeza. Uma cópia já legitimamente preservada e com caso ativo
segue as revisões justificadas existentes. Preservar não modifica o evento.

Ao abrir o caso, é criada cópia cifrada privada vinculada ao titular/registro.
O snapshot tipado a leva ao controle independente. Um caso ativo dessa categoria
sem a cópia não pode substituir o head independente válido. Recuperação restaura
decisão e evidência privada; não precisa recriar o evento no histórico comum.
O leitor também recusa um formato numérico grande que truncaria para1 em uma
conversão permissiva, sem tratá-lo como snapshot legado.

Eventos vencidos deixam a exportação comum antes do worker. Eventos atuais
incluem o próprio UUID no ZIP autenticado, permitindo informar qual recurso
deve ser investigado sem fornecer credenciais. Cópias próprias de caso ativo
têm seção `retainedSecurityEvents`, com ID, ação, tipo/recurso e data. A entrega
automática não inclui metadados internos, request ID ou identificação redundante
do ator. Pedidos complementares seguem o protocolo de privacidade. A liberação
ou expiração do caso encerra o acesso específico e permite limpar a cópia.

O select administrativo existente recebe “Evento de segurança” e ajuda junto
ao identificador. A lista própria de preservações usa o mesmo nome. Não foram
criados página, modal, layout, CSS, fonte, asset ou métrica de produto novos.
O servidor continua conferindo administrador ativo, senha, sessão, titular e
recurso; o texto da interface não concede autorização.

## Evidência obtida

- Primeira fonte:37 unitários e 37 integrações aprovados, incluindo11 migrações,
  11casos novos de segurança e 15 jurídicos.
- Regressão ampla:46 unitários e 164 integrações aprovados, incluindo12 migrações,
  65HTTP do provedor,18autenticação,15verificação de e-mail,13 correção,
  15jurídicos,14 preservações HTTP e12casos novos de segurança.
- Rodada seguinte:46 unitários e 72 integrações aprovados após incluir ID próprio
  no export, interrupção da prontidão em cópia inválida e lote de 1.201 eventos:
  12 migrações,13 segurança,18 direitos,14 preservações HTTP e 15 jurídicos.
- Confirmação da fonte final após reforçar a cópia obrigatória no controle e o
  formato numérico estrito:46 unitários e 47 integrações aprovados, sem falhas,
  erros ou skips. Inclui14 cenários de segurança, sete de commit/rollback do
  controle,11 preservações posteriores ao backup e 15 jurídicos.
- 49 JS aprovados, sem falhas ou skips. Detector único nos dois JS finais: `[]`.

As rodadas se sobrepõem. Todos os dados são sintéticos, somente no banco
`unicarona_java_test` em loopback55432. Provedor dos testes HTTP é local; não há
cobrança, conta ou envio de e-mail real. Os testes fazem pg_dump/pg_restore real,
com caso posterior ao backup e sem repovoar histórico comum.
Fontes, resultados e hashes constam no
[registro de evidência desta etapa](evidence/security-audit-retention-26oct.json).

QA renderizado do select/ajuda/lista e leitor jurídico r6 permanece pendente da
liberação de acesso local já solicitada. Capturas anteriores não comprovam este
complemento. Ver a [revisão delimitada](../../.impeccable/review/privacy/security-audit-retention-review.md).

## Publicação e continuação do objetivo

Privacidade r6 continua `draft`; fonte r5 preservada em `legal/history/privacy-r5.md`.
Motorista r3 e demais documentos conservam suas revisões. API e worker precisam
ser publicados com migration/leitores compatíveis e recuperação conferida; um
leitor antigo não reconhece a nova categoria do controle. Não misturar versões
ou fabricar aceite retroativo. Não houve publicação das minutas.

Continuam abertos os prazos e expurgo das demais famílias de auditoria, provas
financeiras/contratuais/elegibilidade, logs técnicos e controles fora deste
computador; anonimização real; minimização restante; revisão cruzada das seis
minutas, jornadas/aceites, red teams, mapa dos 12 casos e 16 entregáveis. Os1.201 eventos
testam este lote, não todos os volumes/categorias ou a capacidade de produção.
