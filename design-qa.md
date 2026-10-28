# QA visual do UniCarona

O registro conectado de 02/10/2026 abaixo é o atual. As seções seguintes
preservam a revisão histórica do protótipo e não comprovam backend ou pagamentos reais.

## Revisão parcial do sistema conectado — 02/10/2026

Esta revisão refere-se à API e ao PostgreSQL reais; o resultado anterior descreve
o protótipo em memória e não comprova a integração atual.

As dez jornadas de `backend/e2e` passaram com sessões separadas, sem exceções
JavaScript. Capturas de início/perfil, calendário e apuração administrativa foram
abertas e inspecionadas em computador e celular. As larguras de 390 px testadas
não tiveram overflow horizontal. A revisão corrigiu rótulos técnicos, repetição
de curso, rodapé antigo e visibilidade da aba administrativa após redimensionar.
Datas nativas têm explicação textual em português para evitar ambiguidades do
formato regional do navegador. Detalhes de parcelas apresentam valor congelado,
participantes, disponibilidade e decisões relacionadas antes da apuração.

A jornada de falhas verifica erro de conexão após pedido gravado, recuperação
da cobrança depois de recarregar e carteira offline com nova tentativa. Capturas
em 390 px dos estados de erro, carregamento e busca vazia foram abertas e
inspecionadas. A busca pública preserva o trajeto pelo login; filtros dos
resultados permanecem ao recarregar. Pagamento pendente apresenta crédito
previsto e uma ação para retomar a mesma cobrança quando ainda não há link.

A fila administrativa de envios foi conferida em 1280 e 390 px. A consulta omite destinatários e links; a retomada exige justificativa e confirmação. O navegador verifica uma tarefa esgotada, sua retomada e conclusão na nona tentativa.

O início foi verificado a 320, 390, 768, 900, 1024 e 1440 px; a revisão encontrou
uma tag excedente que retirava o mapa da grade. O mapa voltou a ficar acima da
busca no celular e ao lado no computador. Geometria, estrutura, agendamento
recolhido/erro com foco e ação principal no desktop têm checks automatizados.
Oito views principais foram capturadas a 320 e 1440 px com texto longo e escape.
Foram abertos início 390/1440, perfil/carteira 320, cadastro acadêmico 320,
carência e Sem aula no celular. O PIN não aparece após Sem aula ou encerramento.

Os cenários mensais usam agenda e parcelas reais do banco: ausência preserva
vaga/valor, disponibilidade tem confirmação separada, renovação paga exige
aceite e carência retém vagas sem embarque não pago. O modal de compartilhar
mostra texto revisável, omite PIN/contato e restaura foco. Foto confirmada aparece
na reserva e o bloqueio revoga a leitura. Não houve envio externo pelo teste.

Limites: somente as telas e jornadas registradas em `docs/Backend_Verificacao.md`
foram verificadas no sistema conectado. Faltam cobertura exaustiva de estados de
falha, todos os breakpoints de cada tela, zoom/leitor de tela e diff de pixels.
O mapa externo não foi comprovado; pagamentos e e-mails usam ambiente local.
As pendências de aceite e a auditoria por requisito estão em `docs/Backend_Auditoria.md`.

## Histórico — escopo e evidências de 01/10/2026

Adaptação da homepage observada da Uber para o UniCarona: mesma estrutura principal e paleta monocromática, com conteúdo acadêmico e detalhes verdes e laranja. A ilustração e os ícones existentes do projeto foram preservados.

Pasta das evidências: `<LOCAL_USER_HOME>`.

- Referência visual: `01-uber-home.jpg`, captura de https://www.uber.com/br/pt-br/; modalidades: `07-uber-sections.jpg`.
- Implementação: http://127.0.0.1:4173/; captura final: `16-unicarona-final-desktop.jpg`; página completa: `17-unicarona-final-full.jpg`.
- Comparação conjunta final: `19-comparison-final.jpg`.
- Comparação conjunta de tipografia e busca: `20-comparison-final-focus.jpg`.
- Comparação conjunta das modalidades: `23-comparison-services.jpg`, regiões alinhadas pelo título e primeira linha de cartões.
- Celular: `D:/UniCarona/docs/previews/unicarona-copy-390.png`; recorte final: `21-unicarona-final-mobile.png`.

Desktop: viewport CSS 1280 × 720, visitante deslogado, topo da homepage, origem vazia, destino UNICEPLAC e modalidade avulsa selecionada. Ambas as capturas de referência e implementação têm 1265 × 712 pixels, conforme a normalização do navegador integrado. A comparação final manteve os pixels originais, sem redimensionar. Celular: teste Chrome com viewport CSS 390 × 900 e densidade 1; screenshot integral 390 × 7591. O recorte móvel conserva a escala 1:1. A capacidade de viewport do navegador integrado não aplicou a largura móvel solicitada; por isso a evidência móvel válida veio do teste existente do projeto.

## Histórico de comparação

1. Primeira comparação (`13-comparison-home.jpg`, `14-comparison-focus.jpg`): [P2] excesso de altura na abertura. A introdução e o aviso repetido empurravam o título da próxima seção para aproximadamente 788 px, prejudicando a composição acima da dobra. Resultado dessa etapa: blocked.
2. Correção: retirada da introdução redundante e do segundo aviso da abertura, preservando o aviso junto à busca e as informações de maioridade e independência no rodapé. Reduzidos os espaços entre abertura e modalidades.
3. Nova captura e comparação conjunta (`19-comparison-final.jpg`, `20-comparison-final-focus.jpg`): título da próxima seção a aproximadamente 661 px. Proporções e sequência visual próximas da referência. Problema corrigido; nenhum P0/P1/P2 pendente.

## Superfícies verificadas

- **Tipografia:** Arial/Helvetica/sans-serif existente; título 52 px, peso 700, entrelinha 1,12 e tracking −1,6 px. Hierarquia, quebra em duas linhas e legibilidade conferidas. A fonte mantém diferenças de desenho em relação à referência da Uber; aceita como adaptação ao sistema tipográfico disponível, sem alegar reprodução exata da fonte proprietária. Título móvel 44 px e 39 px em telas menores.
- **Espaçamento e estrutura:** cabeçalho preto compacto, busca à esquerda, imagem quadrada de até 448 px à direita, CTA sobre a imagem, três cartões em colunas no desktop. Formulário e controles sem colisões. Em celular, uma coluna e navegação inferior existente. A ordem das seções e os controles persistentes foram preservados.
- **Cores:** preto #000, branco #fff, campos #f3f3f3 e cartões #262626. Verde #08dca4 em localização, foco e detalhes; laranja #f47c35 em pequenos acentos. Textos secundários cinza claro nas superfícies escuras. Contraste visual e foco conferidos.
- **Imagem:** `dist/assets/campus.png` existente, 1024 × 1024, reutilizada sem alteração. Assunto universitário, corte quadrado, proporção e nitidez adequados. A ilustração substitui intencionalmente a colagem fotográfica da Uber para manter a identidade do UniCarona. Ícones de interface existentes preservados; não foram criadas aproximações dos assets da Uber.
- **Texto:** chamadas próprias do UniCarona, destino UNICEPLAC, modalidades avulsa/mensal e apresentação das possibilidades. O botão continua descrevendo uma busca demonstrativa. Avisos sobre a demonstração e a ausência de vínculo oficial permanecem. Labels e texto alternativo preservados.

## Funcionamento e acessibilidade

`tests/check-copy.cjs` passou após a última alteração: larguras 320, 390, 768, 900, 1024 e 1440, sem overflow horizontal; destinos dos diálogos; regras de cobrança; validação de origem e destino; busca mensal; FAQ; foco e segurança da viagem. Zero erros de JavaScript. O console da prévia desktop também não apresentou erros ou avisos.

O novo CTA “Conhecer o mensal” utiliza o destino de diálogo já existente, incluído na verificação de destinos. Login e cadastro continuam apontando para as páginas existentes. Não houve alteração na autenticação nem nos fluxos de login.

Limites: não foi realizada auditoria completa com leitor de tela, zoom de 200% ou comparação móvel contra a Uber. As capturas móveis anteriores da referência não tinham viewport móvel confirmado e foram excluídas da comparação.

## Checklist final

- [x] Paleta e composição principal adaptadas à referência.
- [x] Detalhes locais discretos e ilustração do projeto mantidos.
- [x] Excesso de altura corrigido e comparado novamente.
- [x] Desktop e celular verificados visualmente.
- [x] Testes funcionais e de responsividade passaram.
- [x] Prévia local pronta para revisão.

## Atualização — continuidade da página inteira

Pedido adicional: aplicar à rolagem inteira o conceito da abertura. A referência visual para esta etapa é a abertura do UniCarona já escolhida pelo usuário, derivada da homepage da Uber. Trata-se de estender a linguagem visual às informações próprias do projeto, sem reproduzir as seções e serviços comerciais da Uber.

### Comparação e correções

- [P1, corrigido] A passagem das modalidades para o passo a passo mudava abruptamente de preto para branco. Evidência anterior: `24-scroll-before.jpg`. Corrigidos o fundo global, os cartões, as cores de texto, as divisórias, os links e os botões das sete seções seguintes e do rodapé.
- [P2, corrigido] Os títulos longos e a logo ampliada no bloco de conta criavam uma hierarquia diferente da abertura. Encurtados os títulos mantendo os detalhes nos parágrafos e substituído o bloco da logo por uma apresentação textual da organização prevista em Minhas caronas. A imagem da logo existente permanece pequena e os ícones reutilizam o sistema atual.
- [P2, corrigido] A navegação inferior branca e as janelas brancas interrompiam a continuidade do tema. Ambas agora usam a mesma paleta escura, com controles principais brancos e foco verde.

Captura atual integral: `25-scroll-after.jpg`, navegador integrado com viewport CSS 1280 × 720, página deslogada e diálogos fechados; raster integral 1264 × 4692. A captura integral remove a barra de rolagem e mantém a largura de conteúdo. A comparação conjunta `29-continuity-comparison.jpg` coloca a abertura e modalidades ao lado do passo a passo e da conta, com recortes na mesma escala original; verifica coerência entre regiões diferentes da página, sem alegar correspondência de conteúdo ou posição pixel a pixel.

Regiões adicionais abertas e inspecionadas: `26-lower-page.jpg`, `27-monthly-safety.jpg`, `28-faq-footer.jpg`. Janela mensal aberta e conferida: `31-dark-dialog.jpg`. Captura móvel atual: `D:/UniCarona/docs/previews/unicarona-copy-390.png`, viewport CSS 390 × 900, densidade 1, raster 390 × 7672; recortes 1:1 `30-mobile-lower-0.jpg`, `30-mobile-lower-1.jpg` e `30-mobile-lower-2.jpg`.

### Superfícies e verificação final

- Tipografia: família existente mantida; títulos de seção padronizados em 36 px no desktop e 30 px no celular; títulos internos e textos secundários consistentes. Quebras verificadas nos novos títulos e painéis.
- Espaçamento: seções com 64 px de espaço vertical no desktop e 40 px no celular, divisórias discretas, cartões com raio de 8 px e colunas que passam a uma coluna no celular. Sem cortes ou colisões observados.
- Cores: fundo #000, cartões #262626, nota de cobrança e diálogos #171717, textos #fff/#c7c7c7, divisórias #333. Verde e laranja restritos aos detalhes. CTA branco com texto preto nas seções e diálogos.
- Imagens: campus e logo originais preservados; não foram gerados ou desenhados assets novos. O painel de conta é conteúdo HTML sem dados ou viagens fictícias e informa que representa a organização prevista.
- Conteúdo: informação de pedidos, mensalidade, turma, segurança e limites da demonstração preservada. Alterações nos títulos servem à leitura contínua.

O teste existente `tests/check-copy.cjs` foi executado após a última alteração: seis larguras (320, 390, 768, 900, 1024 e 1440), validação e busca, diálogos, regras de cobrança, FAQ, foco e segurança passaram, sem overflow horizontal e sem erros de JavaScript. Console da prévia sem erros ou avisos. O diálogo mensal foi também conferido visualmente no navegador.

Nenhum P0/P1/P2 pendente nesta etapa. Permanecem os limites de auditoria com leitor de tela e zoom já registrados. Resultado da atualização: passed.

## Atualização — painel do aluno

A primeira tela após o acesso estende a linguagem visual da homepage escolhida:
preto, branco, cinza e acentos locais discretos. A busca é a ação principal;
próxima carona e perfil acadêmico ficam ao lado no desktop e abaixo no celular.
Menu lateral no desktop e navegação inferior nas telas menores.

O primeiro render colocava a busca principal abaixo da dobra em 1280 × 720.
Reduzidos os espaços, a altura do cabeçalho e o bloco dos campos, preservando
labels acessíveis e controles de 48 pixels ou mais. A captura final
`36-dashboard-final.jpg`, na pasta local `layout-study`, mostra o botão inteiro
na primeira tela. Não se trata de uma reprodução da área autenticada da Uber.

Conferidas visualmente as capturas desktop e móvel, incluindo o painel integrado
ao cadastro (`docs/previews/dashboard-signup-390.png`) e ao login
(`docs/previews/dashboard-login-390.png`). Logo e ícones existentes reutilizados;
sem assets novos. O resumo do cadastro usa dados reais do formulário em memória;
as viagens e notificações permanecem vazias.

`tests/login.cjs` e `tests/dashboard.cjs` passaram: entrada pelos dois fluxos,
busca, perfil, estados vazios, diálogos, foco e navegação; seis larguras no teste
do painel, sem overflow horizontal. Nenhum erro de JavaScript, envio de dados
ou armazenamento persistente nos cenários verificados. Permanece o limite de
auditoria completa com leitor de tela e zoom de 200%.

## Atualização — adaptação ao modelo web da Uber

O usuário selecionou as capturas da tela de viagens da Uber apresentadas na
comparação anterior. O alvo é a composição web responsiva, adaptada ao projeto:
busca compacta e mapa no desktop; mapa acima do painel de busca no celular.
Não há alvo do aplicativo nativo nem da área autenticada da Uber.

### Evidências

Pasta absoluta das capturas:
`<LOCAL_USER_HOME>`.

- Fonte desktop: `01-uber-desktop.jpg`, captura da página
  `https://m.uber.com/go/home`, viewport CSS 1280 × 720, raster 1280 × 720.
- Fonte celular: `03-uber-celular.jpg`, mesma página, viewport CSS 390 × 844,
  raster 390 × 843.
- Implementação desktop: `09-unicarona-uber-computador-final.jpg`,
  `http://127.0.0.1:4173/dashboard.html`, viewport CSS 1280 × 720,
  raster 1265 × 712, densidade observada aproximadamente 1.
- Implementação celular: `08-unicarona-uber-celular-final.jpg`, viewport CSS
  390 × 844, raster 375 × 811.
- Comparações integrais conjuntas: `10-comparacao-desktop.png` e
  `11-comparacao-celular.png`. As imagens foram normalizadas às dimensões CSS
  informadas para compensar a reamostragem do capturador; não há alegação de
  igualdade pixel a pixel. Nenhuma região da interface foi retocada.
- Comparação focal dos formulários: `12-comparacao-formularios.png`, recortes
  equivalentes das capturas normalizadas, necessários para conferir títulos,
  campos, espaçamento e botão.

Estado: página inicial, modalidade avulsa, ida, campos vazios, sem diálogos,
topo da página. Na Uber a página é pública; no UniCarona é um perfil genérico
de demonstração. Os mapas mostram regiões públicas diferentes e usam
provedores diferentes; essa diferença não representa uma falha de posição.

### Histórico de correções

- [P1, corrigido] O painel anterior usava menu lateral, fundo preto e cartões
  na região principal. Substituídos por cabeçalho branco com navegação, grade
  de 346 px para a busca, intervalo de 36 px e mapa flexível à direita.
  Evidência anterior: `02-unicarona-desktop-atual.jpg`.
- [P1, corrigido] A tela móvel anterior abria diretamente um formulário longo.
  Agora o mapa aparece acima de um painel branco com cantos superiores
  arredondados. Data e horário ficam recolhidos, com valor resumido e abertura
  automática quando um erro precisa ser corrigido. Evidência anterior:
  `04-unicarona-celular-atual.jpg`.
- [P2, corrigido] Na primeira implementação, Abrir mapa cobria o zoom e o
  painel sobreposto cortava parte do rodapé do provedor. Evidência:
  `07-unicarona-uber-mobile-primeira.jpg`. O link foi deslocado para baixo,
  e a altura do iframe foi ajustada no celular. Zoom e atribuição aparecem
  livres na captura final `08-unicarona-uber-celular-final.jpg`.
- [P2, corrigido] A data desktop tinha texto menor que os demais campos e
  labels herdavam peso do CSS do login. Padronizado o peso 400 nos campos
  e labels; data a 14 px e trajeto a 15 px. Conferidos novamente nos recortes
  de `12-comparacao-formularios.png`.

Uma captura intermediária com mapa parcialmente carregado foi rejeitada;
a captura desktop final mostra o mapa completo. Capturas com scroll restaurado
no meio da página também foram rejeitadas; a captura aceita foi conferida
com `scrollY = 0`.

### Cinco superfícies de fidelidade

- Tipografia: Arial/Helvetica existente, título do formulário de 20 px, campos
  de trajeto de 15 px e data de 14 px. Hierarquia compacta e quebras conferidas.
  A fonte proprietária da Uber não foi copiada; a família existente é uma
  adaptação aceita para o projeto.
- Espaçamento e layout: cabeçalho de 60 px, busca e mapa alinhados a 92 px
  do topo no desktop, campos de 48 px, painel de 16 px. No celular, painel
  com raio superior de 20 px, mapa de 36dvh e navegação inferior preservada.
  O mapa móvel é menor que na referência para manter modalidades, sentido,
  agendamento e ação principal visíveis. É uma adaptação funcional deliberada,
  não uma reprodução das proporções do app da Uber.
- Cores: branco #fff, campos #f3f3f3, textos e ação principal pretos;
  cinzas secundários #595959/#666. Verde #087b5b e laranja #b65318 são acentos
  locais com contraste adequado sobre o novo fundo claro. Diálogos e estados
  secundários seguem a mesma paleta.
- Imagens: mapa real do OpenStreetMap em tons de cinza, navegável e com
  atribuição; não há mapa desenhado ou gerado. Logo e ícones existentes
  reutilizados. O provedor e a densidade das etiquetas do mapa diferem da
  referência; não foi copiado o asset/cartografia proprietária da Uber.
- Conteúdo: chamadas em português e modalidades avulsa/mensal, ida/volta e
  dados acadêmicos preservados. Cartões secundários ficam abaixo da região
  principal. Perfil, saída, ajuda e notificações continuam acessíveis.

### Verificação e limites

`tests/dashboard.cjs` passou após a última alteração: layout em seis larguras,
posição do mapa, navegação superior/inferior, agendamento recolhido e recuperação
de erro, busca, perfil, diálogos, foco, saída e entrada pelo login/cadastro.
`tests/login.cjs` também passou nesta etapa, preservando recuperação e cadastro.
Sem overflow horizontal, erros JavaScript, armazenamento persistente ou envio
dos dados dos formulários nos cenários testados. O mapa faz requisições GET
ao provedor usando apenas a região fixa pública.

Zoom In/Out testados pelo navegador integrado no celular, confirmando mudança
de zoom e retorno. Console final sem erros ou avisos. Acesso integrado ao
cadastro conferido com os dados de teste e o resumo acadêmico preservado.
O mapa não calcula rotas e depende de conexão; o projeto continua sem reservas,
viagens, pagamentos e autenticação reais.

Nenhum P0/P1/P2 pendente. Refinamento P3 possível: estilo cartográfico mais
próximo da referência com um provedor configurável. Não há auditoria completa
com leitor de tela ou zoom de 200%.

Checklist: fonte e implementação abertas, comparações conjuntas inspecionadas,
controles livres, estados e responsividade verificados, prévia local mantida.

final result: passed

## Entrega do MVP navegável — revisão final em 01/10/2026

Esta etapa amplia o painel para os fluxos da especificação. As seções anteriores
são registros das revisões anteriores; as evidências abaixo correspondem à
entrega completa do frontend. Referência selecionada: Uber web, capturas
`01-uber-desktop.jpg` e `03-uber-celular.jpg` da mesma pasta `dashboard-comparison`.
Não foi usada uma imagem gerada como prova da implementação.

### Comparação visual conjunta

Prévia: http://127.0.0.1:4173/dashboard.html. Estado: perfil genérico,
sem reserva, modalidade avulsa, ida, campos iniciais, topo com scrollY = 0.

- Desktop: viewport CSS 1280 × 720, captura
  `21-mvp-inicio-computador-entrega.jpg` de 1265 × 712 px.
- Celular: viewport CSS 390 × 844, captura
  `20-mvp-inicio-celular-entrega.jpg` de 375 × 811 px.
- Comparações de tela inteira: `22-mvp-comparacao-desktop-entrega.png`
  e `23-mvp-comparacao-celular-entrega.png`.
- Comparação focada dos formulários:
  `24-mvp-comparacao-formularios-entrega.png`.
- Tela real de reserva no celular:
  `19-mvp-reserva-celular-final.jpg`; resultados:
  `18-mvp-resultados-celular-final.jpg`.

As comparações foram abertas e inspecionadas com fonte e implementação lado
a lado, tanto nas telas inteiras quanto no formulário. O navegador exporta
as capturas da implementação com escala sobre a largura útil, excluindo a
barra de rolagem. Somente os painéis comparativos foram normalizados para
1280 × 720 e 390 × 844; as capturas originais permanecem intactas. Um primeiro
export desktop com enquadramento móvel ainda em atualização foi descartado
e substituído após conferir as dimensões do DOM. Não há afirmação de fidelidade
pixel a pixel.

### Superfícies e adaptações

- **Tipografia:** família existente Arial/Helvetica, hierarquia compacta,
  título da busca de 20 px, rótulos e textos secundários discretos. Os
  formulários internos compartilham a mesma hierarquia. Nome longo conferido
  em 320 px, com quebra no perfil e resumo truncado na barra de apresentação.
- **Espaçamento:** busca de 346 px, intervalo de 36 px e mapa flexível no
  desktop. A barra demonstrativa acrescenta espaço acima da busca. No celular,
  mapa de 36dvh, painel branco de cantos superiores arredondados e navegação
  inferior; agendamento recolhido. O mapa menor acomoda avulsa/mensal,
  ida/volta e ação principal, que aparecem no primeiro enquadramento.
- **Cores:** base branca, campos cinza claro, textos e ação principal pretos.
  Verde existente #087b5b reservado à identidade local e estados; vermelho
  para erros. Cabeçalho, resultados, formulários, diálogos, carteira e
  administração seguem os mesmos tokens.
- **Imagens:** mapa OpenStreetMap real em tons de cinza, logo e ícones
  existentes. Cartografia, fonte proprietária e assets da Uber não foram
  copiados. Zoom, link Abrir mapa e atribuição permanecem visíveis.
- **Conteúdo:** chamadas em português e regras acadêmicas da especificação.
  Fotos, motorista, carro, agenda, preços e estados são demonstrativos.
  Perfil autodeclarado e limites da demonstração aparecem no produto.

### Correções verificadas nesta etapa

- [P1, corrigido] Controles antes limitados a informações/placeholder agora
  percorrem pedidos, pagamentos fictícios, reservas, mensal, gerenciamento,
  carteira, perfil, segurança e administração.
- [P2, corrigido] Regras globais de `.d-shell` interferiam no espaço do painel;
  o seletor foi escopado e a grade revista em todas as larguras.
- [P2, corrigido] Filtros de avulsa não se aplicavam ao mensal; mensal agora
  oferece compatibilidade acadêmica e respeita sentido/horário da busca.
- [P2, corrigido] Resumo financeiro e PIN ficavam após outros detalhes no
  celular; agora têm prioridade visual antes do formulário ou dos dados
  secundários. A ordem semântica continua agrupada por função.
- [P2, corrigido] Voltar não recuperava a tela anterior; passou a usar o
  histórico próprio, com retorno seguro ao início na entrada direta.
- [P2, corrigido] Nome longo podia ampliar a barra de apresentação em 320 px;
  o resumo tem elipse, e o perfil conserva o conteúdo com quebra de texto.
- [P2, corrigido] Preferências não tinham efeito demonstrável; avisos opcionais
  da lista de interesse e frequência registrada no saque agora são aplicados.

### Verificação funcional e limites

Passaram `mvp-domain`, `mvp-flow`, `mvp-states`, `dashboard`, `login` (incluindo
`academic-profile`) e `check-copy`. Foram conferidas nove rotas internas nas
larguras 320, 390, 768, 900, 1024 e 1440 px, navegação por teclado, foco em
diálogos/retorno, formulários obrigatórios, estados vazio/carregando/erro/offline,
pagamento recusado/pendente/aprovado e ações entre os papéis demonstrativos.
Nenhum overflow horizontal ou erro JavaScript nos cenários automatizados.
Os valores e a conciliação foram verificados por parcela, com testes de
idempotência, capacidade concorrida, crédito e decisões financeiras separadas.

Limites: estado somente em memória, sem autenticação real, reservas externas,
pagamentos reais ou backend. O mapa depende de rede e não calcula rotas/GPS.
Não há certificação completa de acessibilidade com leitor de tela ou zoom 200%.
As janelas e regras comerciais ainda indefinidas na fonte permanecem propostas
explícitas da demonstração em `docs/MVP_Fluxos.md`.

Nenhum P0/P1/P2 pendente nos cenários verificados. Possível refinamento P3:
provedor cartográfico configurável para aproximar a aparência da referência.

final result: passed
