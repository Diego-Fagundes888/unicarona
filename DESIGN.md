---
name: UniCarona
description: Sistema implementado de caronas universitárias, com base preto e branco e detalhes verdes e laranja.
colors:
  uc-ink: "#111"
  uc-muted: "#626262"
  uc-border: "#dedede"
  uc-control-border: "#858585"
  uc-surface: "#fff"
  uc-soft: "#f3f3f3"
  uc-brand: "#087b5b"
  uc-orange: "#b65318"
  uc-success: "#17643a"
  uc-warning: "#825b06"
  uc-error: "#a8241b"
  uc-info: "#235e75"
  black: "#000"
  primary-hover: "#333"
  secondary-hover: "#e5e5e5"
  disabled-surface: "#e6e6e6"
  disabled-text: "#707070"
  home-surface: "#262626"
  home-muted: "#c7c7c7"
  home-line: "#333"
  home-accent: "#08dca4"
  badge-success-surface: "#eaf6ee"
  badge-warning-surface: "#fff2d9"
  badge-error-surface: "#fcebee"
  notice-success-surface: "#e8f4eb"
  notice-warning-surface: "#fff3da"
  notice-warning-text: "#684b08"
  notice-error-surface: "#fae9e6"
  notice-error-text: "#922116"
typography:
  display:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "52px"
    fontWeight: 700
    lineHeight: 1.12
    letterSpacing: "-0.03em"
  headline:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "32px"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.025em"
  title:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.35
  body:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
  body-public-auth:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.55
  body-dashboard:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "14px"
    fontWeight: 600
  action:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "15px"
    fontWeight: 600
  caption:
    fontFamily: "Arial, Helvetica, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  uc-control-radius: "8px"
  uc-radius: "12px"
  uc-dialog-radius: "16px"
  badge: "5px"
  segmented: "24px"
  avatar: "50%"
spacing:
  uc-space-1: "4px"
  uc-space-2: "8px"
  uc-space-3: "12px"
  uc-space-4: "16px"
  uc-space-6: "24px"
  uc-space-8: "32px"
  uc-space-12: "48px"
  uc-space-16: "64px"
components:
  button-primary:
    backgroundColor: "{colors.uc-ink}"
    textColor: "{colors.uc-surface}"
    typography: "{typography.action}"
    rounded: "{rounded.uc-control-radius}"
    padding: "11px 18px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.uc-surface}"
  button-secondary:
    backgroundColor: "{colors.uc-soft}"
    textColor: "{colors.uc-ink}"
    rounded: "{rounded.uc-control-radius}"
    padding: "11px 18px"
  button-secondary-hover:
    backgroundColor: "{colors.secondary-hover}"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.uc-ink}"
    rounded: "{rounded.uc-control-radius}"
    padding: "11px 18px"
  button-quiet-hover:
    backgroundColor: "transparent"
    textColor: "{colors.uc-ink}"
  button-danger:
    backgroundColor: "{colors.uc-error}"
    textColor: "{colors.uc-surface}"
    rounded: "{rounded.uc-control-radius}"
    padding: "11px 18px"
  button-danger-hover:
    backgroundColor: "{colors.uc-error}"
    textColor: "{colors.uc-surface}"
  button-disabled:
    backgroundColor: "{colors.disabled-surface}"
    textColor: "{colors.disabled-text}"
  button-public:
    backgroundColor: "{colors.uc-surface}"
    textColor: "{colors.uc-ink}"
    typography: "{typography.action}"
    rounded: "{rounded.uc-control-radius}"
    padding: "12px 20px"
  field:
    backgroundColor: "{colors.uc-soft}"
    textColor: "{colors.uc-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.uc-control-radius}"
    padding: "12px"
    width: "100%"
  panel:
    backgroundColor: "{colors.uc-soft}"
    textColor: "{colors.uc-ink}"
    rounded: "{rounded.uc-radius}"
    padding: "24px"
  badge-success:
    backgroundColor: "{colors.badge-success-surface}"
    textColor: "{colors.uc-success}"
    rounded: "{rounded.badge}"
    padding: "4px 8px"
  notice:
    backgroundColor: "{colors.uc-soft}"
    textColor: "{colors.uc-ink}"
    rounded: "{rounded.uc-control-radius}"
    padding: "16px"
  dialog:
    backgroundColor: "{colors.uc-surface}"
    textColor: "{colors.uc-ink}"
    rounded: "{rounded.uc-dialog-radius}"
    padding: "28px"
    width: "min(520px, calc(100vw - 32px))"
  navigation-item:
    backgroundColor: "transparent"
    textColor: "#595959"
    padding: "12px 14px"
  navigation-item-selected:
    textColor: "{colors.black}"
  itinerary:
    textColor: "{colors.uc-ink}"
    padding: "0 0 0 28px"
---

# Design System: UniCarona

## Overview

**Creative North Star: "Caronas da comunidade universitária"**

Este nome descreve a identidade existente: caronas de ida e volta à faculdade, com contexto acadêmico legível e ações diretas. Logo, base preto e branco, verde e laranja discretos e Arial/Helvetica permanecem os compromissos confirmados. A Uber informa arquitetura, hierarquia e navegação; a identidade visual e os fluxos pertencem ao UniCarona.

A página pública usa fundo escuro e fotografia/ilustração de campus como apoio à busca. Autenticação e áreas operacionais usam superfícies claras, formulários estreitos, registros separados por linhas e disclosures para detalhes secundários. Esta documentação extrai a implementação; não representa nova direção, comp aprovada, seed, roll ou aprovação de entrega.

**Key Characteristics:**

- Base preto e branco, detalhes de marca e cor funcional nos estados.
- Arial/Helvetica comum às superfícies, com densidade adaptada à tarefa.
- Registros planos, formulários estreitos e profundidade reservada a sobreposições.
- Navegação, formulários e conteúdo reorganizados nas larguras menores.

Fonte normativa: `dist/design-system.css` e componentes de apresentação em `dist/ui.js`; variantes efetivas em `dist/styles.css`, `dist/home.css`, `dist/login.css` e `dist/dashboard.css`. O mapa de contexto usa `dist/map.js`, montado pelo dashboard, e Leaflet 1.9.4 servido localmente em `dist/vendor/leaflet/`. Proveniência, hashes, snippets e limites estão no sidecar. Tokens listados são os reutilizados; valores particulares de uma única superfície não formam nova escala.

## Colors

A paleta separa ação, leitura, marca e situação operacional. Os valores canônicos estão no frontmatter; os nomes abaixo explicam a aplicação.

### Primary

- **Tinta operacional (`uc-ink`)**: texto principal e botão primário compartilhado. A autenticação e o shell do painel usam também preto absoluto (`black`).
- **Branco (`uc-surface`)**: fundo operacional, diálogo e texto sobre ações escuras; botão principal invertido na home.

### Secondary

- **Verde UniCarona (`uc-brand`)**: detalhes da marca, foco e progresso acadêmico. A home usa verde luminoso (`home-accent`) no contexto escuro; não é substituição global do verde operacional.
- **Laranja UniCarona (`uc-orange`)**: detalhes discretos de segurança e identidade, sem competir com a ação principal.

### Neutral

- **Cinza de leitura (`uc-muted`)**: ajuda, metadados e placeholders; a home usa `home-muted` sobre preto.
- **Cinza de superfície (`uc-soft`)**: campos, painéis de reserva/embarque e ações secundárias.
- **Divisor (`uc-border`)**: linhas entre registros e fronteiras de conteúdo; **borda de controle (`uc-control-border`)**: contorno mais contrastado dos campos.
- **Superfície escura (`home-surface`)** e **divisor escuro (`home-line`)**: agrupamento de conteúdo na home.
- **Hover e disabled**: variantes explícitas do frontmatter, sem reduzir a opacidade do texto desabilitado.

Sucesso, aviso, erro e informação são papéis funcionais (`uc-success`, `uc-warning`, `uc-error`, `uc-info`). Badges e notices possuem fundos próprios; aviso e erro em notices usam texto contextual específico. O token `uc-info` existe na fonte, mas o notice informativo padrão permanece neutro na implementação.

**The Cor com função Rule.** Preserve a ação principal em preto ou branco conforme a superfície; use verde/laranja como detalhes e os tons semânticos junto de texto/ícone que explique o estado.

## Typography

**Display Font:** Arial, Helvetica, sans-serif.

**Body Font:** Arial, Helvetica, sans-serif.

**Character:** leitura operacional direta, pesos regulares para conteúdo e semibold/bold para ação e hierarquia. A família foi preservada pelo contrato de direção; não há fonte proprietária da Uber, fonte mono nem pareamento novo.

### Hierarchy

- **Display:** headline da home; escala responsiva descrita em Layout. Títulos de seção públicos usam 36px e line-height 1.15, chegando a 30px no celular.
- **Headline:** títulos operacionais compartilhados. Autenticação usa line-height 1.15; a busca principal do dashboard usa 28px.
- **Title:** seções compartilhadas. Subtítulos usuais usam 17px; nomes de pessoa, grupos de formulário e várias seções refinadas usam 20px.
- **Body:** conteúdo operacional compartilhado. Home/auth usam `body-public-auth`; shell do dashboard usa `body-dashboard`.
- **Label / action / caption:** labels explícitos, ações semibold e ajuda menor. Badges usam 12px/600; não usar esse tamanho para o texto principal.
- **Números:** resumos, saldo, totais e PIN usam `font-variant-numeric: tabular-nums`. Valor de saldo efetivo é 40px/700/1.2; total de plano é 32px, reduzido a 28px até 700px. PIN usa 38px e espaçamento de 0.16em.

Não há razão matemática única de escala implementada. Textos de ajuda usam limites contextuais: 56ch no vazio compartilhado, 60ch na ajuda e 48ch em ações de apoio do dashboard.

## Layout

A largura compartilhada de conteúdo é 1152px e a de formulário é 560px. Home usa container com margens laterais de 48px; até 1150px, 32px; até 600px, 16px. Auth centraliza login/recuperação em 420px e cadastro em 560px; até 600px, a área principal passa a padding 32px 16px 48px. O conteúdo do dashboard tem caixa externa máxima de 1224px incluindo padding lateral de 36px, reduzido a 28px até 1180px e 16px até 700px.

A escala compartilhada de espaçamento está no frontmatter. A implementação também usa 20px entre campos, 40px em grids gerais, 48px em busca/admin e 64px em formulário/mensal/contrato; essas escolhas contextuais não devem ser reduzidas artificialmente à escala.

| Limiar real | Adaptação implementada |
| --- | --- |
| 1180px | Dashboard: busca lateral de 360px passa a 326px; gap 48px passa a 24px. |
| 1150px | Home: margens menores, hero gap 128px passa a 72px e display passa a 48px. |
| 1000px | Mensal/renovação empilha agenda/configuração; gap 32px e divisão horizontal. Contrato empilha e coloca resumo antes dos registros. Navegação superior do painel dá lugar à barra inferior de cinco destinos. Admin passa de sidebar de 200px para disclosure com links em duas colunas. |
| 950px | Home esconde navegação desktop e mostra menu popover; sugestões passam de três colunas para uma; display passa a 42px. Hero ainda mantém duas colunas. |
| 700px | Grids operacionais, formulários de duas colunas e busca/filtros empilham. Checkout prioriza resumo; reserva mantém ordem normal. Mapa e data/horário do dashboard ficam recolhidos por JS e abrem por disclosure. Ações de registros podem quebrar linha; controles de ação passam a mínimo de 48px. Admin passa a uma coluna. |
| 600px | Home empilha hero e seções, reorganiza header/footer e usa display 44px. Auth usa margens de 16px e headline 28px. |
| 360px | Home usa display 39px; data/hora do dashboard passam a uma coluna; detalhes secundários do mapa são reduzidos. |

O saldo continua efetivamente 40px em mobile pela especificidade da cascata; o valor 36px declarado num override não está ativo e não integra o frontmatter. No dashboard, a regra final de apoio em duas colunas prevalece sobre a regra antiga de uma coluna em 1180px; ela empilha em 700px.

**The Reorganizar a tarefa Rule.** Preserve as mudanças de ordem, disclosures e navegação por contexto; responsividade inclui controles e fluxo, além de largura. Não tornar a navegação inferior uma regra da página pública ou da autenticação.

## Elevation & Depth

O sistema usa separadores e camadas tonais no conteúdo comum. Registros, busca, perfil e ações de apoio não recebem sombra como padrão. Menu de conta, menu público, toast e diálogo compartilhado usam a sombra ambiente `uc-shadow` registrada no sidecar. O diálogo do dashboard é uma variante anterior com borda e raio menor, sem essa sombra.

Backdrops escurecem o contexto de diálogos nativos. Sombra não define grupos normais de formulário ou status; estes usam alinhamento, espaço, linha ou fundo tonal.

**The Profundidade de sobreposição Rule.** Reserve a sombra compartilhada às superfícies que se sobrepõem ao conteúdo; mantenha registros planos.

## Shapes

Controles usam cantos moderados (`uc-control-radius`); painéis e menus usam `uc-radius`; diálogo compartilhado usa `uc-dialog-radius`. Badges têm raio menor; segmentos de modalidade e cadastro público usam cápsulas; avatares são circulares. Linhas de registros e tabs sublinhadas conservam cantos retos.

O itinerário combina origem circular, linha vertical e destino quadrado. Essa geometria é uma linguagem de trajeto existente, acompanhada de nomes acessíveis de origem/destino; não é ornamento arbitrário. Ícones compartilhados são SVG de traço (20px, viewBox 24, stroke 1.75); o shell usa 22px/1.7 e variantes menores na navegação.

## Components

### Buttons

Ações diretas com label explícito. O primário compartilhado usa os tokens do frontmatter, borda da mesma tinta, altura mínima 44px e hover escuro; active reduz a escala a 0.98. Até 700px, altura mínima passa a 48px. Secondary usa fundo tonal; quiet é transparente com borda; danger sinaliza ação destrutiva. Na home, ação principal é branca sobre preto. Auth usa botão preto full-width com padding 13px 18px e texto 16px; dashboard usa altura mínima 48px e padding 12px 18px.

Foco compartilhado usa outline verde de 3px, offset 3px. `busy()` troca texto, desabilita e aplica `aria-busy`, restaurando o label ao terminar. Estado disabled tem cores explícitas e não escala no active.

Quiet e danger conservam seus fundos no hover: seus seletores têm a mesma especificidade que o hover primário e aparecem depois na fonte. Foco e active continuam compartilhados; não inferir um hover escuro sem considerar a cascata.

### Inputs / Fields

Campos/selects/textarea usam fundo tonal, borda de controle, cantos moderados, largura total e altura mínima 48px. Labels são explícitos; ajuda usa `aria-describedby`; erro é textual e marca `aria-invalid`. Hover do campo compartilhado escurece a borda. Textarea permite resize vertical e inicia em 100px. Disabled conserva leitura e cursor apropriado.

Checkbox compartilhado é nativo (18px), acompanhado de label com área mínima 44px; auth tem variante visual de 16px com check SVG e label maior. Dias selecionados ficam preto/branco e mantêm controle checkbox. Upload compartilhado preserva input file real por baixo da apresentação e exibe nome truncado; foco usa `:has(:focus-visible)` no invólucro.

Auth possui catálogo pesquisável com opções em fluxo, altura máxima 260px, seleção textual, `aria-selected` e contorno verde. Não existe componente reutilizável de switch, radio, drawer ou tooltip definido na camada compartilhada; não fabricar tais entradas na documentação. O tooltip do marcador do mapa é uma variante local do Leaflet, com Arial/Helvetica a 14px/1.4.

### Chips / Badges / Tabs

Badges descrevem situação com texto, fundo e tom semântico; não são todos controles. Segmentos de modalidade são botões com `aria-pressed`; default tonal e selected preto/branco. Tabs de registros usam linha inferior de 2px no estado selecionado, overflow local e label preservado. Tabs não devem virar chips apenas porque ambos selecionam uma opção.

### Cards / Containers

Painel tonal para reserva/embarque, padding de 24px reduzido a 20px até 700px, sem sombra. A lista é composta por registros com padding vertical 20px e divisor; conteúdo longo usa `overflow-wrap: anywhere`. Perfil e ações de apoio do dashboard refinado são blocos planos. Container não implica card contornado.

### Navigation

Shell do painel: destino atual usa `aria-current="page"`, texto preto e sublinhado no desktop. Até 1000px, cinco destinos formam barra inferior fixa com áreas mínimas 56px, padding seguro via `env(safe-area-inset-bottom)` e seleção tonal. Conteúdo reserva espaço abaixo da barra. Ícones de conta/notificação do shell têm 40px, uma exceção observada à altura geral de 44–48px.

Menu de conta e menu público usam popover nativo e links de 48px; foco do item fica para dentro. Admin: lista lateral no desktop e disclosure nas larguras menores. Mapa e agenda do dashboard também usam `details/summary` e sincronizam estado recolhido com `matchMedia` até 700px.

### Mapa de contexto do campus

O mapa é um apoio navegável à busca, centrado no campus público fixo da UNICEPLAC, sem GPS ou acompanhamento de viagem. Leaflet 1.9.4 é servido localmente com LICENSE/README; tiles continuam no OpenStreetMap. O controlador só inicializa nas rotas início/busca com disclosure aberto e canvas com dimensões visíveis; no mobile, o mapa recolhido não inicia pedidos de tiles.

Estados próprios: `loading` anuncia carregamento e mantém `aria-busy="true"`; após 12000ms, `slow` explica a demora e oferece retry/link externo; erros de tiles levam a `error`; somente `ready` mostra o canvas. A camada textual usa `role="status"` e `aria-live="polite"`; a busca permanece disponível. Retry redesenha os tiles sem alterar a busca. Ao descartar o mapa, o controlador limpa timer, observer, eventos e instância.

O canvas usa grayscale e cantos de 12px, altura desktop 560px. Até 700px, mapa pronto tem altura 300px; a mensagem visível exige mínimo de 440px, permitindo texto e ação sem compressão. Zoom tem alvos de 44×44px, cantos de 8px, sem sombra, ícones SVG plus/minus e nomes “Aproximar mapa” / “Afastar mapa”; foco verde de 3px fica para dentro. Marcador circular de 40px e tooltip identificam apenas o campus. Scroll da página não aciona zoom por roda; zoom/fade/marker animation respeitam reduced motion.

Atribuição OpenStreetMap e link “Abrir mapa” permanecem visíveis em todas as condições de conteúdo. Os pedidos de imagem definem `referrerPolicy: "strict-origin-when-cross-origin"`; não inferir a política dos tiles apenas pela meta de referrer do documento. Carregar mapa real depende da rede/provedor; os testes de estados abortam pedidos externos, não baixam tiles nem certificam disponibilidade do serviço.

### Notices / Empty / Loading

Notice tem ícone SVG e texto contextual; informação padrão é neutra, aviso/erro/sucesso usam fundos e texto próprios. Vazio compartilhado alinha à esquerda, explica ausência e pode oferecer ação. Loading tem `role="status"`, label oculto e skeletons decorativos; a animação alterna opacidade por 1.4s. `prefers-reduced-motion` remove animações/transições onde implementado e desativa scroll suave.

Toast é `role="status"`, escuro, limitado à largura da viewport e aparece por 4500ms. Feedback de formulário usa `role="alert"`; falhas/offline e resposta pendente devem permanecer explícitos, sem converter ausência de resposta em sucesso.

### Dialogs / Itinerary / Financial Summary

Diálogo compartilhado é nativo, limita altura a 85dvh, permite scroll, tem título associado por `aria-labelledby` e devolve foco ao controle de origem ao fechar quando ele ainda existe. Confirmação prioriza o botão de voltar. Variante do dashboard usa largura máxima 480px, padding 28px e raio 12px.

Itinerário separa origem, destino e ponto de encontro; resumo usa `dl` e números tabulares. Preço, subtotal, saldo, PIN e estado financeiro cumprem papéis distintos e não devem ser fundidos num badge de sucesso.

A evidência visual existente cobre desktop 1440×900, notebook 1280×720, tablet 768×1024 e mobile 390×844; há testes adicionais até 320px. Raster AX pode diferir da viewport DOM; imagens do primeiro viewport não comprovam controles inferiores. Este registro amostra originais e lê o código, sem substituir a revisão integral. Teclado, nomes acessíveis e retorno de foco têm evidência nos testes existentes; leitor de tela e aparelhos físicos não foram verificados.

## Do's and Don'ts

### Do:

- **Do** preservar logo, preto/branco, detalhes verde/laranja e Arial/Helvetica da identidade existente.
- **Do** reutilizar o sistema compartilhado, preservando variantes justificadas de home, auth e dashboard.
- **Do** usar labels, foco visível, feedback textual e estados sem depender apenas de cor.
- **Do** preservar empilhamento mensal/contrato em 1000px, disclosures e ordem de conteúdo por tarefa.
- **Do** manter registros planos e reservar sombra às sobreposições.
- **Do** confrontar os snippets e tokens com a cascata efetiva após futuras alterações.

### Don't:

- **Don't** introduzir branding proprietário da Uber ou substituir a identidade universitária por uma nova direção.
- **Don't** transformar informações de matrícula, confirmação de e-mail, PIN ou pagamento pendente em garantias não sustentadas pelo produto.
- **Don't** registrar valores anulados pela especificidade como variantes efetivas; avalie a cascata e o contexto.
- **Don't** atribuir validação de leitor de tela, aparelho físico ou aprovação de entrega a este registro.

Não canonizado como variante: declaração móvel de saldo a 36px anulada pela especificidade; o valor efetivo é 40px, sem concluir defeito visual. Controles de ícone de 40px são uma exceção local observada, não uma regra para novos controles. A direção preserva Arial/Helvetica intencionalmente; nomes de contexto ou pequenos textos introdutórios não são promovidos a um padrão de eyebrow/kicker. Nenhuma proibição de craft foi convertida em token.
