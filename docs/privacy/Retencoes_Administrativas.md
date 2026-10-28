# Retenções administrativas específicas

Complemento de fonte 25/10: V38, controle independente e confirmação transacional
agora estão ligados à criação/revisão/liberação deste painel, cofre, remoção de
perfil, rotação e expurgo. `Recuperacao_Retencoes.md` descreve o replay tipado de
decisões posteriores ao backup e os blobs cifrados por arquivo. Há provas físicas
delimitadas; recuperação fora deste computador, dependências ausentes, demais
categorias, publicação e auditoria integral continuam abertas.

Backend V35 em validação, 21/10/2026. A demonstração continua V26. Interface administrativa/centro de privacidade, publicação coordenada e auditoria integral do objetivo ainda não estão concluídas.

## Finalidade e limites jurídicos

Uma retenção específica conserva somente a evidência necessária a uma obrigação/caso identificado; não suspende automaticamente a exclusão de toda a conta. A [LGPD, texto compilado oficial](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm), especialmente arts. 6º, 7º, 15, 16 e 18, exige que finalidade, necessidade, base e exercício dos direitos sejam avaliados. Um registro no painel não comprova sozinho a existência de ordem legal ou legitimidade do motivo.

Revisão em até 90 dias, vigência de até 365 dias por decisão e histórico inicial de cinco anos são escolhas operacionais propostas, **não prazos universais impostos pela LGPD**. A revisão não estende a vigência automaticamente. Obrigações específicas, dados sensíveis, períodos de defesa e regras de uma determinação concreta exigem análise individual. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** A classificação técnica `security` não é uma nova base legal: exige análise documentada da hipótese aplicável, necessidade e proporcionalidade; não autoriza retenção genérica.

## Fluxo implementado

1. Administrador seleciona titular, categoria e um recurso que efetivamente lhe pertence. Novo cadastro de retenção não admite recurso em branco.
2. Informa base, justificativa concreta, referência do caso, próxima revisão e vencimento; confirma a senha novamente. Senha/sessão/estado/papel atuais são reconferidos dentro da transação.
3. A aplicação verifica disponibilidade do recurso. Arquivo eliminado não é recuperado nem declarado protegido; perfil já removido somente pode ter nova retenção se ainda existir cópia pertinente no cofre.
   Perfil, identificação mínima, rota, veículo e notificação mutáveis recebem uma cópia mínima cifrada ao iniciar a retenção. Não se espera a exclusão da conta para conservar a versão relevante. Correções do dado atual continuam permitidas e não reescrevem essa evidência. Trata-se de cópia delimitada daquele momento, sem histórico oculto de todas as alterações.
4. Criação é idempotente; uma duplicata ativa para o mesmo recurso usa o fluxo de revisão. Revisão/liberação exige a revisão esperada do registro para evitar decisão concorrente sobre estado antigo.
5. Cada decisão registra evento cifrado e auditado. Motivos/referências não aparecem no log ou na impressão pública de idempotência. O titular consulta categoria, fundamento e datas de suas retenções vigentes; não recebe dados de outro titular ou nota interna de terceiros.
6. Ao liberar/vencer, a evidência guardada somente pelo caso fica elegível à limpeza em lotes. Uma base financeira própria ainda vigente é respeitada.

Categorias: identificação do perfil, documento enviado, rota, identificação mínima contratual, identificação de veículo, correção de e-mail, decisão de conta, protocolo de direitos, manifestação promocional, recibo jurídico e notificação. Tipos/pertencimento são derivados pelo servidor; não se recebe nome de tabela, caminho ou expressão SQL do cliente. Não se criou retenção genérica de todo backup ou banco.

Um registro legado sem recurso específico pode ser revisado/liberado, mas não autoriza download arbitrário de todos os arquivos. Para delimitar seu escopo sem desproteção intermediária, criar as retenções específicas enquanto a anterior ainda vigora e só então liberar a antiga. A primeira revisão conserva uma fotografia cifrada do estado legado conhecido, marcada `legacy_snapshot`; não inventa um evento de criação nem uma data de auditoria antiga.

## APIs e permissões

| Operação | Rota | Proteção |
| --- | --- | --- |
| Fila e revisão vencida | `GET /api/v1/admin/privacy/holds` | Administrador; filtros/paginação estritos; somente metadados |
| Criar | `POST /api/v1/admin/privacy/holds` | Administrador, senha novamente, CSRF/origem, chave idempotente |
| Revisar/liberar | `POST /api/v1/admin/privacy/holds/{id}/review` | Mesmas proteções e revisão esperada; sem reativação de retenção encerrada |
| Ler justificativa/histórico | `POST /api/v1/admin/privacy/holds/{id}/evidence` | Senha novamente e leitura auditada |
| Acessar evidência retida | `POST /api/v1/admin/privacy/holds/{id}/data` | Retenção vigente e específica, senha novamente, arquivo/JSON direto, `no-store`, download anexo e auditoria |
| Retenções do titular | `GET /api/v1/privacy/retentions` | Conta derivada exclusivamente da sessão; sem motivos/referências internas |

O download de dados consulta arquivo privado específico ou cópias pertinentes já existentes no cofre. Não materializa exportação permanente no servidor nem cria URL pública. Recurso ativo sem cópia no cofre continua no fluxo administrativo próprio; não se faz extração excessiva só para preencher o painel. Quem baixar evidência para um caso continua responsável pela segurança e retenção dessa cópia fora da aplicação.

A exportação do titular inclui as cópias próprias de identificação/perfil/veículo ainda existentes, com lista explícita de campos permitidos e conteúdo JSON legível. Não inclui justificativa/referência interna do caso ou dados de outra conta. Dados de outras categorias/segurança que exigem análise de terceiros continuam no protocolo assistido, sem supor que a categoria de cofre autoriza revelar todo seu conteúdo.

## Concorrência e exclusão

`PrivacyRetentionLock` coordena decisões, expiração do cofre, remoção do perfil e eliminação física entre processos API/worker. A trava é limitada às tarefas de privacidade; endpoints financeiros não passam a adquiri-la. A espera tem limite e uma falha conserva evidência para retentativa. É necessário acompanhar contenção no host de produção.

`PrivacyFileErasure` confere a retenção novamente dentro da coordenação, no instante de apagar, sem confiar na lista de candidatos antiga. Se a retenção confirma primeiro, os bytes ficam protegidos; se a remoção começa primeiro, a tentativa posterior recebe indisponibilidade. Falha de remoção não vira confirmação falsa de conclusão. Triggers por instrução fazem as alterações de retenção adquirir a mesma coordenação antes das travas de linha; isso também cobre manutenção SQL legítima. A proteção não substitui controle de acesso contra administrador do banco/sistema de arquivos.

Perfil e notificação sob retenção deixam a operação visível e vão ao cofre cifrado. `privacy_retained_records.hold_only` distingue evidência conservada apenas pelo caso de uma base autônoma. Liberação não conserva por engano a primeira até uma data antiga; também não apaga a segunda prematuramente. Rota com finalidade autônoma mantém seu prazo próprio sem inflá-lo pelo vencimento do hold; o hold impede o expurgo enquanto necessário. A retenção de um recibo, consentimento ou aviso não congela todos os outros registros do titular.

## Evidências e dados novos

V35 acrescenta revisão, revisão realizada e retenção de metadados ao hold; histórico `privacy_hold_events` com associação composta a titular/hold; coordenação no banco e proteção do histórico contra edição comum. Rotação AES altera somente a representação cifrada, sem substituir decisão/data. Expiração do histórico/metadados ocorre em lotes depois da retenção de defesa e do encerramento do hold; nenhum prazo é infinito por padrão.

Dados novos: justificativa/referência cifradas, ator administrativo, recurso, base, revisão e vigência. Acesso: administração autorizada com auditoria e informações mínimas ao próprio titular. Compartilhamento: nenhum provedor novo. Histórico e notas exigem minimização; não inserir senha, documento completo ou narrativa de terceiro sem necessidade. O termo genérico de cinco anos precisa ser validado/reduzido conforme a finalidade real antes do lançamento.

## Testes e pendências

- `privacy-holds-foundation.log`: cinco unitários e 29 integrações aprovados na fundação inicial. Não prova sozinho os endpoints novos.
- `privacy-holds-http-first.log`: falhou porque faltou registrar as rotas no validador estrito de contratos e nas regras de autorização; não contar como aprovação. A coordenação por SQL e a rotação já passaram nessa rodada.
- `privacy-holds-http-contracts.log`: cinco unitários + 25 integrações aprovados depois do registro, incluindo nove cenários novos de retenção e catálogo HTTP completo.
- `privacy-holds-scope-data-final.log`: cinco unitários + 57 integrações aprovados após download privado, isolamento de recibos/avisos e histórico legado: 12 retenções, cinco rotação, 16 direitos, seis schema, sete compatibilidade e 11 identidade/catálogo. Rodadas se sobrepõem; não somar como casos únicos.
- Há testes concorrentes reais com PostgreSQL, travas observadas no servidor e barreiras de I/O, nas duas ordens de criação/eliminação. Não são apenas mocks da lista de candidatos.
- `privacy-holds-financial-restore-final.log`: 18 unitários + 140 integrações aprovados antes da captura inicial de recursos mutáveis. Inclui 61 cenários Mercado Pago simulados, autenticação/e-mail, jobs/worker, identidade, retenção e restauração física. A seleção escrita `FilesHttpIT` não corresponde a uma classe; os oito casos reais de `FileHttpIT` foram executados na rodada seguinte, sem atribuí-los a essa contagem.
- `privacy-holds-snapshot-file-final.log`: cinco unitários + 37 integrações aprovados após a captura: 13 retenções, oito arquivos e 16 direitos. Uma seleção inexistente de documentos jurídicos não foi contada; a rodada seguinte usa `LegalHttpIT`, com seus 13 casos reais.
- `privacy-holds-export-legal-final.log`: cinco unitários + 42 integrações aprovados após exportação das cópias e lista de campos permitidos. Verifica preservação da data antiga no cofre, correção da atual por API e ambas na exportação própria, sem nota interna. Os 13 casos de documentos jurídicos também passaram com a minuta atualizada.
- O wrapper Maven passou a recusar seletores literais de classes inexistentes mesmo dentro de listas mistas. A rejeição foi exercitada antes de iniciar qualquer Maven; a execução com nomes válidos passou. Não se apresentou a soma de rodadas repetidas como um total único.

Atualização de 23/10: telas administrativas integradas e inspecionadas em desktop/celular; AP-01 das filas corrigido e considerado resolvido por revisor independente. Ver `Interface_Privacidade.md` para o alcance do fechamento. A tela própria do titular ainda exige sua jornada renderizada completa.

Ainda necessários: operação e autorização humanas dos casos; completar os ensaios de recuperação de todas as categorias/dependências e o procedimento assistido; recuperação externa do controle/chaves; expiração financeira/logs/estatísticas; minimização restante; publicação e red teams finais. O complemento de 25/10 implementa controle próprio de holds além do diário de exclusão, sem presumir o resultado de preparações incertas. Avisos finais já foram implementados na V36, com limites descritos em `Avisos_Encerramento.md`; sua revisão visual final não é comprovada pela inspeção administrativa. **Esta camada ainda não está pronta para produção.**
