# Kit da logo UniCarona

Direção A — Percurso U, escolhida pelo usuário em 23/10/2026.

- `PREVIA.png`: visão geral da identidade final.
- `GUIA-DE-USO.md` e `guia.html`: regras e seleção dos arquivos.
- `masters/`: quatro composições principais e desenho específico para redução.
- `digital/svg/`: símbolo, assinatura horizontal, vertical e nome; versões colorida, preta, branca, verde e para fundo escuro.
- `digital/png/`: equivalentes transparentes em duas resoluções por composição.
- `web/`: favicon, ícones de aplicativo e web, manifest e trecho de integração.
- `presentation/apresentacao.html`: apresentação independente, com seis aplicações ilustrativas.
- `presentation/slides/`: imagens de cada página da apresentação.
- `qa/`: auditoria vetorial, medidas de contraste e captura de tamanhos.

Os arquivos de logo são vetores preenchidos, sem texto dependente de fonte, raster, filtros, máscaras ou strokes. Fontes do sistema são usadas apenas nos materiais explicativos.

O frontend não foi modificado. Nenhum serviço, perfil social, site ou aplicativo foi publicado.

## Reconstrução local

Na cópia de trabalho do projeto (scripts fora do ZIP): `python docs/brand/unicarona-percurso-u/build_kit.py`, depois o script `presentation_board.py` da skill usando `presentation/spec.json`, e `node docs/brand/unicarona-percurso-u/render_kit.cjs`.

Dependências já existentes: Python, Node, Sharp, Playwright, Chrome e scripts locais da skill logo-design. Não foram instalados pacotes. `render_kit.cjs` traduz a apresentação gerada e exporta os PNGs. O ZIP final é um pacote de entrega separado, sem arquivos de execução ou evidências internas.
