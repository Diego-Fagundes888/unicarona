# Verificação do backend conectado

> Registro histórico anterior à retirada do backend TypeScript.
> Para a estrutura atual, consulte [Retirada_Backend_TypeScript.md](Retirada_Backend_TypeScript.md).

> Registro das versões e verificações descritas abaixo. Em 16/10/2026, a implementação Java de novas liquidações de “Sem aula” foi revisada para crédito integral ao passageiro. As menções históricas à retenção não representam a regra nova. Ver [regra revisada e evidência atual](privacy/Sem_Aula_Regra_Revisada.md) e [situação da implantação](privacy/Plano_Implementacao.md).

Atualizado em 02/10/2026. O aceite completo permanece aberto pelas validações externas registradas. A [auditoria dos 50 itens](Backend_Auditoria.md) relaciona cada requisito à implementação, provas e limites.

## Evidências executadas

- `npm run build`: compilação TypeScript passou.
- `npm run test:unit`: cinco testes passaram, sobre preços, centavos, desconto proporcional, limites de entrada/idade e proteção do armazenamento privado contra caminhos públicos e junctions no Windows.
- `npm test`: 79 testes passaram com API e PostgreSQL real. Incluem autorização, tokens, uploads, ofertas, mensalidade, cancelamentos, PIN, liquidação, ocorrências, transferências, carência e administração. Dez confirmações pagas concorrem pela última vaga: exatamente uma reserva; os demais recebimentos viram crédito integral. Incluem alterações concorrentes de parâmetros, calendário durante checkout, suspensão/restauração, decisões financeiras divergentes, preservação da verificação acadêmica e quotas concorrentes de arquivos. SQL direto não consegue alterar valores congelados, reabrir reservas concluídas, apagar parcelas, aprovar recebimento/transferência sem ledger correspondente ou modificar dinheiro já recebido.
- `npm run test:e2e`: dez jornadas de navegador passaram, usando servidor real e banco isolado. A primeira cria conta, confirma e-mail por mensagem do worker, entra, consulta perfil, redefine senha, verifica revogação da sessão e entra com a nova senha. Outra usa sessões separadas de motorista e passageiro para publicação, busca, solicitação, aceite, checkout, evento assinado do gateway local, reserva, PIN, conclusão e confirmação final. Consulta o ledger para comprovar saldo disponível somente após liquidação. Também publica uma rotina mensal, verifica o aceite específico mesmo com avulsas automáticas, paga e confirma a agenda inteira, e desativa a renovação preservando reservas pagas. Dois relatos sobre o mesmo trecho exigem confirmação explícita da decisão divergente na administração, preservando o dinheiro já liberado. A terceira também esgota oito entregas de e-mail, consulta a fila administrativa sem destinatários/links, retoma com justificativa e confirma a nona tentativa. Envia documentos privados de um novo motorista, obtém aprovação administrativa, altera parâmetros, cadastra e desativa calendário de turma, suspende/restaura acesso e verifica o acesso à segurança durante a revisão. A quarta verifica busca pública passando pelo login, perda da resposta de uma solicitação já gravada, repetição com a mesma chave sem novo pedido, falha do provedor e checkout retomado após recarregar, pagamento, cancelamento pelo motorista, crédito, carteira offline com recuperação e busca vazia. Filtros permanecem ao recarregar; retorno de login para origem externa é rejeitado. A quinta cria 31 ofertas, pedidos e avisos para conferir páginas seguintes, filtro de pedidos pendentes antes da paginação, recarga, histórico do navegador e isolamento entre usuários.
- `npm run test:migrations`: criou um banco temporário vazio, aplicou as 13 migrations e repetiu a execução. Seeds básicos e cenários completos foram executados duas vezes pelo papel da aplicação sem superusuário, sem duplicar usuários, reservas, contratos, recebimentos ou ledger. Somente o banco temporário foi removido ao terminar.
- `npm run api:export`: OpenAPI atualizado, incluindo os endpoints conectados e o gateway local de testes.
- `npm run test:performance`: passou com 5 mil estudantes, 20 motoristas, 3 mil
  ofertas, 3 mil avisos e 10 reservas confirmadas pelo gateway local. Busca de
  30 e 100 resultados executou quatro consultas em ambos os casos, incluindo
  autenticação e limite de acesso. Na medição de 02/10/2026 às 18:24:55 UTC, p95 foi
  111,09 ms no login, 31,86 ms na busca de 30, 7,89 ms em detalhes, 46,24 ms
  na solicitação, 50,22 ms na confirmação assinada e 10,91 ms nos avisos. Cinco
  buscas simultâneas completaram com p95 de 122,75 ms e 20 consultas no total. O relatório inclui
  `EXPLAIN ANALYZE`; não afirma uso de todos os índices nem capacidade de produção.

As páginas principais não carregam mais o domínio em memória nem a troca de identidade da demonstração. A identidade vem da sessão. A página de pagamento local é explicitamente de testes e só consegue gerar eventos para cobrança própria. Seu endpoint não é registrado em produção.

A suíte específica `backend/tests/admin-jobs.test.ts` passou em cinco testes (incluindo o agrupador): permissões/CSRF, minimização de dados, retomada concorrente/idempotente com auditoria, paginação e envio de transferência esgotado. O saldo permanece reservado após a retomada e só muda uma vez pelo evento assinado. A execução completa mais recente passou em **79 testes**, sem falhas ou skips, já incluindo essa suíte, readiness, fotos privadas, throttle e as corridas de login/reset e refresh/logout.

As dez jornadas E2E passaram em execução conjunta. Além das cinco jornadas
descritas acima, há cobertura de respostas reais atrasadas em autenticação,
controles acadêmicos/teclado/rascunhos, layout, uso mensal/renovação/transferências
e carência. O cadastro só envia uma requisição ao concluir os campos válidos;
catálogos vêm da API. Respostas atrasadas não sobrescrevem uma nova navegação.
Carência retém toda a agenda sem reserva de embarque; renovação exige novo aceite
e pagamento e remove os holds. Fixtures de contratos em período anterior permitem
testar vencimento sem alterar o relógio do computador. Não representam a passagem
de um mês real durante o ensaio.

O mensal exercita ausência com vaga e valor preservados, disponibilidade final,
Sem aula com 100% ao app e sem comissão, cancelamento pelo motorista com crédito,
devolução/saque por worker e resultado assinado repetido, além de renovação paga.
A reconciliação final fica zero. A reserva também mostra foto privada confirmada
e detalhes para compartilhar, sem PIN/telefone/e-mail e com foco restaurado.

Após a última inspeção visual, o PIN foi removido dos DTOs de trechos encerrados
ou Sem aula. A suíte de 79 testes e a repetição conjunta das dez jornadas E2E
passaram com esse ajuste, sem falhas, skips ou exceções JavaScript nas jornadas.

## Falhas verificadas

Onze cenários em `backend/tests/failures.test.ts` (12 testes contando o agrupador)
verificam banco offline, falta de conexões do pool, statement timeout, desconexão
de um cliente ocioso, lock timeout com rollback da chave de idempotência,
conexão/deadline do pagamento, retry de webhook durável após rollback,
timeout de transferência sem devolver saldo prematuramente, retry de e-mail,
callback atrasado de lease antigo e limite de oito entregas antes de `dead`.
Erros HTTP são sanitizados; os retries conferem reservas e ledger reais.

## Verificação visual parcial

Capturas em `docs/previews/connected/`: início e perfil, nas larguras 1440 e 390 px; calendário administrativo em 1280 e 390 px; apuração de ocorrências em 1280 e 390 px e detalhe de parcela no celular. Foram abertas e inspecionadas. O teste de celular verificou ausência de overflow horizontal. As jornadas executadas não tiveram exceções JavaScript.

Também foram abertas e inspecionadas as capturas de carregamento da busca,
resposta perdida, cobrança com opção de retomar, carteira offline e busca vazia
em 390 px. O E2E de falhas bloqueia o iframe externo de mapas para que o ensaio
local não dependa do tempo de resposta desse terceiro; não comprova sua operação.

A inspeção encontrou e corrigiu o aviso antigo de demonstração no rodapé, repetição do curso no resumo acadêmico e exposição do estado técnico `self_reported`. O mapa externo não carregou nas capturas; há texto explicativo e link para abri-lo. A disponibilidade desse serviço externo não foi comprovada.

Na administração, a aba selecionada permanece visível ao mudar para largura de
celular. Datas têm descrição textual em português além do calendário nativo,
cujo formato depende do navegador/sistema. Estados e tipos de ocorrência têm
nomes compreensíveis. A apuração mostra valores congelados e relatos relacionados,
com navegação por páginas de parcelas. A fila administrativa foi inspecionada em 1280 e 390 px, com estado de revisão, justificativa e retomada; não mostra destinatários, links ou conteúdo das tarefas.

Essas capturas não comprovam todas as telas, todos os breakpoints, leitor de tela, zoom, todos os estados offline ou regressões de imagem. Os testes antigos do protótipo não são evidência do sistema conectado.

O ensaio conectado de layout verifica 320, 390, 768, 900, 1024 e 1440 px no início,
posição do mapa, navegação e agendamento. O mapa saiu da grade por uma tag de
fechamento excedente; corrigida e protegida por uma verificação estrutural. Oito
views principais foram capturadas a 320/1440 px com texto longo, estado vazio e
verificação de escape. Início 390/1440 e perfil/carteira 320 foram abertos e
inspecionados, assim como mensal Sem aula/carência e cadastro acadêmico 320.
Captura integral do mensal renovado tem agenda longa; não prova legibilidade de
cada linha quando reduzida ao preview. A confirmação funcional usa elementos DOM.

## Trabalho ainda necessário

- Executar Docker/Compose com Engine disponível. Os arquivos presentes não comprovam containers funcionando.
- Homologar as integrações externas para uso comercial; pagamento real continua aguardando escolha de provedor.
- Definir retenção/limpeza/anonimização sem apagar evidências. Estrelas permanecem fora do MVP aprovado; a tabela modelada não ativa essa funcionalidade.
- Leitor de tela, zoom 200%, comparação automatizada de pixels e carga de produção são validações adicionais não executadas. A cobertura atual está delimitada pelas jornadas e capturas acima.

As listas principais de ofertas, pedidos, reservas, contratos, avisos,
transferências, relatos, bloqueios e análises administrativas usam páginas reais;
permanece o limite documentado de offset 5000. Catálogos e métricas usam DTO
explícito; OpenAPI descreve origem, CSRF, idempotência e assinatura. O código
demonstrativo foi movido para `tests/legacy`; as entradas antigas executam suítes
conectadas. A revisão de segurança/logs e os limites por requisito estão na auditoria.

Depois da correção de refresh/logout, passaram novamente os 79 testes de
API/banco e as dez jornadas E2E. A medição acima foi repetida após essas mudanças.
O servidor de desenvolvimento foi reiniciado com o código atualizado e respondeu
`health=ok` e `readiness=ready`. Referências de assets foram versionadas para
evitar o uso de JS/CSS anteriores pelo cache do navegador.

As 13 migrations e o script de inicialização Docker já usam LF. O Git agora
preserva esse formato por `.gitattributes`, sem modificar migrations aplicadas
ou checksums. A verificação HTTP dos 11 arquivos/referências de assets atualizados
retornou 200.

Na continuação da auditoria, o Compose foi parseado com a biblioteca YAML
instalada no projeto, incluindo os anchors. Passaram a sintaxe YAML, a ordem
db saudável → migrate → seed → API/worker, existência dos entrypoints compilados,
origem/porta local e compartilhamento do armazenamento privado. Os caminhos
convencionais do Docker Desktop/CLI e Podman também foram conferidos: ausentes;
nenhum processo de engine foi encontrado. Essas provas são estáticas e não
substituem `docker compose up --build` com um Engine disponível.

Pagamento real continua dependendo da escolha e homologação de provedor. Não houve movimentação de dinheiro real ou envio de e-mail externo.
