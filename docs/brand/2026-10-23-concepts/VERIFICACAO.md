# Verificação dos conceitos

Duas rodadas de construção, auditoria e inspeção visual em 23/10/2026. Os arquivos v1 foram preservados. Resultado final comparativo: concepts.png.

## Rodada 1

SVGs auditados: A 99, B 100, C 99/100 na heurística da skill. A diferença de margens verticais foi informativa, sem falhas estruturais. A prancha concepts-v1.png foi renderizada e inspecionada; a escala do C era mais pesada e o A tinha uma haste e um terminal de 48 unidades.

## Rodada 2

- A: haste/terminal reduzidos de 48 para 40 unidades; abertura interna aumentada e margens verticais igualadas. O terminal separado permanece visível a 16 px.
- B: raio interno de 16 para 20 unidades, mantendo espessura de 40. Os dois ganchos conservam separação visível a 16 px; a associação ao produto continua abstrata.
- C: hastes do U encurtadas em 8 unidades. O C permanece visível, mas é secundário na redução a 16 px; reavaliar desenho específico para favicon caso esta direção seja escolhida.
- Nome: lettering original em curvas; inspecionado na assinatura horizontal da prancha.

Auditoria dos seis SVGs finais em audit-v2.txt: sem FAIL/WARN; símbolos A/B 100 e C 99/100. As diferenças de margens em C e nas assinaturas são informações geométricas, não certificações de qualidade. Nenhum SVG de logo depende de raster, filtro, máscara, stroke ou texto editável.

Inspeção visual: escala 16/20/24/32/48/64/96/128/256 px, ampliação de pixels, preto no branco, branco no preto, fundos de contraste, desfoque, espelho e rotação. Capturas qa-v2-a/b/c.png. Preto sobre preto deliberadamente não tem contraste na amostra original: aplicar a versão branca em fundos escuros. A reversão aqui é uma simulação de teste, não um arquivo final do kit.

Comparação de acabamento com Airbnb, Pagekit, Patreon e Framer: qa-peers.png. As três silhuetas não repetem as referências comparadas. Isso não constitui busca global de semelhança ou liberação jurídica de marca.

tests-v1.html e tests-v2.html foram gerados pela skill. As referências nesses HTMLs apontam à biblioteca local da skill; são folhas de trabalho internas. concepts.png é independente dessas referências e pode ser compartilhado.

Renderizadores: Chrome/Playwright para folhas HTML; Sharp/libvips para a imagem final da prancha SVG. A imagem final foi aberta e inspecionada após renderização. As ilustrações de interface nas folhas de teste são exemplos genéricos do script, não telas ou funcionalidades novas do UniCarona.

Não houve alteração do frontend, instalação de dependências ou execução de testes de backend, pois esta etapa adiciona apenas estudos de identidade em docs/brand/.
