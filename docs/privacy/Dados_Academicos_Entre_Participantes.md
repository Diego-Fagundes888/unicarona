# Dados acadêmicos entre participantes

26/10/2026. Complemento de desenvolvimento ao schema V46, sem migration.
Privacidade r11 em `draft`; fonte r10 preservada integralmente em
`backend-java/src/main/resources/legal/history/privacy-r10.md`. A demonstração
privada V26 e seu frontend congelado não foram alterados. O objetivo integral
dos 44 requisitos continua ativo.

## Achado concreto

As respostas de busca e detalhe de caronas avulsas e rotinas mensais entregavam
curso, número do período e turma exatos do motorista. A consulta inicial exigia
somente vínculo com a mesma universidade; portanto uma pessoa sem contratação,
inclusive com turma incompatível, podia obter esses campos. A apresentação
reduzida do nome não reduzia esse outro conjunto de dados identificadores.

Na avulsa, os campos não são necessários para a contratação. No mensal, a regra
do servidor compara curso/período/turma e a interface já utiliza um indicador de
compatibilidade. Não é preciso entregar os valores exatos de todas as turmas
incompatíveis para informar que uma rotina não pode ser contratada por alguém.

## Regra na fonte

| Consulta/contexto | Perfil acadêmico do motorista |
| --- | --- |
| Busca e detalhe de oferta por outra pessoa | Curso/turma vazios, número nulo, `periodStatus=protected` |
| Solicitação aceita e válida, antes do pagamento | Encontro/observações pertinentes mantêm sua regra; o perfil acadêmico exato continua protegido |
| Reserva paga com finalidade operacional | Identificação do veículo e encontro seguem o acesso operacional existente; não abrem curso/período/turma |
| Histórico, reserva encerrada e bloqueio | Perfil acadêmico continua protegido; demais bloqueios e autorizações não são removidos |
| Próprio motorista: detalhe e lista de suas ofertas | Mantém os próprios dados acadêmicos e nome completo conforme o contexto existente |
| Mensal compatível ou incompatível | Mantém `compatible`, filtros, ordenação, preços e verificação de elegibilidade no servidor; valores exatos de outra pessoa não são entregues |
| Perfil próprio, correção, exportação e administração autorizada | Continuam com finalidades próprias; esta mudança não apaga dados do banco nem altera suas permissões |

`RideReadRepository` agora recebe o titular autenticado também na consulta de
detalhe. As duas projeções JDBC condicionam os campos acadêmicos no próprio
SELECT à identidade do dono da oferta. O valor não chega ao mapper da contraparte
para ser escondido apenas no HTML. A reconstrução de detalhes operacionais da
avulsa também conserva a proteção, independentemente do pagamento ou histórico.

Não foi criada uma autorização alternativa por query string ou por `driverId`.
O viewer vem da sessão autenticada. Continua proibido consultar oferta indisponível
ou de outra instituição sem o vínculo previsto pelas regras já existentes.

O campo de apresentação `Driver.periodStatus` passa a admitir `protected` no
OpenAPI. Esse estado não é um valor novo do perfil cadastrado e não indica que a
pessoa deixou de informar seu período. O frontend de desenvolvimento mostra
“Dados acadêmicos protegidos”, inclusive se um cliente ainda mantiver valores
antigos na estrutura em memória. Os próprios dados continuam com a apresentação
existente de curso/período/turma ou valores não definidos.

## O que a redução não promete

`compatible=true` permite deduzir que a rotina corresponde à própria turma do
passageiro. Isso é intencional e necessário à escolha/contratação do mensal,
não anonimização. Não são removidos IDs técnicos usados para autorização,
contratação, bloqueio ou relato de ocorrência. Região, agenda, relações e histórico
ainda podem identificar pessoas. A revisão dessas outras finalidades permanece
separada; esconder o curso não torna a oferta anônima.

O perfil acadêmico é autodeclarado; alterações sucessivas do próprio perfil podem
ampliar a inferência por compatibilidade. A redução da resposta não é uma barreira
forte contra dedução da turma. Controle proporcional de abuso e revisão dessa
inferência continuam necessários, sem impedir correções legítimas de cadastro.

O código não elimina cópias já legitimamente recebidas por outra pessoa nem
limpa retrospectivamente cache fora do controle da aplicação. A versão nova do
frontend deixa de renderizar o conjunto protegido; publicação coordenada continua
necessária para trocar API e clientes em conjunto. O servidor legado não é uma
alternativa de execução aprovada para esta política.

## Fundamento e documentos

A decisão aplica finalidade, necessidade, transparência e segurança ao produto
observado, conforme [LGPD, art. 6º](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
Não há afirmação de que a lei proíbe universalmente divulgar um curso, ou de que
ocultar um campo exige um novo consentimento para as mesmas operações contratuais.
A minuta informa a redução e a dedução possível por compatibilidade. A classificação
da revisão como informativa não publica o texto nem cria aceite retroativo.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Conferir a política de dados
acadêmicos, a finalidade da compatibilidade e o conjunto documental antes de
publicar para usuários reais.

## Verificação e limites

Testes HTTP literais foram acrescentados para catálogo, detalhe, titular, oferta
aceita, reserva paga e histórico de avulsa; compatibilidade verdadeira/falsa e
filtragem mensal; mensal pago com detalhes operacionais e rotina pausada. O
cenário já existente de aceite mensal também confere a proteção antes do pagamento.
As comparações com o backend legado tratam essa diferença de privacidade
explicitamente, sem exigir que o servidor atual reproduza a exposição anterior.

A rodada final `privacy-academic-minimization-final-26oct.log` passou em
**191 unitários e 189 integrações**, sem falha, erro ou skip. Integrações: Payment24,
IdentityDriver11, Ride20, Routine16, Contract39, Lifecycle30, Request16, Legal15 e
PrivacyRights18. Inclui quatro cenários HTTP novos e reforço literal de um cenário
de aceite mensal existente. Não somar com a rodada inicial sobreposta.

O primeiro ensaio passou nos três unitários e executou 36 integrações, com um
erro no novo cenário que tentava transição direta de carona prevista para
concluída. O banco recusou corretamente. A fixture foi ajustada para as transições
permitidas, sem enfraquecer o guarda do domínio.

Os **50 testes JS** passaram, incluindo dados protegidos com valores antigos,
perfil próprio informado e perfil próprio não definido. Detector Impeccable
executado uma vez no JS alterado: `[]`. Rodadas e hashes finais em
`evidence/academic-data-minimization-26oct.json`. PostgreSQL usado somente no banco
descartável `unicarona_java_test`; nenhuma chamada real de pagamento/e-mail.

Impeccable foi aplicada ao ajuste delimitado de copy/estado, preservando Arial,
cores, CSS, estrutura, navegação e componentes. Não houve nova captura ou revisão
visual: o usuário pediu continuar as outras pendências enquanto o acesso local
está bloqueado. Esses testes e o detector não equivalem a aprovação visual,
acessibilidade completa ou prontidão de publicação.

Continuam abertos retenção de texto livre/encontro/agenda e financeiro realizado,
IDs/caches remanescentes, agregação anônima, operação de recuperação contínua/
externa, jornadas/aceites finais, consistência e red teams integrais e entrega dos
16 itens finais. Esta implementação não encerra o objetivo maior.
