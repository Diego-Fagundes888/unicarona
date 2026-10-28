# Assets visuais

## Logo UniCarona · Percurso U

Direção A escolhida em 23/10/2026. Kit vetorial em `docs/brand/unicarona-percurso-u/`.
As aplicações do produto usam arquivos em `dist/assets/brand/`: assinatura verde/preta
para fundo claro, menta/branca para fundo escuro e símbolo isolado na ilustração de
Minhas caronas. O lettering é original em curvas, sem fonte externa.

As assinaturas web mantêm os paths e as proporções do master; apenas o canvas foi
compactado verticalmente para os cabeçalhos. Dimensões responsivas ficam em
`dist/brand.css`. As cinco páginas HTML usam o mesmo favicon SVG/ICO e os ícones
web do kit. O manifest usa caminhos relativos à pasta dos assets.

`dist/assets/brand/logo-email.png` é a versão PNG em fundo branco para o modelo
Java de e-mails. A URL usa a origem do próprio produto e não contém identificador
de destinatário. Mensagens já congeladas na fila preservam seu conteúdo original.

O endereço legado `dist/assets/logo.jpg` também contém o novo ícone. A imagem
anterior foi preservada em `docs/brand/2026-10-23-concepts/previous-logo.jpg`.
Os exports históricos de integração são mantidos apenas localmente.

## Ilustração do campus

O dashboard usa Leaflet servido localmente e tiles do
[OpenStreetMap](https://www.openstreetmap.org/), com atribuição aos colaboradores
e links do provedor visíveis. A busca continua disponível quando o mapa falha.
As coordenadas públicas do centro do mapa foram extraídas do mapa incorporado
na [página de contato da UNICEPLAC](https://www.uniceplac.edu.br/fale-conosco/)
em 01/10/2026. Representam a região do campus, não um ponto de embarque definido.

O `<picture>` da página inicial oferece `dist/assets/campus.webp` (172.918 bytes)
e conserva `dist/assets/campus.png` (2.046.647 bytes) como fallback e endereço
compatível. As versões têm as mesmas dimensões e proporções; a versão WebP
reduz em aproximadamente 91,6% os bytes da ilustração transferidos por navegadores
compatíveis. O PNG original continua disponível para futuras exportações.

Gerada com a ferramenta integrada image_gen para o protótipo UniCarona. A cena é ilustrativa e não representa instalações reais da UNICEPLAC.

Brief usado: Adult Brazilian university students with backpacks meeting at a parked dark charcoal compact car on a sunny campus; one driver near open front door, two passengers arriving, generic modern campus building without signage, trees, warm orange sunlit architecture, fresh emerald accents, light sky. Sophisticated editorial illustration with geometric shapes and dimensional shadows, clean uncluttered composition. Adults 20+, no logos, no text, no license plate details. Square composition for a university carpool website hero.
