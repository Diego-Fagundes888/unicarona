# Matriz dos 44 requisitos — controle de conclusão

Checkpoint de desenvolvimento atualizado em 26/10/2026. Esta matriz distingue implementação
na fonte, evidência delimitada e trabalho ainda necessário. Não é um parecer
jurídico nem declara a operação pronta. A demonstração protegida continua no
schema V26 e no frontend congelado; a fonte contém V27–V46 e interfaces ainda não
publicadas em conjunto.

Complemento de minimização V46: [dados acadêmicos entre participantes](Dados_Academicos_Entre_Participantes.md)
trata os itens 3–5, 28, 35, 38, 40–43. Campos exatos ficam restritos ao titular nas
consultas de oferta, inclusive aceita/paga; compatibilidade mensal permanece.
Privacidade r11 em draft, r10 preservada, sem novo schema. Não conclui a revisão
de IDs, históricos, todas as projeções ou a publicação. QA visual adiado.

Complemento sem nova migration: [recuperação nativa com WAL cifrado](Recuperacao_Transacional_PITR.md),
pertinente aos itens 14–15, 20, 33–34 e 40–44. Prova delimitada de eventos
posteriores à base, valores sem duplicação e controles mais recentes antes da
prontidão; falha fechada quando o WAL falta ou é adulterado. Não encerra esses
itens: operação contínua, recuperação fora do host, RPO/RTO, retenções restantes,
publicação e revisão integral continuam pendentes.

V46 trata [PIN de embarque e sua expiração](PIN_Embarque_Retencao.md), nos itens
3, 14–15, 25, 33–34 e 40–43. Não conclui esses itens: preserva valores e a prova
do embarque, mas o replay de eventos posteriores ao backup continua pendente.
Privacidade r10 é minuta, com r9 preservada; não altera a demonstração protegida.

V45 acrescenta um ciclo financeiro limitado: [tentativa falha e neutra por conta](Transferencias_Falhas_Retencao.md).
Não conclui os itens 14–16, 20, 29, 33–34 ou 44: pagamentos recebidos, contratos
realizados, demais classes financeiras e agregação anônima permanecem pendentes.
Privacidade r9 é minuta; r8 está preservada, sem publicação da demonstração.

Responsável confirmado: Diego Alves Fagundes, pessoa física, Novo Gama/GO.
Contato de privacidade: privacy@example.invalid A configuração compartilhada
`app.env` contém esses quatro campos; o launcher protegido os combina com o
ambiente de sandbox. Não publicar CPF, endereço ou empresa por inferência.

“Implementado na fonte” não significa “publicado”, “revisado integralmente” ou
“juridicamente validado”. Os documentos citados contêm testes e limites de cada
etapa; contagens de rodadas sobrepostas não devem ser somadas.

V44: [cotações abandonadas e checkouts nunca enviados](Checkouts_Nao_Enviados_Retencao.md)
trata expurgo de pedidos, pagamentos locais, tentativas falhas nunca iniciadas,
eventos classificados e referências em cache/avisos, respeitando finalidade e
preservações. Privacidade r8 em minuta, r7 preservada. Não encerra a retenção de
recebimentos, contratos cumpridos, ledger ou demais famílias financeiras.

Complemento V43: [identificação mínima e obrigações financeiras](Identificacao_Obrigacoes_Financeiras.md)
trata os requisitos 14–15, 20, 33–34, 40–43: finalidade concreta protege a cópia
contratual, liquidação informa o limiar de defesa e restauração antiga não prova
quitação. Privacidade r7 em minuta, com r6 preservada. Não encerra retenção das
próprias tabelas financeiras/contratuais, recuperação de dinheiro, anonimização
ou a auditoria final. Conferência visual continua adiada pelo usuário.

Complemento 25/10 verificado em escopo delimitado: [confirmação de supressões](Confirmacao_Supressoes.md)
passa a exigir prova de conclusão de conta/arquivo conhecido após commit, além
do preparo; cópias anteriores à conclusão e horizonte do banco participam da
expiração. Replay confere conteúdos dependentes mesmo com `deleted_at` já
preenchido e não renova cópias autônomas existentes. Não conclui a retenção
financeira/arquivos ligados, a recuperação externa ou o objetivo integral.

| Nº | Requisito | Implementação/evidência existente | O que impede encerrar o requisito |
| --- | --- | --- | --- |
| 1 | Examinar o sistema real | Inventário com tabelas, fornecedores, projeções e percursos; auditorias de autenticação, financeiro, arquivos e privacidade | Revisão cruzada final de todos os campos, caches, logs e integrações após as mudanças |
| 2 | Legislação brasileira aplicável | Fontes oficiais e limites de interpretação em `Decisoes_Juridicas.md` | Validar modelo de transporte/intermediação, obrigações comerciais, tributação e documentos com profissional brasileiro |
| 3 | Mapa de dados pessoais | `Inventario_Dados.md` identifica coleta, finalidade, armazenamento, acesso, compartilhamento e lacunas; campos estruturados de localização sem finalidade tratados em `Retencao_Localizacao.md` | Fechar texto livre de localização/agenda, campos acadêmicos/IDs e tabelas financeiras/de segurança |
| 4 | Bases legais por finalidade | Inventário e minutas separam contrato, obrigação, defesa, segurança e marketing | Conferir cada prazo/base contra o expurgo realmente implementado; não atribuir consentimento a todo tratamento |
| 5 | Política específica de privacidade | Minuta `privacy.md`, contexto do controlador/fornecedores derivado de configuração e leitor versionado | Harmonização final, retenções ainda pendentes, publicação coordenada e validação jurídica |
| 6 | Termos de Uso completos | `terms.md` e políticas relacionadas no catálogo de seis documentos | Matriz cláusula/comportamento e revisão jurídica final, antes de publicação |
| 7 | Proteção legítima do responsável | Alternativas jurídicas e decisões registradas em `Decisoes_Juridicas.md` | Red team jurídico e análise profissional de cláusulas controversas |
| 8 | Responsabilidade | Minutas distinguem conduta dos usuários, falhas próprias, terceiros e direitos preservados | Validar limites conforme CDC e modelo operacional efetivo; não afirmar exclusão absoluta de responsabilidade |
| 9 | Intermediação | Textos de participação e motorista descrevem a função efetiva da plataforma e repasses não habilitados | Conferir comunicação comercial e enquadramento da operação remunerada/recorrente |
| 10 | Encerramento em Configurações | Centro e fluxo dedicado; dez rotas próprias renderizadas desktop/mobile e parecer independente ship delimitado; `Interface_Privacidade.md` | Publicação coordenada e demais estados jurídicos/operacionais fora do parecer visual |
| 11 | Fluxo completo de exclusão | Consequências, resumo, senha, confirmação, protocolo; solicitação/cancelamento reais sintéticos e comprovante cancelado persistente; processamento testado no servidor | Revisão final dos avisos e comprovação do pacote publicado; parecer próprio não certifica expurgo integral |
| 12 | Confirmação de segurança | Senha, CSRF/origem, sessão, vínculo e idempotência em operações sensíveis; exportação direta | Red team integrado de sessão, autorização e recuperação, sem enfraquecer gates existentes |
| 13 | Janela de reversão | Sete dias limitados por configuração; jornada sintética agendada/cancelada conferida visualmente, sem retomada automática de preferências | Consistência de todos os avisos e publicação |
| 14 | Apagar/reter/pseudonimizar | Perfil/arquivos, cofre, diário e holds; V39 trata bytes sem metadados; V40 retira cópias ligadas e metadados de arquivos; complemento26/10 minimiza motivo/revisor, candidatura encerrada e veículo sem uso com preservação por caso (`Metadados_Candidatura_Motorista.md`) | Expiração financeira/contratual e de logs, inclusive prova mínima de aprovação ligada a esses registros; volumes/categorias e recuperação externa; consolidar pacote e conferir UI do complemento |
| 15 | Política objetiva de retenção | Prazos/jobs para tokens, sessões, preferências, protocolos, avisos, cofre, uploads, anexos, metadados de arquivos e candidaturas; V42 acrescenta seis meses de calendário para eventos operacionais de segurança classificados, com preservação específica e replay por data original (`Logs_Seguranca_Retencao.md`) | Fechar financeiro/contratos/elegibilidade e demais famílias de auditoria/logs; validar necessidade de coleta e prazos por finalidade; publicação e QA visual do complemento |
| 16 | Estatísticas anônimas | Nome reduzido e chaves técnicas são reconhecidos como dados pessoais/pseudônimos | Implementar/validar agregação verdadeiramente anônima; não apresentar exclusão de nome como anonimização |
| 17 | Estados distintos | Logout, desativação, suspensão, banimento, exclusão do titular e encerramento pela plataforma implementados e testados | Conferir apresentação e transições na interface integral |
| 18 | Encerramento pela plataforma | Motivo comunicado e interno separados, autenticação adicional, estados esperados e recurso por protocolo; interface administrativa integrada | Operação humana, revisão de proporcionalidade e jornada completa do titular; o veredito AP-01 não certifica decisões jurídicas |
| 19 | Recriação de banidos | Restrição HMAC de e-mail normalizado, prazo finito e coordenação com cadastro/exclusão; sem base secreta de documentos | Procedimento de rotação/recuperação e divulgação honesta da limitação a outro endereço |
| 20 | Pendências financeiras | Operações em curso adiam o processamento; saldos são apresentados separadamente e disputas não exigem manter o perfil público. Ledger preservado; resumo não promete quitação. V43 conserva identificação mínima enquanto a obrigação concreta permanece | Expurgo do próprio histórico sem romper conciliação/obrigações, recuperação financeira completa e operação de resolução dos valores/casos |
| 21 | Aceites separados | Cadastro renderizado desktop/mobile, guardas de Termos/ciência desmarcados e cinco recibos de conta sintética conferidos; telefone opcional; revisão independente ship delimitada | Atualização material do catálogo, confirmação recebida e publicação coordenada ainda exigem suas próprias evidências |
| 22 | Evidência de aceite | Recibos por documento, versão/hash, escopo e data; nenhuma leitura gera aceite | Conferir publicação, exportação e retenção após a revisão final |
| 23 | Versionamento | Documentos imutáveis, publicação explícita, avisos e gate de mudança material; sem aceite retroativo | Publicar pacote coerente e executar fluxo de atualização de ponta a ponta |
| 24 | Centro de privacidade | Dez rotas próprias revisadas em 54 capturas; leitor/cadastro com parecer próprio em 53 capturas e suplemento do contato configurado, sem somar imagens como casos únicos do produto | Publicação coordenada, novo aceite material, confirmação de e-mail e demais jornadas continuam pendentes |
| 25 | Acesso/exportação | ZIP autenticado com senha/Blob temporário, sem URL pública; V42 filtra eventos de segurança vencidos e entrega identificador do próprio evento atual e projeção permitida da cópia preservada, sem metadados internos | Auditoria completa dos domínios exportáveis e operação publicada; a amostra não comprova todos os campos |
| 26 | Correção | E-mail com confirmação/senha/expiração/sessões/avisos no servidor; pedido/cancelamento e correção/restauração de nascimento conferidos na UI;26/10: troca confirmada por HTTP após resposta perdida do cartão conserva solicitação original cifrada, com teste da ramificação de produção em fixture local (`Comando_Cartao_Contato_Original.md`) | Concluir jornada renderizada pelo link recebido, avisos e tratamento operacional dos demais pedidos |
| 27 | Localização | Revelação por fase; checkout com revisão atual sob trava e CF-01 resolvido; API recusa seis campos estruturados sem finalidade, edição não os copia e limpeza trata legados com preservações; guarda agora reaplica holds confirmados antes dessa limpeza; `Retencao_Localizacao.md` e `Recuperacao_Retencoes.md` | Retenção de encontro/notas/texto livre/agenda; ensaios finais de categorias/dependências, recuperação externa e publicação coordenada |
| 28 | Dados entre participantes | Nome reduzido, telefone/foto/placa por finalidade operacional; foto antiga perde autorização; complemento V46 não divulga curso/período/turma exatos em avulsa/mensal à contraparte, mantendo compatibilidade e acesso próprio | Revisão final de IDs, demais campos/projeções/caches e inferência por compatibilidade; QA visual e publicação coordenada |
| 29 | Pagamentos nos documentos | Mercado Pago, testes, comissão, cancelamento/crédito, contestação e repasse indisponível descritos | Harmonização com a integração final e regras reais de recebimento/repasse; revisão jurídica |
| 30 | Cookies/analytics | Tecnologias locais/sessão inventariadas; não acrescentado banner sem finalidade | Reconferir todas as dependências e comportamento efetivo antes da publicação |
| 31 | Comunicações | Marketing separado; avisos essenciais cifrados com validade, supressão e retentativa; conclusão não depende do provedor | QA dos modelos sintéticos, retenção final dos logs de entrega e operação fora deste computador |
| 32 | Idade mínima | Elegibilidade adulta e correção de nascimento testadas; sem presumir maioridade universitária | Conferir textos/operacionalização e validação profissional se o escopo passar a permitir menores |
| 33 | Segurança | NTFS, cifras/autorização, ponte V38 e replay; V39 liga observação, upload/commit e supressão de órfãos; worker interrompe novas tarefas/e-mail quando perde prontidão. Restore físico e casos de falha aprovados em escopo delimitado | Recuperação externa, pacote operacional, volumes/categorias e red team final; revisar conclusão/expiração dos tipos anteriores de supressão; nenhuma auditoria pontual equivale a segurança integral |
| 34 | Logs e evidências | Aceites, decisões, acesso administrativo, exclusão e eventos registrados; V42 mantém proibição de editar audit_logs e permite só expurgo vencido da família classificada; cópia de caso autenticada/independente, sem reconstruir histórico comum; lote de 1.201 eventos testado | Retenção das demais famílias de auditoria/logs técnicos, prova financeira/contratual/elegibilidade e minimização final; validar enquadramento de registros legais de acesso e operação real |
| 35 | Copy clara | Estados próprios/administrativos revisados; leitor/cadastro aprovados no escopo delimitado, incluindo erro de versão, tabela móvel, telefone opcional e contato configurado | Revisão de avisos de mudança, confirmação recebida de e-mail e demais jornadas fora dos pareceres existentes |
| 36 | Sem dark patterns | Exclusão acessível, ações dedicadas, volta visível, caixas desmarcadas, marketing separado | Verificação integral dos caminhos e acessibilidade/foco em desktop/celular |
| 37 | Treze temas documentais | Seis documentos consolidam termos, privacidade, motorista, participação/cancelamento/mensal, pagamentos/reembolso/repasses e segurança/encerramento | Conferir cobertura dos treze temas sem duplicação e publicar versões aprovadas |
| 38 | Consistência documental | `Consistencia_Documentos.md` cruza as seis minutas atuais por tema, comportamento e evidência; a regra de saldo/disputa versus operação em curso foi corrigida nesta matriz | Repetir confronto após concluir retenções/minimização/recuperação; red team jurídico final, jornadas e pacote publicado ainda necessários |
| 39 | Red team jurídico | Riscos e escolhas iniciais documentados | Rodada final explícita sob perspectivas de passageiro, motorista, consumidor, ANPD e advogado adversário |
| 40 | Red team técnico | Casos de IDOR, foto antiga, dados excluídos/restaurados, exportação e recadastro testados em etapas | Matriz final de ataques entre todos os domínios e estados publicados |
| 41 | Testes automatizados | Suítes de aceites, fechamento, obrigações, retenção, exportação, preferências, identidade e restore; logs por etapa | Mapear os doze cenários exigidos a casos finais e conferir lacunas sem somar execuções repetidas |
| 42 | Implementar além dos documentos | Migrations V27–V46, serviços/controllers/jobs, gates, cifras, contratos e interfaces na fonte | Concluir frentes técnicas pendentes e publicar de forma coordenada |
| 43 | Preservar o que funciona | Regressões financeiras/autenticação/caronas/contratos/arquivos em PostgreSQL descartável; demo V26 isolada | Regressão final do pacote completo e plano de implantação/retorno sem restaurar dados eliminados |
| 44 | Critério final e relatório | Inventário, decisões e relatórios delimitados de cada etapa | Fechar esta matriz, riscos residuais, consistência, operação real e os dezesseis entregáveis finais; objetivo permanece ativo |

## Ordem de fechamento restante

V41 de 26/10: [dados transitórios do checkout](Dados_Transitorios_Checkout.md)
separa contato/token de criação, QR/autenticação e prova financeira. Inputs
dispensáveis são retirados, comandos incertos são preservados para conciliação
e a tela encerra a coleta ao passar o prazo sem inventar cancelamento financeiro.
Privacidade r5 em draft, com r4 preservada; Motorista r3 permanece. Registros
financeiros/contratuais/logs continuam com fechamento próprio pendente. Ver
evidência final e limites no relatório; QA visual aguarda a liberação já pedida.

Complemento de 26/10 validado no backend, com QA visual pendente: [metadados de candidatura](Metadados_Candidatura_Motorista.md)
separa narrativa/revisor de 90 dias, remoção de registros sem finalidade após
365 dias, prova mínima ainda usada e cópia privada por caso. Sem migration nova;
API/worker devem ser publicados juntos. Privacidade r4 e Motorista r3 em draft
substituem as revisões r3/r2 preservadas na história de fontes. Financeiro,
contratos e logs continuam exigindo seu próprio fechamento; nenhuma captura ou
rodada parcial deste complemento os certifica.

1. Fechamento administrativo realizado: AP-01 resolvido, veredito independente
   limitado à correção e comparação documental com o DS existente. Preservar a
   distinção entre esse fechamento e a validação completa do produto.
2. Jornadas próprias revisadas: dez rotas, 54 capturas, cinco correções e parecer
   independente ship delimitado; comparação documental final concluída. Seguem
   novo aceite material, confirmação pelo link de e-mail e avisos nos viewports
   exigidos; confirmar encontro/notas antes do pagamento. Leitor/cadastro tiveram
   fechamento próprio ship em 53 capturas válidas, seis documentos completos,
   quatro correções e comparação documental final; ver
   `Leitor_Cadastro_Verificacao.md`. Essa etapa não certifica as jornadas restantes.
   Checkout teve fechamento delimitado em 24/10: encontro/notas autorizados,
   preço contratado por trecho e bloqueio de primeira cobrança com revisão
   desatualizada; 15 recapturas e veredito ship apenas de CF-01 resolvido após o
   parecer completo. Regressão nomeada 11 unitários + 263 HTTP e 46 JS passou;
   contrato OpenAPI adicional aprovado em rodada de quatro unitários e 24 HTTP
   sobrepostos, sem somar contagens. Não é certificado do objetivo inteiro.
3. Completar ensaios por categoria/dependência do replay posterior ao snapshot e
   fechar recuperação externa, metadados de candidaturas/decisões, retenção financeira/de
   segurança e agregação anônima. Captura, blobs e replay locais já estão ligados;
   a prova delimitada não encerra a operação de recuperação nem os 44 requisitos.
   V39 acrescenta a limpeza de bytes sem metadados, sem inventar titular; completar
   volumes/limites. O complemento de confirmação dos tipos anteriores foi testado
   separadamente, com 63 unitários/86 integrações na rodada ampla e 32/37 após o
   último ajuste, sem somar sobreposições; isso não certifica a política integral.
   A V40 acrescenta prazos dos anexos ligados, metadados de arquivos eliminados e
   datas reais de upload no controle de preservação. Na conclusão inicial da V40, as minutas eram
   Privacidade r3 e Motorista r2 em `draft`, com fontes anteriores preservadas;
   sem publicação ou aceite retroativo. Conferir seu conteúdo e as jornadas antes
   do fechamento final. Ver `Documentos_Motorista_Retencao.md`.
4. Cruzar as seis minutas com o comportamento final; executar red teams jurídicos
   e técnicos e mapear os doze testes exigidos.
5. Validar a operação com profissional brasileiro, preparar publicação conjunta,
   ensaiar restauração segura e produzir os dezesseis entregáveis. A publicação
   das minutas exige decisão explícita, sem simular aceites de pessoas existentes.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Os prazos e escolhas
operacionais propostos não são apresentados como obrigações universais da LGPD.
