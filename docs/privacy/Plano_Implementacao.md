# Plano e cobertura — camada de privacidade

Objetivo original: `<LOCAL_USER_HOME>/.codex/attachments/eb7f7926-8a7d-4faa-b52f-9334090cf1cb/goal-objective.md`, 44 requisitos e 16 entregáveis finais. **Em andamento; não é relatório de conclusão.**

Complemento de minimização V46: [perfil acadêmico entre participantes](Dados_Academicos_Entre_Participantes.md).
Busca/detalhe de avulsa e mensal não entregam curso/período/turma exatos de outra
pessoa, mesmo com aceite ou reserva paga; titular mantém os próprios dados.
Compatibilidade e elegibilidade mensal permanecem no servidor. Interface distingue
proteção de informação ausente; Privacidade r11 em minuta, r10 preservada. Sem
migration, alteração da demo ou mudança de DS; QA visual segue adiado.
Fonte final: **191 unitários, 189 integrações e 50 JS aprovados**, detector único
`[]`. Quatro novos cenários HTTP e reforço do aceite mensal, com regressão de
caronas, mensais, pagamento, elegibilidade, direitos e documentos. Rodada inicial
com erro de fixture preservada; não somar resultados sobrepostos.

Complemento V46: [recuperação transacional por base física e WAL cifrados](Recuperacao_Transacional_PITR.md).
Ensaio nativo isolado recuperou embarque, retirada do PIN, cancelamento e crédito
posteriores à base, preservando os lançamentos exatos, e reaplicou controles de
privacidade posteriores ao alvo. Falta de WAL/adulteração impediram promoção;
recurso finito segue o catálogo. Não equivale a backup contínuo/offsite instalado,
RPO zero, recuperação de todos os eventos além do alvo ou conclusão financeira.
Versão final com leitura em blocos e teste de heap possui evidência própria.
Rodada final: **191 unitários e duas integrações aprovados**, sem falhas/erros/
skips; recuperação nativa e arquivo de 96 MiB com heap de 64 MiB. São cenários
delimitados, não uma regressão completa de todas as integrações do projeto.

V46: [ciclo de vida do PIN de embarque](PIN_Embarque_Retencao.md).
Remove credencial/verificador quando a finalidade termina, congela o prazo
original, impede reapresentação ordinária após o uso e preserva prova e valores.
Leitura/embarque recusam vencidos antes do worker; recuperação limpa vencidos
antes da prontidão. Não recupera evento perdido após backup anterior ao embarque:
recuperação financeira e dos fatos da viagem continua sendo condição de publicação.
Privacidade r10 em minuta, r9 preservada; inspeção visual segue adiada.
V46: rodada dirigida final com **176 unitários/132 integrações aprovados**,
incluindo 15 cenários próprios, 16 de migração e 38 de mensal. A verificação
complementar por classe soma 176/291 casos distintos aprovados entre a rodada
ampla e a dirigida; não é uma única execução verde de 291 integrações. Falhas e
correções permanecem na evidência. Fonte de produção permaneceu igual entre
essas duas rodadas; somente a fixture/expectativa antiga e a prova de expiração
foram ajustadas nos testes.

Próxima prioridade operacional de recuperação: orquestração contínua de bases e
WAL sem lacunas, monitoramento/alertas e política verificável de RPO/RTO, cópia
fora do computador, custódia/recuperação de chaves/controles e integração de
prontidão. O ensaio finito não cobre essas partes. Continuar minimização,
retenções restantes, consistência/red teams e entregáveis; não ampliar descarte
de recebidos como substituto da recuperação nem alterar o cluster privado ativo.

V45: [transferências definitivamente falhas](Transferencias_Falhas_Retencao.md).
Expurgo conjunto de tentativas antigas de efeito zero por conta, protocolo de
desenvolvimento reconhecido, com preservação de saldos, disputas e provas
dependentes. Transferências concluídas, recebimentos e devoluções reais Mercado
Pago permanecem fora desse caminho. Privacidade r9 em minuta, r8 preservada.
Migração sem exclusão em massa; manutenção e recuperação fazem a seleção segura.
Inspeção visual continua adiada pelo usuário. Evidência final vinculada ao relatório.

V45 fonte final: **176 unitários/232 testes de integração aprovados**, incluindo
21 cenários próprios e 15 de migração. A regressão anterior teve 176/215 e a prova
inicial 5/13; as rodadas são sobrepostas. Isso valida este ciclo limitado, sem
concluir a retenção financeira completa nem autorizar publicação.

V44: [checkouts nunca enviados e cotações abandonadas](Checkouts_Nao_Enviados_Retencao.md).
Expurgo físico coordenado de pedidos/pagamentos locais sem recebimento, reserva
ou envio, após 90 dias e conferência de casos/cache/testemunhos. Recebimentos,
contratos cumpridos e ledger continuam na frente de retenção financeira. Não
declarar a frente inteira encerrada. Privacidade r8 em minuta, r7 preservada.
Inspeção visual adiada conforme instrução do usuário.

V44 verificada em banco de teste: regressão ampla **50 unitários/272 integrações**;
prova de escopo após reforços **50/149**; fonte final de referências/cache com
**50/71**, incluindo 24 cenários próprios, todos aprovados. Rodadas sobrepostas;
falhas de preparação de testes também registradas na evidência, sem somar os
resultados. Não é conclusão da retenção de recebidos, ledger ou dos 44 requisitos.

Checkpoint V43 de 26/10: [identificação mínima nas obrigações financeiras](Identificacao_Obrigacoes_Financeiras.md).
A cópia contratual acompanha obrigação concreta e fatos relevantes, sem renovação
por polling/restore nem perda por carteira antiga restaurada. Snapshot 3 conserva
finalidade observada e lê legados genuínos 1/2; não é backup do ledger. Privacidade
r7 permanece em minuta, com r6 preservada. Testes/evidência vinculados ao relatório.
Retenção dos próprios registros financeiros/contratuais, agregação anônima,
minimização restante, recuperação externa, consistência/red teams e entrega
integral continuam abertos. Inspeção visual adiada conforme instrução do usuário.

[Consistência das seis minutas](Consistencia_Documentos.md) registra o confronto
por tema e seus limites. Corrigida a descrição da própria matriz de requisitos:
operações em curso adiam processamento; saldo e disputa são tratados de forma
separada, sem exigir exposição pública do perfil. Não é conclusão jurídica global.

V43: regressão ampla corrigida com **50 unitários e 240 integrações** aprovados.
Após reforçar as travas de recuperação, fonte final com **50 unitários e 63
integrações** aprovados, incluindo 17 cenários de finalidade financeira/protocolo.
Rodadas sobrepostas; falhas reproduzidas e correções registradas no relatório.
Nenhuma cobrança real, publicação ou alteração da demonstração protegida.

Responsável confirmado: Diego Alves Fagundes, pessoa física, Novo Gama/GO, contato `privacy@example.invalid`. Não inventar CNPJ, vínculo institucional, seguradora, licença de transporte, serviço de hospedagem ou validação profissional.

## Ordem de execução e evidências de conclusão

| Frente | Requisitos | Estado inicial / evidência necessária para concluir |
| --- | --- | --- |
| Inventário e fontes | 1–4, 27–30, 32–34 | Inventário inicial em `Inventario_Dados.md`; confrontar inventário final com schema/código após mudanças e publicar tabela de bases/finalidades/prazos com fontes oficiais |
| Regras e textos jurídicos | 5–9, 18, 29, 37–39, 44 | Redigir após auditoria, com papéis reais, cláusulas alternativas e escolhas justificadas; versões públicas de termos, privacidade e anexos sem duplicação; matriz de consistência com backend e ressalvas jurídicas explícitas |
| Documentos e aceites | 21–23, 31 | V30, catálogo/arquivo público, publicação controlada, seis minutas com hash, recibos no cadastro e reaceite testados; exigência para novas operações no servidor. Leitor, cadastro e reaceite integrados no código do cliente e testados; faltam inspeção real, fechamento Impeccable e publicação coordenada; nenhum aceite retroativo |
| Direitos e centro de privacidade | 24–26, 35–36 | Backend testado de direitos próprios e atendimento cifrado; UI própria de protocolos, preferências, exportação, correções e retenções integrada em desenvolvimento, com testes JS/HTTP. Faltam UI administrativa, inspeção real, publicação e conclusão das demais frentes |
| Encerramento e estados | 10–14, 17–20 | Preview, senha, reversão, remoção, avisos essenciais e separação financeira testados; V31 diferencia desativação, encerramento e sanções. UI própria implementada e testada em desenvolvimento; faltam controles administrativos, revisão visual e publicação. Reverter sanção não desfaz desativação voluntária; cancelar exclusão não remove suspensão |
| Retenção, supressão e arquivos | 15–16, 33–34, 40 | Cofre, expiração operacional, diário/catálogo, rotação e retenções testados. V37 acrescenta remoção de uploads sem vínculo e supressão individual por arquivo, inclusive após dump/restore. Faltam arquivos/documentos e metadados remanescentes, financeiro/logs, estatística anônima, administração visual, recuperação externa/retencões posteriores ao snapshot e publicação coordenada |
| Minimização entre usuários | 27–30, 40 | Telefone, foto atual, placa completa, nome reduzido e encontro/nota seguem contexto/estado/bloqueio; aceite válido permite conhecer o encontro antes de pagar. V37 prepara arquivos sem vínculo para expurgo com diário independente. Faltam identificadores/campos acadêmicos, endereços/coordenadas, snapshots/observações, retenções remanescentes e integração visual; mapa opcional sem rastreamento GPS inventado |
| Segurança e red teams | 33, 39–41, 43–44 | Testes contra IDOR, sessão roubada, acesso posterior, retorno de banido, concorrência, financeiro, termos antigos e retenção; repetir autenticação/e-mail/pagamentos/caronas/mensais conforme mudanças |
| Interface, revisão e entrega | 24, 35–36, 42, entregáveis finais | Usar sistema existente, capturas válidas desktop/celular, revisão Impeccable independente e documentação; relatório dos 44 requisitos com prova por item e limites/pendências externas |

## Decisões de arquitetura a preservar

- Integrar Java/Spring, PostgreSQL, sessões/cookies e worker/outbox existentes. Nenhuma arquitetura de autenticação paralela.
- Não apagar ledger nem obrigações para satisfazer um teste de exclusão. Conta visível, acesso, perfil e evidência financeira são camadas diferentes.
- Não chamar UUID persistente, hash com chave ou cofre reidentificável de anonimização. Identificar exatamente o que foi apagado e o que ficou pseudonimizado sob retenção.
- Documentos publicados imutáveis; nenhuma alteração silenciosa de versões ou criação de consentimento para pessoas antigas.
- Sete dias é uma janela de segurança explicada ao usuário, não regra legal. Dados excessivos podem ser objeto de pedido específico sem aguardar a janela.
- Retenção e identificação comercial precisam refletir o enquadramento real. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA**, especialmente transporte/remuneração, CDC/reembolsos, prazos de defesa e transferências internacionais.
- Não publicar políticas como se todos os controles planejados já estivessem concluídos. O relatório final deve distinguir implementação, teste, operação externa e validação profissional.

## Controle desta etapa

A V27 foi a fundação aditiva; V28 e V29 acrescentaram estados e processamento de exclusão; V30 acrescentou metadados de publicação, avisos e integridade dos recibos; V31 acrescentou controles de conta e decisões; V32 acrescentou correções sensíveis. Essas versões foram exercitadas no banco descartável de testes e ainda não foram aplicadas ao banco de demonstração. O catálogo e os aceites possuem APIs testadas; as minutas continuam deliberadamente em estado `draft`, sem publicação operacional ou interface concluída. O objetivo permanece ativo até a auditoria final completa.

### Progresso verificado em 16/10, 11:25

- Inventário, diagnóstico e matriz de alternativas em `Inventario_Dados.md` e `Decisoes_Juridicas.md`.
- V27: 12 tabelas aditivas, documentos com versões imutáveis, referências de aceite, protocolos cifrados, consentimentos, encerramento, holds e restrição finita. Nenhum aceite preenchido retroativamente. Schema foi exercitado apenas em `unicarona_java_test`.
- `PrivacySettings`: identidade do controlador e parâmetros limitados. Informações públicas confirmadas salvas na configuração privada; sem CPF/CNPJ/endereço inventado. Parâmetros ainda não significam execução dos jobs de retenção.
- Endpoints novos: `GET/PATCH /api/v1/privacy/preferences`; `POST/GET /api/v1/privacy/requests`; `GET /api/v1/privacy/requests/{id}`. Contratos estritos, titular derivado da sessão, CSRF, rate limit, idempotência e conteúdo dos pedidos cifrado. A suspensão não suprime esses direitos.
- Marketing inicia desligado, exige versão da manifestação para ativar e pode ser revogado sem aceitar outra manifestação. Não foi criado um serviço de campanha ou envio promocional; as mensagens essenciais existentes permanecem independentes.
- `.local/privacy-rights-verified.log`: **58 testes aprovados** (18 unitários + 40 integração) de configuração, contratos, autenticação, e-mail, schema e direitos. `.local/privacy-foundations-final.log` também comprova os sete casos de compatibilidade/migração Flyway, preservando objetos e linhas anteriores. Não somar repetições como testes distintos.
- O runtime de demonstração continua no artefato anterior/V26 com Gmail funcional; novos fluxos ainda não foram publicados nele. Não declarar a nova camada pronta ou a exclusão implementada.

### Próximas entregas obrigatórias

1. Integrar os seis documentos e os aceites à interface de cadastro/motorista e ao reaceite de mudanças materiais; confrontar minutas com a implementação final e publicar de forma coordenada. APIs, hash, arquivo imutável, exigência no servidor e vínculo da confirmação de encerramento ao conteúdo/prazo já foram implementados/testados, sem consentimento retroativo.
2. Centro de privacidade/UI e atendimento administrativo integrado; integrar os controles de conta/recurso/recadastro e correções sensíveis já implementados no servidor; concluir confirmação final e operação de valores após encerramento.
3. Concluir retenção de metadados financeiros/logs, anonimização estatística e gestão de holds; testar dump/restore físico, rotação de backups e cópia protegida do diário; minimizar contato/foto históricos, nomes, localização/snapshots e caches. Mapa externo deve ser opcional, sem inventar rastreamento GPS.

   **Achado operacional concreto:** o volume local D: é exFAT. A tentativa de restringir `.env` e `.local` por ACL não persistiu as permissões. `protect-private-files-local.ps1` agora detecta isso e recusa uma declaração de aplicação bem-sucedida. É necessário concluir uma configuração/migração segura de segredos, banco, uploads, diário e evidências para armazenamento com controle de acesso efetivo (por exemplo NTFS no computador atual), preservando verificação e recuperação. A API de demonstração continuou respondendo. Não chamar a pasta local de protegida por ACL nem considerar esse ponto resolvido; não apagar dados do PostgreSQL durante a migração. Essa pendência não impede o trabalho independente no catálogo/aceites.
4. Concluir a matriz das demais regras de cancelamento/reembolso e sua informação contratual. A destinação automática de “sem aula” já foi revista e testada, conforme o progresso de 13:21 abaixo.
5. Acessibilidade, desktop/celular, revisão independente Impeccable no escopo final, red teams e matriz requisito → implementação → teste/evidência dos 44 itens e dos 16 entregáveis.

### Progresso verificado em 16/10, 13:14

- Exportação com senha novamente, limite de tentativas, ZIP/JSON e arquivos próprios diretamente no stream, sem URL pública ou arquivo de exportação permanente. Contratos fechados e testes de exclusão de dados de terceiros. A conclusão assíncrona da resposta preserva a autorização feita no POST inicial.
- Atendimento administrativo de protocolos, leitura auditada, respostas cifradas e proteção contra decisão concorrente/repetida. Concluir um protocolo exige declarar a providência realizada; responder não executa automaticamente correção ou exclusão.
- V28: estado reversível `closing`; V29: processamento `erasing`, fila de tarefas, eliminação física confirmada, retentativa com espera, nascimento nulo permitido somente para conta excluída e metadados de evidência retida. Nenhuma migração nova aplicada ao runtime de demonstração.
- Pedido de exclusão com senha/CSRF/idempotência, janela de sete dias, revogação de outras sessões, suspensão preservada, pausa de oferta e interrupção de renovação automática. Cancelamento não reativa essas escolhas.
- Preview distingue viagens, solicitações, cobranças/comandos incertos, devoluções, regularização, disputas e saldos. Viagem já paga continua operável em `closing`; cobrança pendente e viagem ativa impedem o processamento prematuro. O ledger permanece intacto.
- Perfil removido, rotas operacionais minimizadas e credenciais eliminadas. Identidade e veículo de contrato pago são retidos cifrados com prazo; rota paga tem prazo menor, com exceções específicas justificadas. UUID/histórico continuam identificados como pseudonimizados, não como dados anônimos.
- O diário HMAC independente é escrito antes da remoção do perfil. Na inicialização, a API/readiness e o worker aguardam sua verificação/reaplicação. Teste restaura perfil, sessão e arquivo antigos no banco descartável; a supressão é reaplicada duas vezes, sem afetar outra conta. Também detecta alteração do diário. Isso ainda não substitui teste físico de dump/restore.
- Teste financeiro adicional do protocolo Mercado Pago: contestação assinada após exclusão é aceita/conciliada duas vezes sem duplicar recebimento, reativar acesso ou perder crédito/saldo. Trata-se de upstream simulado local, sem nova cobrança real. Corrigida tipagem do parâmetro de retentativa de pagamento de dívida identificada nos logs locais.
- Retenção em lotes: cofre vencido respeita holds; consentimento vigente é preservado; revogação conserva prova e não ativa campanhas; destinatário/conteúdo de e-mail concluído é removido; expiram tokens/sessões antigos, notificações e restrições vencidas. Metadados financeiros e demais logs ainda exigem conclusão própria.
- `.local/privacy-retention-final.log`: **73 testes aprovados** — 29 unitários e 44 de integração. Integração: 16 casos de privacidade, 15 de verificação/e-mail, sete de compatibilidade Flyway, quatro de caronas/encerramento e dois de Mercado Pago/regularização. A consulta do teste de e-mail passou a usar o vínculo técnico do job, porque o destinatário agora é apagado após processamento.
- Na rodada mais ampla, também passaram autenticação (17), arquivos (oito), perfil (14), contas administrativas (14) e fundação HTTP (sete). Os logs mantêm a sequência real de correções; não somar repetições como testes novos.
- `.impeccable/review/privacy/security-audit.json`: varredura de 611 arquivos de código, modelos de configuração, documentação, scripts e logs, sem ocorrência dos segredos configurados. Atualizar a varredura após as próximas mudanças; não equivale a auditoria completa de segurança.
- PostgreSQL local foi reiniciado após interrupção; a demonstração voltou a responder com o artefato imutável anterior/V26. As novidades permanecem em validação isolada. `Fluxo_Exclusao_Backend.md` descreve o comportamento e as lacunas atuais.

A etapa seguinte é o catálogo/aceites e sua integração com o produto. A camada completa ainda não foi entregue nem declarada pronta para produção.

### Complemento verificado em 16/10, 13:21

- As impressões de idempotência dos protocolos/respostas usam HMAC do texto confidencial, evitando uma impressão simples que permitiria testar palpites sobre mensagens curtas após vazamento do banco. Repetição e controle administrativo foram novamente aprovados em `.local/privacy-fingerprint-hardening.log` (três unitários e dois casos de integração, sem somar como casos novos).
- Novas confirmações e timeouts de `no_class` agora geram crédito integral ao passageiro, sem receita do motorista/plataforma; a origem contábil permanece elegível à devolução. Parcelas já bloqueadas continuam em análise. O histórico antigo e seu replay permanecem imutáveis.
- `.local/privacy-no-class-policy.log`: **46 casos de integração aprovados** (26 do ciclo de caronas e 20 da manutenção), além de três unitários de configuração. Há casos repetidos das rodadas anteriores; o número não é um total acumulado de testes distintos.
- `Sem_Aula_Regra_Revisada.md` registra a justificativa, a diferença histórica, a operação de devolução e o limite da evidência. A consulta somente de leitura ao sandbox nativo encontrou zero parcelas antigas `no_class` retidas e ledger equilibrado. Nenhuma compensação histórica foi necessária nessa base.
- A demonstração continua V26; a regra revisada e a camada de privacidade aguardam publicação coordenada após conclusão dos documentos, aceites e interface.

### Complemento verificado em 16/10, 14:15

- Identidade configurada a partir da confirmação do titular: Diego Alves Fagundes, pessoa física, Novo Gama/GO e contato privacy@example.invalid Não foram inventados CPF, CNPJ ou endereço. O envio Gmail existente continua independente do canal público de privacidade.
- Seis minutas específicas cobrem os treze temas sem duplicar políticas. Catálogo, arquivo imutável, hash canônico, revisão explícita, recibos íntegros, histórico do titular e avisos deduplicados implementados. V30 acrescenta metadados/avisos e controles de integridade. Estado continua `draft`, sem publicação na demonstração.
- Cadastro exige leitura/aceite e as cinco referências atuais. Usuário, recibos, perfil e confirmação são atômicos; repetição para conta existente não preenche histórico retroativo nem altera marketing. `.local/privacy-legal-registration-verified.log`: 18 unitários + 42 integrações aprovados.
- Exigência de aceites ligada às novas ofertas/edições, rotinas/reabertura, candidatura de motorista, solicitações e preparação de renovação. Termos específicos do motorista são distintos; a indisponibilidade do outro participante não expõe o histórico dele. Cancelamentos/contratos antigos não recebem nova exigência por atualização posterior.
- Confirmação de exclusão vincula conteúdo publicado e janela efetiva; pedido já recebido congela regra/prazo. Repetição idempotente de aceite ou encerramento conserva resultado histórico após atualização, sem aceitar o documento novo.
- `.local/privacy-legal-service-verified.log`: 15 unitários + 71 integrações aprovados. `.local/privacy-legal-financial-regression.log`: três unitários + 162 integrações aprovados, incluindo 61 casos de Mercado Pago com upstream local, contratos, pagamentos, arquivos, administração, ciclo de caronas e renovação sem aceite. `.local/privacy-legal-replay-final.log`: 15 unitários + 29 integrações aprovados após o ajuste final de repetição. As rodadas contêm casos repetidos; não somar como um total de casos únicos.
- Consulta somente de leitura à demonstração às 14:12 confirmou V26, ledger equilibrado e zero parcelas antigas `no_class` retidas. Os novos controles não foram implantados nela. `Documentos_Aceites_Backend.md` descreve limites e comportamento por ação.
- Continuam obrigatórios: interface/aceites visíveis, demais estados de conta e recadastro, correções sensíveis, confirmações finais, retenção financeira/logs/estatística, gestão de holds, minimização, migração do armazenamento privado exFAT para volume com permissões efetivas, backup/restore físico e revisão final. Esta etapa não conclui o objetivo.

### Complemento verificado em 17/10

- V31 implementa desativação voluntária, banimento/encerramento da plataforma, motivo público e nota interna cifrada, revisão auditada e identificador HMAC de recadastro com prazo escolhido para o caso, entre um e o máximo configurado, limitado a 365 dias. Exclusão não apaga prematuramente a restrição; vencimento/liberação encerra seu uso. Não se afirma detectar a mesma pessoa com outro e-mail.
- Desativação preserva viagens pagas, valores e acesso limitado; reativação exige senha e não religa renovações, ofertas ou marketing. Uma sanção superveniente conserva a escolha voluntária: ao retirar a restrição, a conta volta a desativada quando aplicável; só o titular reativa. Encerramento da plataforma conserva o prazo anterior de pedido existente e tem revisão administrativa distinta da reversão unilateral do titular.
- `Estados_Conta_Backend.md` registra fluxos, APIs, bases de retenção propostas, efeitos financeiros e limites. O inventário acrescenta os dados efetivamente coletados pelas V27–V31, separando diagnóstico inicial de implementação posterior. `safety.md` continua minuta e foi alinhado ao funcionamento.
- `.local/privacy-account-states-preservation.log`: dez integrações de controles de conta, 14 administrativas e 16 de direitos passaram; três unitários também passaram. A única falha era a comparação do schema antigo, ainda não ajustada para a coluna aditiva `voluntarily_deactivated_at`. Não registrar essa rodada como `BUILD SUCCESS`.
- `.local/privacy-account-compatibility-final.log`: três unitários + sete integrações Flyway aprovados após a correção da comparação. As definições antigas continuam comparadas; a coluna nova tem verificação própria de tipo/nulabilidade/default e os estados novos são explicitamente conferidos. Rodadas anteriores também comprovaram 28 casos de ciclo de caronas (dois novos de desativação) e autenticação/e-mail/contestação após exclusão. Não somar casos repetidos como um total único.
- O ambiente reiniciado estava sem o PostgreSQL de desenvolvimento. Recuperação automática do cluster foi concluída usando explicitamente `127.0.0.1:55432`, mantendo separado o serviço Windows em 5432. `backend-java/scripts/postgres-local.ps1 -Mode start` retoma apenas o cluster existente, sem inicializar/apagar banco; `-Mode status` verifica cluster/porta/readiness. Ambos foram exercitados, sem duplicar o servidor.
- API e worker de demonstração foram recuperados com o artefato imutável V26 conhecido. `/health/ready` respondeu `ready`; a consulta somente de leitura confirmou schema 26, ledger equilibrado e zero parcelas antigas `no_class` retidas. Isso não publica nem comprova operação da nova camada V31.
- Varredura de 657 arquivos não encontrou valores dos segredos configurados no escopo examinado (`.impeccable/review/privacy/security-audit.json`). Não comprova segurança integral ou permissões efetivas do disco exFAT; a migração do armazenamento privado continua necessária.
- Próximas partes do objetivo: correções sensíveis e confirmações finais; gestão de retenções, expiração financeira/logs e estatística anônima; minimização/localização; armazenamento protegido, backup/restore e chaves; interface completa com revisão independente e relatório final dos 44 requisitos. Não há bloqueio que justifique encerrar ou reduzir o objetivo.

### Correções sensíveis verificadas em 17/10, 11:40

- V32: pedido de troca de e-mail com destino cifrado, token HMAC e validade de 30 minutos. Requer senha/sessão próprias, prova do novo endereço e nova verificação de disponibilidade/restrições antes de efetivar. O login atual permanece até concluir; depois, sessões/tokens anteriores são encerrados e avisos preparados para os dois endereços. Endereço já utilizado/restrito tem resposta genérica, sem confirmação indevida. Troca não reativa conta ou marketing.
- Senha alterada, link substituído/cancelado/expirado, conta errada e restrição de serviço impedem confirmação. Durante suspensão/banimento/encerramento, correção de e-mail segue protocolo assistido. Entrega revalida o pedido sem manter bloqueio de banco durante SMTP/HTTP; destino, link e conteúdo congelado ficam cifrados e a carga é limpa após processamento.
- Nascimento pode ser corrigido com senha sem gravar a data completa em auditoria/idempotência. Se indicar menoridade, o dado correto é salvo em conta restrita; cadastro de menores continua vedado. Valores pagos ficam preservados para análise, sem acusação automática de fraude. Revisão administrativa não restaura o serviço enquanto a idade for incompatível. Corrigir novamente para adulto não remove uma sanção automaticamente.
- Corte de idade alinhado entre aplicação/banco/revisão em `America/Sao_Paulo`, corrigindo avanço indevido do aniversário de 1º de março em 29 de fevereiro. Nascer em 29 de fevereiro e o limite do aniversário têm testes próprios; uma constraint impede restaurar menor de idade a `active`/`deactivated` para novas combinações.
- Exportação inclui trocas próprias. Destino expira ao concluir/cancelar ou na limpeza após expiração; metadados expiram em 30 dias, salvo retenção específica. Remoção de perfil apaga pedidos/envios; retenção específica conserva evidência cifrada sem impressão de credencial ou identificador de token.
- `.local/privacy-identity-http-first.log`: 26 unitários + 74 integrações aprovados (autenticação, e-mail, administração, catálogo HTTP, migrações e dez correções). `.local/privacy-identity-final.log`: 26 unitários + 47 integrações aprovados (12 correções, direitos, controles de conta, migrações e dois casos financeiros de correção de idade). `.local/privacy-identity-retention-final.log`: 26 unitários + 34 integrações aprovados (13 correções, direitos e cinco invariantes de schema). Não somar repetições como casos únicos.
- Novas variantes de e-mail mantêm o layout existente e têm testes de conteúdo/link e transporte SMTP/HTTP em loopback. Não houve envio real ao Gmail ou nova cobrança. Inspeção visual das variantes e revisão independente entram na entrega da interface, ainda pendente.
- Varredura de 667 arquivos não encontrou valores dos segredos configurados no escopo examinado. A consulta somente de leitura confirmou demonstração V26 e ledger equilibrado; nenhum fluxo novo foi publicado nela. `Correcao_Dados_Sensiveis.md` detalha comportamento, provas e limites.
- Permanecem necessários: armazenamento protegido e backup/restore/chaves; confirmações finais, gestão de retenções, expiração financeira/logs/estatística e minimização; interface integral, publicação coordenada e relatório/revisões finais. Os textos continuam minutas alinhadas às mudanças, sem declaração de conclusão integral.

### Armazenamento e restauração verificados em 17/10, 12:27

- Runtime nativo, configurações, banco, uploads, correio, evidência antiga e disco WSL agora ficam em NTFS privado. `.env` e `.local/private-runtime.json` são ponteiros sem credenciais; resolvers recusam configuração redirecionada/incompleta e credenciais novamente inseridas na raiz. Java/PostgreSQL/jar/assets da demonstração executam por cópias protegidas. O código de desenvolvimento permanece no D: e não foi declarado host de produção endurecido.
- API/worker antigos encerraram normalmente, banco foi copiado a frio e uma terceira cópia abriu em 55433. Contagens/impressões de todas as tabelas públicas coincidiram nos três bancos; a cópia ativa voltou em 55432. Serviço independente em 5432 preservado. Depois da recuperação/readiness, originais verificados foram retirados do D:. Os relatórios Java antigos tiveram retirada individual limitada por cópia/hash após recusa da abordagem recursiva pela revisão automática.
- WSL foi compactado/movido offline por comando oficial, com registro atualizado, SHA256 preservado e ACL verificada. Boot/Docker não foram comprovados: virtualização do Windows continua indisponível (`HCS_E_HYPERV_NOT_INSTALLED`). Um cache público recuperável do instalador foi preservado no D: para liberar espaço no C:; não se descartou banco da distribuição.
- `Private/logs/privacy-private-runtime-final-tests.log`: **três unitários + 42 integrações aprovados**, sem skips, incluindo autenticação (17), arquivos (oito), direitos (16) e restauração física (um). Não somar rodadas repetidas. O novo teste usa dump completo real com extensões, reintroduz perfil/sessão/arquivo antigos e confirma supressão na inicialização, conservação do peer/ledger e nova supressão após restauração somente do arquivo.
- Os testes deixaram de carregar os segredos de execução de SMTP/Mercado Pago/chaves de criptografia. Relatórios e temporários novos são privados. O POM recusa ausência de testes; uma rodada diagnóstica com erro de caminho e zero resultados não foi contabilizada como aprovação. `Armazenamento_Backup_Local.md` descreve a sequência real de correções, evidências e limitações.
- A demonstração continua V26/ledger equilibrado; nenhuma migration V27–V32 ou minuta foi publicada e não se refez entrega Gmail/cobrança real. A próxima fase deve concluir **expurgo de backups/arquivos de migração/diário e rotação de chaves**, confirmações finais, gestão de retenções, expiração financeira/logs/estatística, minimização e toda a interface/aceites. Ainda são obrigatórios revisão Impeccable no escopo final, red teams, publicação coordenada e relatório dos 44 requisitos/16 entregáveis. O objetivo continua ativo.
- Verificação final de ACL: 18.688 caminhos, zero Allow para sujeitos não autorizados, sem itens desaparecidos naquela rodada; varredura de 517 arquivos sem valores dos segredos configurados. Resolver passou seus casos de falha segura e TypeScript legado compilou com `--noEmit`. A cópia física temporária do ensaio foi descartada depois, preservando o backup e o cluster ativo. O relatório público resumido está em `.impeccable/review/privacy/storage-migration.json`; ele não declara a camada integral concluída.

#### Próxima implementação: retenção operacional e chaves

Usar inventário/manifestos de backups com criação, prazo, escopo e integridade. A manutenção deve operar somente sobre diretórios de backup/arquivo explicitamente cadastrados e nunca sobre configuração ativa, executáveis, banco ativo ou VHDX. Recusar links/junctions, tratar limpeza parcial/erro e registrar resultado mínimo. Não usar exclusão recursiva baseada em caminho recebido pelo usuário/API. Preservações específicas devem extrair a evidência necessária para o cofre do caso, em vez de manter todo backup indefinidamente. O diário de supressão somente pode expirar depois da eliminação comprovada das cópias antigas que poderiam reintroduzir o titular; falha de expurgo não pode abrir uma janela de restauração sem supressão. Rotação deve manter leitura/verificação das evidências ainda vigentes e separar chaves/finalidades; não substituir peppers/chaves financeiros existentes sem migração e ensaio. Essa descrição é plano, não implementação concluída.

### Retenção de cópias e diário — implementação verificada em 17/10, 13:38

- V33 acrescenta identidade/watermark de recuperação ao banco, começando sem data estabelecida, e impede regressão simples/troca de identidade. Um snapshot anterior ao marco independente ou de outra base não é liberado. O instante original de exclusão é distinto do momento de reaplicação; corrigida a constraint que impedia reaplicar uma entrada antiga ainda necessária. Arquivos já marcados como apagados são novamente suprimidos antes da readiness e em lotes periódicos.
- Catálogo independente, prazos assinados, reserva antes do snapshot, finalização com ponto de restauração, nenhum prolongamento por repetição/re-assinatura, expurgo físico limitado, preservação do diário diante de cópia desconhecida/falha e metadados mínimos de cópia eliminada por 90 dias. Remoção do diário somente após prazo e confirmação das cópias antigas; intenções assinadas retomam interrupções. Perda seletiva de entrada é detectada pelo índice. O marco permanente não contém IDs do usuário/exclusão e não foi chamado de estatística anônima.
- Anéis diferentes para assinatura de backups e de diário, IDs de chave, verificação de versões anteriores e re-assinatura em lotes, mantendo prazos/identidade. Formato V1 compatível com o pepper original. TOKEN_PEPPER, chaves financeiras, SMTP e Mercado Pago não foram rotacionados. **Separação/rotação AES continua pendente.**
- Corrigido o despertar do worker: ele aguardava a recuperação usando o intervalo de fila, podendo esperar 60 segundos. A liberação agora sinaliza o monitor; encerramento durante a espera também é imediato. Não foi aumentado o timeout para mascarar a falha. E-mail/worker real foram novamente exercitados.
- Comando de arquivos instalado em runtime privado, sem contexto Spring/datasource/migrations/provedor; oito recursos históricos registrados e um backup novo reservado/finalizado. Ensaio nativo confirmou schema/tabelas/dados e limpou seu banco temporário. Catálogo atual: nove cópias, zero entradas de usuário, marco zero, sem vinculação V33 ou operação pendente. Sondagem de limpeza: zero arquivos vencidos. Configuração privada ganhou somente as variáveis de operação/assinatura; credenciais existentes preservadas. Demonstração continua V26 e pronta.
- `Private/logs/privacy-copies-worker-wakeup-final.log`: **30 unitários + 79 integrações aprovados**. `privacy-copies-concurrency-final.log`: **31 + sete** após contenção. `privacy-copies-io-legal-final.log`: **31 + 20** após controle de ausência/FS e validação dos documentos. Rodadas repetem casos; não somar. Logs de falhas anteriores foram preservados e explicados em `Retencao_Backups_Diario.md`. Variante Docker segue sem execução por virtualização indisponível; nativo usa PostgreSQL real.
- A automação de cópias/diário está integrada ao worker V33, **ainda não publicado**. A ferramenta de arquivos não expira o diário sem a barreira de banco. Falta ligar as demais ferramentas de restauração ao protocolo, configurar recuperação externa das chaves/controle e executar no host final. Isso não reduz o objetivo aos backups: permanecem confirmações finais, holds, retenção financeira/logs/estatística, AES, minimização, interface/aceites, revisão Impeccable, publicação coordenada e auditoria final dos 44 requisitos/16 entregáveis.

### Criptografia de evidências e correio — verificada em 21/10

- V34 e `PrivacyEvidenceCrypto`: anel AES dedicado, envelope versionado e contexto autenticado de campo/titular/recurso ou evento. Leitura compatível do legado sem trocar a chave financeira; configuração explícita obrigatória em produção, segredo/ID inválidos recusados, sem fallback silencioso de envelope novo. Protocolos, respostas, decisões, correção de e-mail, cofre e correio novo usam a separação. Não se passou a coletar IP.
- Conversão em transações pequenas e justas, com parada/retentativa. Falha conserva a cifra original, não impede itens seguintes ou trabalho financeiro e não imprime dados/segredos. A mudança da representação cifrada não reescreve metadados de aceite/decisão nem aumenta prazo. Evidência retida de e-mail perdeu a dependência escondida da cifra aninhada antiga, conservando o endereço dentro do cofre externo.
- Cadastro/recuperação também passaram a cifrar destinatário, link e conteúdo congelado. Consultas de atraso e fixtures seguem o vínculo titular/token. Destinatário legado é convertido quando esse vínculo existe; ausência de vínculo não gera associação inventada. Tokens, HMAC, validade, retentativa e identificação da entrega foram preservados.
- Chaves aleatórias próprias adicionadas somente aos arquivos privados de app e sandbox, preservando os demais valores. Instalador repetido não alterou anéis. Launchers preservam o anel do ambiente e não completam configuração parcial com chave de outra base. A ferramenta instalada de cópias continua válida: nove recursos presentes, nenhum diário de usuário, marco zero, sem vínculo V33; sem expurgo novo.
- `privacy-evidence-first.log`: cinco unitários + 52 integrações aprovados. `privacy-evidence-rotation.log`: cinco + quatro. `privacy-evidence-financial-restore.log`: 15 + 115. Após a extensão ao correio, `privacy-evidence-all-mail-final.log`: 15 + 68, sem skips. Contagens têm repetições e não devem ser somadas como casos distintos. Compilação inicial encontrou colisão de variável local, corrigida antes das rodadas aprovadas.
- PG local recuperado e API/worker retomados com o artefato imutável **V26**. Readiness da API confirmada; consulta somente de leitura confirmou V26, ledger equilibrado e zero parcelas históricas `no_class` retidas. Nenhuma V27–V34, aceite/minuta ou novo envio/cobrança foi publicado por esta etapa. Os ensaios usam PostgreSQL real descartável e transportes externos simulados.
- `Criptografia_Evidencias.md` descreve operação, escopo e limites. Falta recuperação fora deste computador e procedimento final de retirada de chaves considerando cópias/entregas; nenhuma chave antiga foi removida. Continuam pendentes confirmações finais, administração de holds, retenção financeira/logs/estatística anônima, minimização, toda a interface, publicação/revisões e relatório final. O objetivo dos 44 requisitos permanece em andamento.
- Verificação final desta etapa: varredura de 554 arquivos sem os valores dos segredos configurados; auditoria de 21.371 caminhos privados sem Allow indevido ou desaparecimentos durante a leitura. São verificações delimitadas, sem declaração de segurança integral ou proteção do volume de desenvolvimento exFAT. Evidência pública sem segredos em `.impeccable/review/privacy/evidence-encryption.json`.

### Gestão de retenções — verificada em 21/10, 15:46

- V35 implementa criação, revisão por versão esperada, liberação, fila de revisão, justificativa/histórico cifrados e acesso auditado à evidência específica com senha novamente. Escopo e pertencimento verificados, sem caminho/tabela do cliente, sem restauração de dado já eliminado e sem nova retenção genérica. Titular consulta somente suas categorias/bases/prazos. Seis rotas novas, com contrato estrito e autorização registrados; catálogo completo chega a 132 rotas.
- Corrigida concorrência entre verificação de hold e apagamento físico: coordenação em transação entre API/worker, rechecagem no momento de remover e gatilho de instrução para alterações de retenção. Os dois sentidos da corrida foram testados com espera observada no PostgreSQL e barreiras de I/O. Arquivo faltante/erro não recebe confirmação falsa de proteção/remoção.
- Dados mutáveis recebem cópia mínima cifrada ao iniciar a retenção. Correção legítima do perfil continua permitida; dados antigos do caso não são reescritos. Exportação própria inclui cópias de identificação/perfil/veículo com lista de campos permitidos e omite notas internas e dados de outra conta.
- Cofre distingue `hold_only` de base financeira própria. Ao liberar/vencer, a primeira fica elegível à limpeza, sem manter indevidamente a data antiga; a segunda conserva seu prazo. Perfil e aviso não permanecem públicos por causa do hold; recibo/aviso específico não congela todos os registros da conta. Histórico legado tem fotografia identificada, sem auditoria antiga inventada; histórico novo tem vínculo composto com titular/hold, expiração e rotação AES.
- `privacy-holds-scope-data-final.log`: cinco unitários + 57 integrações aprovados. `privacy-holds-financial-restore-final.log`: 18 + 140 antes da captura inicial. `privacy-holds-snapshot-file-final.log`: cinco + 37 após a captura, incluindo os oito arquivos reais. `privacy-holds-export-legal-final.log`: cinco + 42 após exportação/filtragem, incluindo 13 retenções e 13 documentos jurídicos. Sem skips; não somar rodadas repetidas. A primeira rodada HTTP falhou por contratos/autorização ainda não registrados e foi corrigida; nomes de seleção inexistentes não foram contados como casos aprovados. O wrapper passou a recusar esse erro em listas mistas.
- Minuta de privacidade e inventário atualizados; `Retencoes_Administrativas.md` explica APIs, evidências e limites. Prazos 90/365 dias e histórico de cinco anos são política proposta, não obrigação geral atribuída à LGPD. Fonte oficial compilada consultada; **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA**. Nenhuma política ou V27–V35 foi publicada na demonstração, que continua V26 e ledger equilibrado.
- Ainda faltam avisos finais de encerramento, recuperação externa/reaplicação de retenções posteriores ao snapshot, retenção financeira/logs/estatísticas, minimização e toda a interface/aceites, além de publicação e revisões finais. A revisão da minimização deve fechar também o acesso a fotos antigas de participante, hoje autorizado por vínculo com o dono. O objetivo integral permanece ativo, sem redefinição para esta etapa.

### Avisos essenciais de encerramento — verificados em 21/10, 16:15

- V36 integra avisos do pedido, iniciativa da plataforma, cancelamento e remoção concluída à fila criptografada existente. Mensagem preparada atomicamente com pedido/cancelamento; preparação final em manutenção independente depois da remoção, sem depender do provedor para concluir a exclusão. Marketing desligado não suprime avisos essenciais.
- Destinatário final verificado capturado somente quando o processamento começa, cifrado e com janela fixa de 90 dias. Preparar o job remove essa cópia transitória; retentativa usa somente payload cifrado, sem renovação do prazo. Limpeza/expiração e supressão por restauração deixam metadados mínimos, sem endereço nos logs. Falta de chave antiga recebe retentativa própria sem reabrir a conta ou impedir outras preparações.
- Entrega confere titular/pedido/fase/estado atual, marcador de preparo final e prazos. Cancelamento antigo não pode sugerir que um pedido novo foi cancelado. Replay da supressão limpa contatos/avisos antigos e não cria novo e-mail a cada execução. Identificação estável e conteúdo congelado não foram apresentados como garantia de envio exatamente uma vez em SMTP/backup antigo.
- Copy preserva o layout de e-mail e explica data, consulta do estado atual, restrições não removidas pelo cancelamento, limite da exclusão e canal de privacidade depois de perder o acesso. Data de registro distingue a confirmação de eventual conta nova; conclusão não é quitação financeira. Nenhum novo e-mail real, cobrança ou fornecedor foi utilizado nos ensaios.
- `privacy-closure-notice-foundation.log`: dez unitários + 46 integrações; `privacy-closure-notice-http-first.log`: 11 + nove; `privacy-closure-financial-restore-final.log`: 24 + 138; `privacy-closure-lifecycle-final.log`: 11 + 44 após a proteção contra mensagem antiga e auditoria de expiração, com dez cenários novos de avisos. Todos aprovados, sem skips; rodadas repetem casos e não se somam como testes distintos. Rotação cobre também o contato transitório.
- Quatro HTML sintéticos foram gerados pelo renderer real, sem dados/links privados, e servidos apenas por whitelist em loopback. HTTP 200 confirmado; abertura no painel ficou em fila. Inspeção desktop/mobile e revisão visual **não realizadas**: CUA e a alternativa documentada de Computer Use falharam na inicialização com `failed to write kernel assets ... (os error 3)`. Não se usou o artefato privado de e-mail anteriormente recusado nem uma cópia dele. O fechamento visual Impeccable continua pendente no fluxo integral.
- `Avisos_Encerramento.md` e inventário/minuta de privacidade atualizados. A ajuda pública antiga usada como URL de privacidade precisa ser ligada ao leitor jurídico na integração da interface, antes de publicar. Demonstração continua V26, ledger equilibrado; nenhuma V27–V36 ou minuta foi publicada. Varredura de 578 arquivos não encontrou os valores dos segredos configurados no escopo examinado.
- Próximas partes continuam sendo recuperação externa/holds após snapshot, retenção financeira/logs e estatística anônima, minimização entre participantes/localização, interface integral com aceites/leitor público, publicação/revisões e auditoria final dos 44 requisitos/16 entregáveis. Há progresso verificável e não há motivo para declarar o objetivo concluído ou bloqueado.

### Minimização de contato/foto/placa e direitos — verificada em 21/10, 22:38

- Telefone e foto da contraparte exigem reserva confirmada/embarcada, carona prevista/ativa, ausência de bloqueio bilateral e menos de 48 horas após a saída prevista no fuso da instituição. Encerramento da carona ou entrada na confirmação financeira não mantém acesso. O limite é política operacional proposta, não prazo atribuído à LGPD. Reserva futura confirmada continua permitindo organização; abertura anterior à saída não foi limitada por esta etapa.
- Download verifica que o arquivo ainda é a foto atual do dono. URL antiga substituída/removida perde autorização mesmo havendo outra reserva; acesso do próprio titular e autorização administrativa existente permanecem distintos. Listas/detalhes da reserva e detalhe da carona protegem a placa completa quando acaba a finalidade ou há bloqueio; contrato antigo permanece consultável. Regra compartilhada evita divergência entre foto exibida e arquivo privado.
- A regressão ampliada revelou que a verificação global de e-mail bloqueava relato de viagem antiga e direitos próprios apesar de sessão válida. Exceção delimitada conserva privacidade/ocorrências/bloqueios, leitura de `auth/me` e logout, sem emitir sessões para novos cadastros ou liberar busca/caronas/arquivos/administração sem verificação. CSRF, senha, vínculo com contraparte, titular e autorização continuam valendo. Caso novo verifica exportação própria, recusa de outro titular e logout efetivo.
- `privacy-participant-access-final.log`: cinco unitários + 58 integrações aprovados antes da extensão final. `privacy-participant-rights-auth-final.log`: **dez unitários + 200 integrações aprovados**, sem skips; inclui autenticação, e-mail, arquivos, caronas, mensal, ciclo pago, ocorrências, estados da conta, correção, documentos e direitos. Rodadas se sobrepõem. Logs anteriores registram SQL corrigido e dois testes antigos atualizados somente para a diferença intencional de placa e para a cifra dedicada; não se enfraqueceu a criptografia.
- `Minimizacao_Participantes.md`, inventário e minuta de privacidade descrevem alcance e regras. Nenhuma migration nova; fonte permanece V36, sem instalação na demonstração V26. Readiness HTTP 200, ledger equilibrado e zero parcelas antigas `no_class` retidas foram novamente confirmados em leitura. Nenhum e-mail/cobrança real novo foi usado. Interface e revisão visual continuam pendentes, sem alegação de inspeção neste checkpoint.
- Continuam em aberto nomes/IDs, endereço/localização/agenda, expurgo de arquivos substituídos, retenção financeira/logs/estatística anônima, recuperação externa e holds posteriores ao snapshot, interface/aceites, publicação coordenada, red teams e auditoria final integral. O objetivo dos 44 requisitos/16 entregáveis permanece ativo.

### Nomes, encontro e acesso à rotina — verificados em 21/10, 23:03

- Primeiro nome e inicial final nas projeções da contraparte: ofertas/rotinas, solicitações, reservas/contato, contratos, interesses, bloqueios e resumo de pagamento. Dados próprios e consultas administrativas autorizadas preservam a identificação pertinente. Estado de perfil excluído impede reapresentar nome cru incoerentemente restaurado. A redução continua sendo tratamento de dado pessoal; não é anonimização.
- Ofertas iniciais ocultam encontro exato e observações. Solicitação aceita e válida permite conhecer essas informações antes do pagamento, sem liberar telefone/foto/placa. Reserva paga usa sua própria regra operacional e tem prioridade quando também existe outra proposta aceita. Detalhes históricos protegem encontro e dados acadêmicos atuais; informações necessárias do contrato continuam consultáveis.
- Rotina corrente deixou de ser acessível por simples contrato antigo sob bloqueio/inatividade. Autorização ao ponto privado verifica o trecho/rota efetivamente contratado; trocar a referência de rota não concede acesso ao novo ponto. Vínculo pago e consulta da própria oferta não dependem de perfil acadêmico presente. O servidor usa consultas projetadas, sem carregamento de perfis por item.
- `privacy-participant-identification-foundation.log`: três unitários + dois HTTP; `privacy-name-location-rights-final.log`: **oito unitários + 213 integrações aprovados**, sem skips. Inclui pagamento, arquivos, caronas, contratos/rotinas, ciclo de viagem, solicitações, ocorrências, perfil próprio, documentos e direitos. Casos se repetem; não somar rodadas. Leituras legadas mantêm comparações dos demais campos e ajustam apenas diferenças intencionais de privacidade. A fixture de perfil ganhou aceites sintéticos; não se afrouxou o gate real.
- Inventário, minuta e `Minimizacao_Participantes.md` atualizados com fases, decisões e limites. Fontes oficiais de LGPD/CDC consultadas; revelar o encontro após aceite e antes de pagar é política de produto proposta, não uma sequência atribuída à lei. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Nenhuma migration nova; fonte V36 ainda não publicada. Demonstração continua V26 e ledger equilibrado.
- Interface deverá efetivamente exibir detalhes aceitos no checkout antes do pagamento. Ponto não é editável pela API atual; observações são e exigem revisão de apresentação/prova. Endereços/coordenadas armazenados, campos acadêmicos/IDs, estatísticas e prazos financeiros/logs permanecem na revisão final. Expurgo de fotos de conta ativa requer supressão por arquivo independente do banco, além dos holds posteriores ao snapshot; essa frente continua implementável, não concluída pelo controle de conta existente.
- Prosseguem recuperação externa/diários por recurso, retenção e minimização remanescentes, interface jurídica/aceites, revisão visual, publicação e auditoria integral dos 44 requisitos/16 entregáveis. Nenhum e-mail ou pagamento real novo foi usado nestes testes.
- Conferência final do checkpoint: XMLs das 11 suítes somam 213 integrações, sem falha/erro/skip; varredura delimitada de 589 arquivos sem valores dos segredos configurados. Readiness HTTP 200 e V26/ledger equilibrado novamente confirmados às 23:05. Evidência pública em `.impeccable/review/privacy/participant-identity-location.json`.

## Interface própria de privacidade e leitura pública — em desenvolvimento

- Implementados em `dist`: leitor versionado, aceites no cadastro/reaceite, centro próprio de direitos, protocolos/respostas, marketing separado, exportação com senha, desativação/reativação, exclusão e reversão conforme estado, correções sensíveis, retenções específicas e explicação de localização. Rodapés e URL padrão de privacidade dos e-mails ligados ao leitor. Administração ainda pendente.
- **19 testes JS**, **7 unitários Java e 31 integrações Java** aprovados. Corrigido caso de documentos atualizados após queda de conexão que preservava controles marcados. Acrescentados controle de resposta tardia, separação de senha incorreta/renovação de sessão, mapa de idempotência sem senha no identificador, validação de ZIP e revogação de URLs temporárias. `Interface_Privacidade.md` descreve contratos e limites; evidência em `.impeccable/review/privacy/privacy-ui-functional.json`.
- Navegador continua falhando antes de iniciar por escrita dos arquivos do kernel (`os error 3`). Nenhuma captura antiga foi apresentada como validação desta interface. Inspeção real desktop/celular, fechamento independente Impeccable e documentação final de design continuam pendentes. Testes JS/HTTP não substituem jornada real renderizada.
- Fonte segue V36, sem migration nova. Nenhum e-mail ou pagamento real novo enviado. Demonstração V26/ledger equilibrado, prontidão 200; `api.js` servido corresponde à cópia privada congelada e difere do desenvolvimento. Varredura delimitada: 596 arquivos, zero ocorrências de valores dos segredos configurados.
- Próximas frentes: administração de privacidade, supressão independente por arquivo, recuperação externa/holds após snapshot, retenção financeira/logs/estatística anônima, minimização final, red teams, matriz de 44 requisitos/16 entregáveis e publicação coordenada. O objetivo integral permanece ativo.

## Supressão individual e uploads sem vínculo — V37 em desenvolvimento

- Adicionado `last_uploaded_at`, com preenchimento pela criação na migration e atualização em reenvio deduplicado. Upload sem vínculo é elegível depois de 24h do último envio, salvo foto atual, documento de candidatura ou preservação vigente. Seleção é revalidada sob trava de retenção e bloqueio do titular; quota só libera após remoção física verificada.
- Diário independente aceita instrução por arquivo, assinada e incluída no índice. Retentativa/rotação não estendem prazo; expiração depende de ausência das cópias anteriores e avança o marco mínimo. Replay remove foto antiga que um banco restaurado ainda aponta como atual, preservando conta e sessão. Nova ferramenta de operações deve acompanhar API/worker na publicação.
- Conferidos **26 unitários e 87 integrações**, nas evidências finais das nove suítes de integração e duas unitárias. Inclui dois ensaios reais de `pg_dump`/`pg_restore` e dez cenários novos de ciclo de arquivo/worker. A rodada ampliada inicialmente falhou apenas nas contagens antigas da migration; compatibilidade corrigida passou. Um teste posterior não iniciou com PostgreSQL parado; retomado o cluster existente, a rodada passou. Ver `Arquivos_Sem_Uso.md` e `.impeccable/review/privacy/file-retirement.json`.
- Minuta de privacidade, inventário e documentação de minimização/backups atualizados, sem publicação. Prazo de 24h é política operacional proposta, não obrigação atribuída à LGPD. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**
- Demonstração retomada pelo launcher privado e jar V26 congelado, sem migrations novas: prontidão 200 e ledger equilibrado em 22/10 às 20:10. Os testes não iniciaram cobrança ou mensagem real.
- Persistem recuperação externa e de preservações posteriores ao snapshot, bytes órfãos sem linha/entrada anterior, retenção de documentos vinculados e metadados, financeiro/logs/estatística anônima, administração de privacidade, minimização restante, revisão real da interface, red teams, publicação coordenada e relatório integral. O objetivo dos 44 requisitos/16 entregáveis permanece ativo.

## Interface administrativa e controle de conclusão — 23/10

- Integrados fila/atendimento de direitos, situação identificada da conta, decisões com senha e efeitos explícitos, preservações específicas, consulta auditada e download protegido. Estado esperado/versão, confirmação e separação de motivos mantidos. Perfis excluídos não expõem identidade bruta restaurada na consulta administrativa. `Interface_Privacidade.md` descreve os percursos e limites.
- A inspeção real desktop/mobile encontrou AP-01: “Todas” enviava parâmetro de status vazio, recusado antes do controller. Corrigidas ambas as filas sem afrouxar o contrato; provas de filtro, entrada pela conta, criação e volta ao registro coletadas em prévia descartável com contas sintéticas. Nenhuma migration adicional para essa interface.
- **36 testes JS**, rodada HTTP inicial de **55 integrações**, e rodada final de **14 integrações + três unitários**, todas aprovadas. Há sobreposição; não somar como casos únicos. Evidência estruturada: `.impeccable/review/privacy/admin-ui-functional.json`.
- Fechamento Impeccable com revisão independente, recapturas válidas, AP-01 resolvido e veredito **ship limitado ao achado**, seguido de comparação documental da extensão. Não declara certificação do produto completo. DESIGN.md/sidecar/PRODUCT preservados; drift antigo registrado. Sem novo raster de produto. Artefatos em `.impeccable/review/privacy/admin-finish-verdict.md` e `admin-design-documentation.md`.
- Serviços locais estavam parados após interrupção do computador. O cluster existente recuperou e ficou pronto; API e worker retomados pelo launcher e jar privado V26, sem migrations novas no sandbox. Em 23/10 às 13:27 UTC: V26, ledger equilibrado, zero parcelas antigas `no_class` retidas; prontidão HTTP200 conferida. Os testes sintéticos não iniciaram pagamentos/e-mails reais; a retomada do worker pode executar trabalhos já existentes do ambiente de testes.
- Varredura delimitada dos valores dos segredos configurados: **621 arquivos, zero ocorrências**, com exclusões documentadas pelo script. Não equivale a auditoria universal. Identidade pública do responsável conferida na configuração compartilhada; não foram inventados documento/empresa/endereço.
- Criada `Matriz_44_Requisitos.md` com os 44 itens e evidências/lacunas. Centro próprio foi acessado pelo percurso Configurações → Privacidade e dados com aluna fictícia; jornada completa renderizada ainda pendente. Seguem recuperação externa/holds após snapshot, órfãos/documentos/metadados, financeiro/logs/estatísticas anônimas, minimização restante, revisão jurídica/consistência, red teams, publicação e dezesseis entregáveis finais. Objetivo integral permanece ativo.

## Complemento de 25/10 — replay de preservações posteriores ao backup

- Fundação/ponte V38 agora ligadas aos comandos reais de retenção, cofre, remoção
  de perfil, expurgo e rotação; snapshots tipados não aceitam campos extras ou SQL.
  Blobs privados cifrados separam arquivos de até 5 MiB do controle. Nenhuma
  migration já aplicada foi editada; nenhuma mudança de frontend nesta etapa.
- Recuperação confirmada ocorre antes das supressões e da limpeza de localização;
  conserva decisão mais recente e prazo original, sem inventar titular/ator,
  contrato ou aceite. Cópia corrompida, resultado incerto e dependência ausente
  impedem prontidão e mantêm evidência para recuperação assistida.
- Expiração retira referências; manutenção valida controle/blobs atuais antes
  de retirar versões anteriores. Rotação publica novo controle/blob sem mudar
  decisões ou prazo, com teste isolado de restauração e retirada das chaves antigas.
- `Recuperacao_Retencoes.md` contém detalhes e evidência delimitada. Não equivale
  a recuperação fora deste computador, publicação V38, prontidão comercial ou
  encerramento dos 44 requisitos. A demo V26 protegida e os assets congelados
  continuam preservados. Não houve pagamento ou envio real de e-mail nesta etapa.
- Próximas lacunas: ensaios por categoria/dependência e recuperação externa de
  controle/chaves, arquivos órfãos/documentos ligados, retenção financeira/logs,
  agregação anônima, minimização restante, jornadas/aceites, consistência jurídica,
  red teams, publicação coordenada e dezesseis entregáveis. Objetivo integral ativo.
- Rodada final: **46 unitários e 76 integrações**, sem falhas/erros/skips; dez
  cenários novos de recuperação e HTTP administrativo com controle independente.
  Evidência com hashes em `evidence/hold-control-25oct.json`. Prévia sintética
  retomada em localhost:4190 com HTTP200/provedor `development`, sem migrar a demo.

## Complemento V39 — uploads interrompidos e bytes sem metadados

- O diagnóstico identificou a janela de bytes gravados antes do commit e o risco
  de apagar um upload já confirmado ao tratar exceção posterior ao commit.
  `FileTransactions.upload` mantém coordenação por UUID até a conclusão, distingue
  rollback conhecido/deduplicação de resultado incerto e conserva o arquivo
  confirmado. O coletor não espera por um upload em andamento.
- `PrivacyOrphanFiles` exige duas observações estáveis separadas por 24h, confere
  ausência de linha/vínculo e de preservação e publica supressão exata por dataset,
  tamanho/hash/UUID sem inventar titular. Confirma ausência depois de commit;
  reintrodução invalida a confirmação e cópias anteriores à confirmação bloqueiam
  expiração. A V39 não preenche observações retroativamente.
- A guarda recupera instruções antes de abrir os dados. Restore físico prova foto
  WebP vinculada em arquivo antigo e bytes sem linha, com conta ativa e crédito
  sintético de 700 preservados. Formatos reais WebP/PDF criados depois do backup
  também foram acrescentados à prova de retenções; PNG/JPG não são mais o único
  caminho aceito pelo snapshot/extração administrativa.
- Worker observa retirada de prontidão entre tarefas e antes de iniciar e-mail.
  Nenhum CSS/componente/DS/asset foi editado neste complemento. Minuta de
  Privacidade em revisão 2 e catálogo `draft`; sem publicação ou recibo retroativo.
- Rodada inicial de integração não executou os cenários porque o cluster estava
  parado. O PostgreSQL existente fez recuperação automática e voltou na porta
  isolada 55432, sem recriar o cluster ou afetar o serviço separado em 5432.
  `Arquivos_Orfaos.md` discrimina provas e rodadas, sem somar sobreposições.
- A regressão ampliada encontrou um cenário jurídico que usava revisão 2 fixa,
  agora igual à minuta corrente. A recusa de republicar outro conteúdo nessa
  revisão foi preservada; o cenário usa revisão atual +1. Não é uma autorização
  para desabilitar a proteção ou publicar rascunhos.
- Ainda necessários: conclusão/expiração das supressões anteriores de conta e
  arquivo conhecido, documentos ligados/metadados, financeiro/logs/agregação
  anônima, recuperação externa, volumes/categorias, minimização, jornadas/aceites,
  matriz jurídica/red teams, publicação coordenada e os dezesseis entregáveis.
  O objetivo integral permanece ativo.
- Evidência do complemento em `evidence/orphan-files-25oct.json`: 76 unitários
  aprovados na rodada ampliada e 169 integrações, com um erro do cenário de revisão
  fixa; repetição direcionada dos 15 jurídicos passou após corrigir o cenário.
  Não chamar a rodada ampla de BUILD SUCCESS nem somar casos repetidos. Prévia
  sintética retomada em localhost:4190, sem alterar o jar V26 protegido.

## Complemento de protocolo V39 — conclusão das supressões anteriores

- Conta v3 e arquivo conhecido v2 passam a registrar confirmação assinada após
  conferência do banco e commit, preservando formatos antigos e datas originais.
  Preparo, rekey, rollback ou intenção de remover não fabricam conclusão.
- A expiração usa o instante de conclusão, incluindo cópias entre preparo e
  exclusão efetiva. O banco confirma o horizonte antes de elevar o marco
  independente; arquivos restaurados e perfis parcialmente reduzidos reabrem a
  prova. Linha ocupada não é confundida com linha ausente e não monopoliza o lote.
- Replay também reconhece arquivo ainda não removido após fim de preservação.
  Preservação vigente mantém a evidência privada íntegra. Cópias autônomas de
  identidade/veículo/rota não são duplicadas ou renovadas por reinicialização;
  upload confere o dono antes de gravar bytes.
- Manutenção resolve somente a proposta exata com marcador de commit confirmado,
  inclusive durante a janela do callback original; proposta desconhecida continua
  impedindo limpeza e retirando prontidão.
- Rodada ampla: **63 unitários e 86 integrações** aprovados. Após o último ajuste:
  **32 unitários e 37 integrações** aprovados, incluindo 13 cenários específicos de
  confirmação, preservação/fim de finalidade, rollback, falha física, restauração,
  conflito, dados dependentes, locks e saldos. Não somar rodadas sobrepostas.
- Sem migration adicional, frontend, publicação real ou mudança do jar V26.
  [Protocolo, provas e limites](Confirmacao_Supressoes.md), com evidência própria.
  Continuam pendentes documentos ligados/metadados, financeiro/logs/agregação
  anônima, recuperação externa e volumes/categorias, minimização restante,
  jornadas/aceites, consistência jurídica, red teams, publicação coordenada e os
  dezesseis entregáveis. O objetivo integral permanece ativo.

## V42 — eventos operacionais de segurança

- Prazo de seis meses de calendário por data original e contexto para a família
  explícita de eventos de senha/e-mail/configurações. Demais famílias, provas
  financeiras e elegibilidade conservam suas guardas e exigem política própria.
- Histórico não aceita edição; somente eventos elegíveis vencidos podem sair.
  Exportação oculta os vencidos, inclui ID próprio e cópia de caso com projeção
  permitida. Cópia privada, reauth, titular/recurso e controle independente ligados.
- Cópia ausente ou adulterada não autoriza apagar o original nem substituir o
  head independente válido. Recuperação por arquivo real não recria o evento
  comum que já não estava no backup. Snapshot numérico grande não vira legado.
- Lotes com cursor/interrupção e ensaio de 1.201 eventos; startup aplica vencimento
  após replay e antes da prontidão. Não equivale a teste de toda a capacidade.
- Regressão ampla46 unitários+164 integrações; rodada seguinte46+72; fonte final
  reforçada46+47 (14 segurança,7 controle,11 pós-backup,15 jurídicos), sem falhas/erros/skips.
  49JS e detector único `[]`; não somar rodadas sobrepostas.
- Privacidade r6 em draft, r5 preservada, Motorista r3 mantido. Usuário pediu
  continuar outras pendências e adiar QA visual. V26/frontend privados preservados.
  Relatório: [Logs_Seguranca_Retencao.md](Logs_Seguranca_Retencao.md).

## V41 — finalidade de dados transitórios de checkout

### Complemento26/10 — contato original do comando de cartão

- Falha reproduzida na ramificação de produção do cliente contra fixture local:
  após resposta perdida e troca de e-mail, reenvio mantinha chave e mudava contato
  (HTTP422). Sandbox sozinho não a detectava, por normalizar o e-mail do pagador.
- Envelope cifrado versionado vincula cobrança/titular/valor e congela contato.
  A leitura confere esquema e hash do input. Início/validação têm a mesma trava
  transacional; chamada externa continua sem segurar a transação. Resultado
  conhecido elimina envelope, sem confundir criação com aprovação.
- Legado nunca enviado é atualizado sob trava; já enviado sem contato original
  não é reenviado por suposição. Callback assinado/GET verificado ainda recupera
  o pedido e limpa o input, com recebimento único. Não há nova migration/UI nem
  publicação das minutas ou alteração da demonstração V26.
- Regressão ampla:59 unitários+106 integrações aprovados (65HTTP do provedor,
  13checkout,13 correção,15jurídicos). Outra rodada final confirma22 unitários e
  quatro cenários na ramificação de produção contra loopback após o reforço
  final de formato/contato; não somar casos
  sobrepostos. Relatório e limites em
  [Comando_Cartao_Contato_Original.md](Comando_Cartao_Contato_Original.md).

A lacuna de expiração de audit_logs identificada ao concluir o complemento do cartão passou a ser tratada, para a família operacional classificada, pela V42 acima. As demais famílias continuam abertas; o expurgo não transforma UUIDs em dados anônimos.

- Minimização de contato de criação Pix/regularização e comando de cartão
  dispensável; identidade da solicitação financeira preservada. Resposta incerta
  não é apagada como falha nem interpretada como recebimento.
- QR/imagem e autenticação restritos a checkout/passo vigente; ticket sem uso
  retirado. Leitura própria oculta o dispensável antes do job; SQL também impede
  repor credenciais em estado sem finalidade. Sem expurgo de ledger, valores,
  referências, provas ou pagamentos/devoluções por essa rotina.
- Cadastro de regularização nunca iniciado não começa depois do prazo; envio
  já iniciado mantém recuperação da mesma solicitação. Cursor e guarda de
  prontidão fazem a limpeza delimitada com checkpoint e trava do titular.
- Interface cessa formulário/QR no prazo interno e mantém consulta do resultado.
  Não confunde checkout encerrado com cancelamento externo. Fonte r4 preservada,
  Privacidade r5 em draft e Motorista r3 mantido; sem publicação/aceite real.
- Fonte/testes e limites em [Dados_Transitorios_Checkout.md](Dados_Transitorios_Checkout.md).
  Fonte final:47 unitários+39 integrações aprovados (migração11, checkout13,
  jurídico15); rodada anterior49+92, incluindo61 HTTP do provedor e18 de direitos.
  48 JS finais passaram, detector único `[]`. Não somar sobreposições. QA visual depende da liberação de
  acesso local já solicitada; não usar capturas anteriores como prova deste estado.

## V40 — anexos de candidaturas e metadados de arquivos

Complemento na fonte em 26/10: ciclo de metadados da candidatura, separado dos
anexos, em [Metadados_Candidatura_Motorista.md](Metadados_Candidatura_Motorista.md).
Motivo/revisor 90 dias; candidaturas encerradas e veículos sem finalidade após
365 dias; prova mínima ainda usada e caso cifrado separados. Sem migration nova.
Privacidade r4 e Motorista r3 continuam `draft`, com fontes anteriores preservadas.
Backend deste complemento validado em escopo delimitado; a rodada inicial de 26/10 passou nos 49
unitários, mas as cinco classes de integração não iniciaram seus cenários por
recusa de conexão na porta55432. A rodada de 25/10 executou 40 integrações com
um erro e uma falha de fixtures; não são resultados aprovados do complemento.
Após correção e recuperação do cluster existente:49 unitários+103 integrações
aprovados antes do reforço de datas; fonte final49+43, incluindo15 cenários de
metadados, jurídico15 e supressão13.46 JS passaram. Não somar sobreposições.
Visual do novo item administrativo/próprio pendente:Chrome recusou localhost4190
por política de acesso; solicitação de liberação enviada ao usuário. Sem nova
captura, sem veredito global e sem publicação. Detalhes no relatório do complemento.

- Prazos de 30 dias para solicitação pendente e 30 após decisão, com datas legadas
  preservadas na migração. Vencimento pendente exige nova solicitação; cópia
  retirada de aprovado não revoga a aprovação nem altera veículo/saldo.
- Links ordinários encerrados antes da remoção física; preservação específica
  permanece no fluxo auditado do caso. Finalidade compartilhada e reenvio real
  recente são conferidos sob trava. Revisão administrativa verifica prazo,
  titular, finalidade e bytes/hash atuais, sem garantia fictícia de autenticidade.
- `stored_files` sem finalidade/vínculo fica elegível à retirada após 90 dias da
  remoção física, com nova conferência de ausência. Diário individual e registros
  financeiros continuam separados; conflitos/linhas ocupadas não monopolizam a
  fila e não são tratados como sucesso.
- Snapshot tipado 2 conserva último upload real; legado 1 usa criação conservadora.
  Recuperação não fabrica reenvio. Coletor de blobs compreende ambos os formatos
  conhecidos e continua recusando esquemas desconhecidos.
- Fonte preservada de minutas anteriores; Privacidade r3 e Motorista r2 em
  `draft`. Formulário, área aprovada e fila administrativa explicam os estados
  com os componentes existentes, sem novo CSS ou identidade. Confirmação visual
  móvel delimitada concluída; desktop conferido por conteúdo/geometria, com
  capturas insuficientes para certificação de pixels. Não é aprovação global.
- Rodada ampla: 58 unitários e 128 integrações, com dois erros na compatibilidade
  do coletor. Rodada após correção: **49 unitários e 38 integrações** aprovados,
  incluindo 15 cenários de anexos/metadados. **46 JS** aprovados. Não somar
  sobreposições nem chamar a rodada ampla de `BUILD SUCCESS`.
- [Protocolo, decisões, testes e limites](Documentos_Motorista_Retencao.md).
  [Evidência e hashes](evidence/driver-document-retention-25oct.json) e
  [revisão de interface](../../.impeccable/review/privacy/driver-document-retention-review.md)
  registram duas passagens visuais limitadas, os três ajustes e os limites reais.
  Continuam metadados de candidatura/decisão, necessidade de identificação
  adicional e verificação efetiva, financeiro/logs/agregação, recuperação externa,
  volumes/categorias, minimização restante, jornadas/aceites, consistência/red
  teams, publicação coordenada e os dezesseis entregáveis. Objetivo integral ativo.
