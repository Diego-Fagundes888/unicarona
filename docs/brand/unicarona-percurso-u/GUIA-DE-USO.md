# UniCarona · Percurso U

Identidade escolhida em 23/10/2026. Um ponto de encontro no U do seu percurso.

## Arquivo certo

Use `digital/svg/unicarona-horizontal-color.svg` como assinatura principal. `stacked` é a composição vertical; `symbol`, o símbolo isolado; `wordmark`, apenas o nome. Os equivalentes PNG estão em `digital/png/` e têm fundo transparente.

| Fundo | Versão |
|---|---|
| Branco ou muito claro | `color` (verde + preto) ou `green` (uma cor) |
| Preto #111111 ou muito escuro | `dark` (menta + branco) ou `white` |
| Verde #087B5B | `white` |
| Impressão em uma tinta | `black`, `green` ou `white`, conforme o material |

## Respiro e tamanho

A unidade de respiro é o diâmetro do ponto: 40 unidades no desenho de 256. Mantenha ao menos um diâmetro entre a silhueta e outros elementos. Os SVGs têm margem interna; complete o respiro externamente se necessário. Não altere a relação de tamanho ou o intervalo entre símbolo e nome nas assinaturas.

| Composição | Mínimo digital | Referência impressa* |
|---|---|---|
| Horizontal | 180 px de largura | 35 mm |
| Vertical | 160 px de largura | 30 mm |
| Nome isolado | 120 px de largura | 25 mm |
| Símbolo padrão | 24 px | 7 mm |
| Símbolo para redução | 16 px | — |

*Dimensões impressas são referências iniciais; valide uma prova no material real. Para 16–23 px, use `masters/unicarona-symbol-small-green.svg`, `masters/unicarona-symbol-small-white.svg` ou o favicon pronto. Ícones em `web/` já têm composição e margens próprias.

## Paleta

| Cor | HEX | RGB | CMYK aproximado** | Função |
|---|---|---|---|---|
| Verde percurso | #087B5B | 8, 123, 91 | 94, 0, 26, 52 | Marca em fundo claro |
| Menta | #08DCA4 | 8, 220, 164 | 96, 0, 25, 14 | Símbolo em fundo escuro |
| Preto | #111111 | 17, 17, 17 | 0, 0, 0, 93 | Nome e fundos |
| Branco | #FFFFFF | 255, 255, 255 | 0, 0, 0, 0 | Reversão e fundos |

**Conversão matemática sem perfil ICC; os arquivos entregues são RGB. CMYK e cor especial/Pantone finais precisam de prova e correspondência física com a gráfica; não foi atribuído código Pantone sem essa validação.

Contraste calculado: verde/branco 5,26:1; menta/preto 10,60:1; branco/preto 18,88:1. Menta/branco tem apenas 1,78:1: reserve a menta para fundos escuros. O laranja do produto pode continuar em conteúdos e ilustrações; não compõe esta logo.

## Tipografia e forma

O nome foi desenhado como lettering original em curvas. Não requer instalação ou licença de fonte externa. Para títulos e textos do produto, a família já existente continua sendo Arial, Helvetica, sans-serif; o kit não redistribui fontes.

Preserve a proporção, a orientação e o ponto separado. Use as versões fornecidas, sem sombras, contornos ou gradientes. Em fotos e áreas movimentadas, aplique a marca sobre uma superfície lisa. A versão branca reduz a espessura do símbolo em 5% para compensação visual; o lettering é preservado.

## Web e entrega

`web/` contém favicon SVG/ICO/PNG, apple-touch-icon, ícones 192/512, maskable e manifest. Para integrar, copie essa pasta para `/assets/brand/` e use `web/head-snippet.html`. Os caminhos do manifest são relativos à própria pasta. Isso prepara os ícones e metadados; não implementa funcionamento offline ou instalação do produto.

A apresentação em `presentation/apresentacao.html` é independente de conexão de rede. Os contextos de app, perfil e camiseta são simulações de identidade, sem lançamento ou conta criada. O site existente não foi alterado nesta entrega.

Para registro e lançamento, faça uma busca profissional de marca e semelhança visual. O estudo não comprova exclusividade jurídica.
