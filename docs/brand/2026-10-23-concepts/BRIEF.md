# UniCarona — estudo de nova identidade

Data: 23/10/2026. Etapa: conceitos para escolha; kit e aplicação no produto aguardam direção escolhida.

## O que o projeto é

Plataforma independente para organizar caronas universitárias de ida e volta à UNICEPLAC. Conecta alunos passageiros e motoristas, com viagens avulsas e uma rotina mensal combinada. O vínculo entre colegas, os horários acadêmicos e a repetição do percurso são aspectos mais específicos do produto do que a simples oferta de transporte.

Fontes locais: PRODUCT.md; README.md; DESIGN.md; dist/index.html; dist/login.html; dist/dashboard.js; dist/assets/logo.jpg. Não houve leitura de credenciais ou dados pessoais. A pesquisa visual usa a biblioteca de referências da skill logo-design.

## Brief e premissas

- Nome: UniCarona, preservando a capitalização existente.
- Público: alunos adultos que oferecem ou procuram lugares em trajetos compatíveis.
- Personalidade proposta: próxima, clara, confiável, organizada, contemporânea.
- Promessa visual: pessoas que combinam um caminho e uma rotina.
- Uso prioritário: símbolo de 16–44 px, cabeçalho, acesso à conta, painel e avatar.
- Patrimônio existente: U, verde, base preto/branco; laranja é um acento já presente.
- Decisão de cor fica para a direção aprovada; primeiro avaliar a silhueta em preto e branco.
- A marca é independente da faculdade: evitar brasão, selo ou linguagem de endosso institucional.
- Não inferir GPS em tempo real, atendimento imediato ou identidade verificada a partir do símbolo.

## Diagnóstico da marca atual

O JPG reúne U, carro, três passageiros, capelo, estrada e pin. A imagem se comunica como ilustração, mas a hierarquia se comprime a 32–44 px. O fundo arredondado está incorporado ao bitmap e dificulta aplicações independentes. O favicon existente já usa um U simplificado, enquanto o cabeçalho usa a ilustração completa: falta uma mesma silhueta em todas as escalas.

Manter: nome, proximidade, percurso e possibilidade de continuidade com o U. Substituir: montagem de pictogramas, pormenores e dependência de fundo escuro.

## Estratégia e referências

Biblioteca consultada: travel-transport (apenas quatro entradas; amostra pequena), buscas por route, connection, letter-u e negative-space. Exemplos estudados: Airbnb (curva contínua), Relay (trajeto e nós), Pagekit (abertura deliberada), Patreon (peso entre haste e círculo) e Framer (economia geométrica). Referências de construção, sem traçado ou reutilização de seus desenhos.

Clichês a evitar: carro frontal, pin convencional, capelo, estrada tracejada, escudo de segurança e seta de velocidade. Cada proposta sustenta uma ideia sem depender desses elementos.

Mapa: Uni → U, união, turma; carona → percurso, encontro, lugar compartilhado; mensal → repetição, acordo, rotina; avulsa → autonomia, flexibilidade. Interseções escolhidas: letra/percurso; dois caminhos/encontro; iniciais/acolhimento.

## Dez ideias e seleção

Notas de 1 a 5: clareza / distinção / simplicidade / pertinência / redução. Julgamento de projeto, não pesquisa com usuários.

| Ideia | Tipo | Notas | Total |
|---|---|---|---|
| U com um terminal separado como ponto de encontro | Letra + percurso | 5/4/5/5/5 | 24 |
| Dois ganchos recíprocos conectam caminhos sem se fundir | Abstrato | 4/4/5/5/5 | 23 |
| C acolhido dentro de um U aberto | Monograma UC | 5/4/4/5/4 | 22 |
| Duas linhas convergem em um único trajeto | Abstrato | 4/3/5/5/4 | 21 |
| Palavra UniCarona com um arco ligando os dois a | Wordmark | 3/4/3/4/2 | 16 |
| U com dois terminais redondos simétricos | Letra | 5/2/5/4/5 | 21 |
| Duas cadeiras formam um U no vazio | Espaço negativo | 3/4/3/4/2 | 16 |
| Parênteses acolhem um trecho comum | Abstrato | 3/3/5/4/4 | 19 |
| C aberto em duas direções marca ida e volta | Letra | 3/3/5/4/4 | 19 |
| U construído como uma dobra de fita | Letra | 4/3/4/4/3 | 18 |

Construídas as três primeiras para comparar abordagens distintas.

## Construção

A — Percurso U: U em arcos concêntricos de 96/56 unidades, hastes de 40, terminal circular de diâmetro 40 destacado por 32 unidades. A separação vira a assinatura do desenho. Continuidade com a identidade existente; risco: o U isolado é uma forma comum.

B — Entre nós: dois caminhos de 40 unidades, cotovelos de raio externo 60 e interno 20, relacionados por rotação de 180 graus. A abertura central separa as duas partes. Comunidade e reciprocidade; risco: a leitura é mais abstrata e requer exposição junto ao nome.

C — UC Campus: U de raio externo 104/interno 64 e C interno de raio externo 56/interno 32. Intervalos de pelo menos 24 unidades entre os componentes. Assinatura compacta de iniciais; risco: o C interno é secundário a 16 px.

Assinaturas usam letras geométricas originais desenhadas em paths, sem fonte externa ou texto editável nos SVGs. A tipografia do material de apresentação usa fontes do sistema. Este estudo não atribui exclusividade jurídica às formas; antes de registro ou lançamento, recomenda-se busca de marca e semelhança visual profissional.

## Entrega desta etapa

Três conceitos vetoriais, assinaturas horizontais, visão comparativa e evidências de inspeção. Nenhuma substituição da logo foi feita no frontend. Após escolha: refino final, paleta, versões monocromática e invertida, assinaturas, redução, favicon/app/web icons, apresentação contextual e guia de uso.
