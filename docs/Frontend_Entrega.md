# UniCarona — entrega do frontend

**Status: CONCLUÍDO — 53 páginas e variantes aprovadas na revisão de frontend, dentro do alcance de evidência descrito abaixo.**

O frontend foi reorganizado nas 53 páginas e variantes identificadas pela auditoria. O padrão principal é a arquitetura de tarefas da Uber: busca clara, identificação antes da reserva, informação financeira organizada, próximo passo explícito e navegação coerente. Marca, contexto universitário e regras próprias do UniCarona permanecem.

## Antes e depois

Antes, a página pública perdia continuidade na rolagem, o painel misturava estilos, informações e métricas tinham pesos semelhantes e várias telas dependiam de formulários sem hierarquia. Agora, a página pública mantém uma composição contínua; o produto conectado usa o mesmo sistema de controles, listas, títulos, feedback e estados. Ações operacionais e informações secundárias têm prioridades diferentes.

O mapa tem papel adequado à largura. Reservas, solicitações, cobrança e contrato mantêm nomes e estados distintos. No mensal, total/renovação não ficam escondidos atrás de dezenas de trechos; confirmações e análises pendentes permanecem expostas. Arquivos de motorista conservam acesso privado. Os avisos reais abrem o pedido, reserva ou pagamento que lhes corresponde.

O mapa agora tem carregamento, demora e indisponibilidade próprios, com tentativa novamente e saída para o OpenStreetMap. A cartografia real foi observada nos quatro formatos; no celular, fica recolhida até a abertura pelo aluno. O campus é uma referência pública fixa, sem GPS, rastreamento ou cálculo de rota. A mudança conserva o provedor e a busca continua utilizável quando as imagens externas falham.

## Sistema compartilhado

- Tokens e componentes em `dist/design-system.css` e `dist/ui.js`: cores por função, escala de espaço 4/8/12/16/24/32/48/64, containers de 1152 px e formulários de 560 px.
- Arial/Helvetica, títulos operacionais de 32 px, subtítulos de 22 px e corpo de 16 px; valores financeiros com numerais tabulares. A tipografia preserva a direção existente.
- Preto/branco como base; verde e laranja universitários discretos; sucesso, aviso, erro e informação também identificados por texto/ícone.
- Botões/inputs com raio de 8 px, superfícies de 12 px e diálogos de 16 px; foco visível, alvos de 44–48 px, labels e hints fora das listas de opções.
- Formulários agrupados, pessoa/itinerário/resumo/disclosure/empty/loading compartilhados; ícones SVG centralizados em vez de mapas duplicados.
- Registros planos e separadores para dados; cards apenas onde a comparação e o agrupamento justificam. Sombra reservada às camadas sobrepostas.
- Diálogo para ações que exigem confirmação, com foco protegido/restaurado; menu/disclosures nativos para conteúdo secundário. Hover/disabled/ocupado/reduced-motion padronizados, sem animação de largura no progresso.
- Navegação superior no desktop e inferior nas larguras estreitas; sidebar administrativa vira disclosure. Mensal/renovação empilham até 1000 px, evitando resumo comprimido no tablet.

## Página por página

Os códigos M1–M18 remetem às referências reais abaixo. **M5 é agendamento, não contrato mensal; M10 são preferências, não um feed.** Publicação, mensal acadêmico e administração adaptam princípios e componentes comparáveis: a pesquisa não encontrou um equivalente direto desses fluxos na Uber. A comparação não atribui nossas regras de negócio aos produtos de referência.

| # | Página/variante | Mudança e melhoria de UX | Referência | Status |
|---|---|---|---|---|
| 1 | Página inicial pública | Continuidade monocromática na rolagem, navegação real, serviços alinhados, menu móvel e regras/privacidade com foco restaurado. | M2 | APROVADA |
| 2 | Entrar | Formulário de acesso estreito, labels e hierarquia uniforme, mostrar senha e feedback ocupado. | M11, M3 | APROVADA |
| 3 | Recuperar senha | Uma tarefa por tela; e-mail, instrução e ação de recuperação. | M11, M3 | APROVADA |
| 4 | Instruções de recuperação | Confirmação neutra, próxima etapa e alternativa para informar outro endereço. | M3 | APROVADA |
| 5 | Definir nova senha | Senha e confirmação agrupadas, requisito legível e erros associados aos campos. | M11, M3 | APROVADA |
| 6 | Senha alterada | Estado de sucesso com ação para voltar ao acesso e aviso das sessões encerradas. | M3 | APROVADA |
| 7 | Link de recuperação indisponível | Link indisponível com ação de solicitar outro, sem tela sem saída. | M3, M4 | APROVADA |
| 8 | Criar conta — dados pessoais | Dados e acesso separados; regras consultáveis antes do aceite; progresso depois do título. | M11, M3 | APROVADA |
| 9 | Criar conta — perfil acadêmico | Curso por catálogo, período numérico e turno obrigatórios, turma A/B/C opcional e ajuda recolhida; catálogo ao abrir diretamente. | M3, M11 | APROVADA |
| 10 | Criar conta — confirmar e-mail | Endereço e próxima etapa no cadastro normal; recarga sem rascunho permite solicitar outro link. | M3, M11 | APROVADA |
| 11 | Verificando e-mail | Carregamento visual sem indicar confirmação antes da resposta real. | M3, M4 | APROVADA |
| 12 | E-mail confirmado | Confirmação de e-mail separada de comprovação acadêmica, com retorno ao login. | M3 | APROVADA |
| 13 | Verificação de e-mail inválida | Motivo compreensível e formulário para solicitar novo link. | M3, M4 | APROVADA |
| 14 | Dashboard — início | Busca como tarefa principal, mapa secundário no celular, próxima carona e contexto acadêmico. | M1, M2, M13, M14, M15, M16 | APROVADA |
| 15 | Buscar carona | Origem/destino, sentido, modalidade e data agrupados; agenda e mapa recolhidos no celular. | M1, M12, M16 | APROVADA |
| 16 | Resultados — avulsas | Horário, trajeto e contribuição primeiro; metadados reduzidos; filtros adaptados à largura. | M12, M4 | APROVADA |
| 17 | Resultados — mensais | Rotina, compatibilidade acadêmica, dias e ação para conferir o mensal. | M12, M4 | APROVADA |
| 18 | Detalhe de carona avulsa | Encontro e identificação antes da solicitação; valor e ação separados dos dados secundários. | M4, M12 | APROVADA |
| 19 | Mensal — configuração e prévia | Resumo, escolha da agenda e prévia de valor separados; grid empilhada no tablet para evitar compressão. | M4, M5 | APROVADA |
| 20 | Renovar mensal | Renovação utiliza a mesma composição de agenda/prévia, preservando novo aceite e pagamento. | M4, M5 | APROVADA |
| 21 | Solicitação — passageiro | Estado do pedido, agenda e próximo passo explícitos; aceite não aparece como reserva paga. | M4 | APROVADA |
| 22 | Checkout — início, pendência e sucesso | Distingue não iniciado/pendente/confirmado; crédito previsto e valor externo sem zero fictício; retry. | M4, M18 | APROVADA |
| 23 | Reserva — avulsa e mensal | Trecho, motorista e PIN contextual; confirmação final, contestação e ajustes têm ações próprias. | M4, M16 | APROVADA |
| 24 | Contrato mensal | Total, renovação e direitos antes da agenda em telas estreitas; trechos que pedem atenção permanecem visíveis. | M5, M8 | APROVADA |
| 25 | Minhas — reservas | Reservas para acompanhar primeiro, demais registros por disclosure; tabs e vazio contextual. | M8, M4 | APROVADA |
| 26 | Minhas — pedidos | Lista de pedidos com estado, modalidade, valor e acesso ao detalhe. | M8, M4 | APROVADA |
| 27 | Minhas — mensais | Lista de contratos com período e estado; ação consistente para gerenciamento. | M8, M5 | APROVADA |
| 28 | Minhas — ofertas | Ofertas em registros planos com horário/rota/valor e acesso ao trecho. | M8, M12 | APROVADA |
| 29 | Histórico | Agrupamento mensal cronológico, data/horário/trajeto/valor; explica que inclui programadas. | M8 | APROVADA |
| 30 | Motorista — inscrição/análise | Veículo e documentos em grupos, arquivos com labels/foco/nome; estado de análise explícito. | M3, M9 | APROVADA |
| 31 | Motorista — painel aprovado | Área do motorista com publicar, solicitações, recebimentos e três categorias de registros. | M8, M4 | APROVADA |
| 32 | Publicar avulsa | Trajeto, saída, contribuição e combinação agrupados; orientação ao lado no desktop e abaixo no celular. | M4, M12 | APROVADA |
| 33 | Publicar mensal | Mesma estrutura compartilhada, com dias/horários da rotina; sem duplicar regras de publicação. | M5, M4 | APROVADA |
| 34 | Editar oferta | Edição concentrada em vagas/contribuição/preferências, com efeito sobre novas contratações legível. | M4 | APROVADA |
| 35 | Pedidos recebidos — motorista | Pedidos reais em registros planos com agenda e decisão, além de vazio específico para a fila. | M4, M8 | APROVADA |
| 36 | Gerenciar trecho/passageiros | Contexto do trecho antes dos passageiros; embarque/PIN vinculados ao dia e estado; ajustes secundários recolhidos. | M4, M16 | APROVADA |
| 37 | Carteira | Crédito do passageiro ou disponível do motorista com prioridade; composição financeira em lista, sem grade de métricas. | M7, M18 | APROVADA |
| 38 | Perfil | Dados pessoais e acadêmicos separados; vínculo declarado/validado explícito e confirmação de impacto no mensal. | M9, M3 | APROVADA |
| 39 | Configurações | Preferências primeiro; senha/bloqueios recolhidos; explicação do que os avisos abrangem. | M9, M10 | APROVADA |
| 40 | Segurança/ocorrências | Relato, protocolo persistente, registros, bloqueios e cuidados separados; emergência sem promessa de monitoramento. | M6, M4 | APROVADA |
| 41 | Relatar ocorrência | Pessoa/trecho/data já contextualizados; relato e bloqueio com explicação do efeito e recibo posterior. | M6, M17 | APROVADA |
| 42 | Notificações | Avisos em lista legível; Abrir aviso normaliza destinos reais da API; lido e não lido explícitos. | M8, M10 | APROVADA |
| 43 | Ajuda | Seis respostas reais por disclosure e orientações ligadas aos fluxos do produto. | M6 | APROVADA |
| 44 | Simulador de pagamento | Sistema compartilhado, loading, inválido, pendente e resultado; restrito ao sandbox. | M18, M4 | APROVADA |
| 45 | Admin — motoristas | Fila plana com veículo, documentos privados, decisão e justificativa; sidebar/disclosure por largura. | M9, M8 | APROVADA |
| 46 | Admin — ocorrências | Relatos com estado, envolvidos e parcelas afetadas; acesso à apuração sem cards decorativos. | M6, M8 | APROVADA |
| 47 | Admin — apuração | Detalhes do relato, decisões por parcela e justificativas; conserva destino de parcelas encerradas e outros bloqueios. | M6, M18 | APROVADA |
| 48 | Admin — contas | Busca/filtro de contas, registros planos e ação de revisão com efeitos explicitados. | M9, M8 | APROVADA |
| 49 | Admin — vínculo acadêmico | Vínculo acadêmico e decisão por registro, separados da confirmação de e-mail. | M9, M3 | APROVADA |
| 50 | Admin — calendário | Cadastro e consulta do calendário separados; comunica efeito sobre novas contratações. | M5, M8 | APROVADA |
| 51 | Admin — parâmetros | Nova versão e histórico em grupos distintos; parâmetros legíveis e escopo de aplicação explicado. | M18, M8 | APROVADA |
| 52 | Admin — conciliação | Valores em lista; feedback positivo só quando recebido/distribuído conferem, alerta quando há diferença. | M7, M18 | APROVADA |
| 53 | Admin — tarefas de envio | Resumo de estados, filtro, fila e explicação de retomada; vazio específico e decisões preservadas. | M8, M6 | APROVADA |

## Problemas eliminados

Continuidade visual perdida na rolagem; duplicação de ícones; controles/raios/cores inconsistentes; conteúdo de cadastro sem agrupamento; períodos e turno ambíguos; catálogo que não carregava ao abrir diretamente a etapa acadêmica; confirmação de e-mail sem endereço após recarga; saudação longa causando overflow; resumo mensal comprimido no tablet; notificações reais sem deep link; valor externo fictício antes de iniciar cobrança; urgências mensais escondidas entre trechos; carregamento/erro/vazio sem contexto; mapa indisponível com ícone bruto de documento quebrado.

## Validação

212 capturas obrigatórias indexam 53 páginas e variantes × 4 formatos. Há 106 entradas no índice suplementar, com reuso de algumas imagens primárias, incluindo cadastro efetivo, confirmação após recarga, pedido pendente de motorista, controles inferiores, confirmação final, agenda, menu/privacidade, estados financeiros e mapa normal/indisponível. A galeria liga aos originais; contact sheets não substituem a avaliação em tamanho original. As capturas são de 05/10 e 09/10/2026; as fontes e imagens alteradas na correção final foram revalidadas.

A regressão final conectada passou **11/11 em 09/10/2026**, em 268.061 ms, sem falhas, cancelamentos ou testes pulados. O comando foi `node .local/run-frontend-e2e.mjs`. Os testes exercitam API Java/PostgreSQL isolados, dados sintéticos, teclado, validações, cadastro, recuperação, documentos privados, aprovação, resposta perdida, offline, paginação, pagamento, embarque, crédito, carência, mensal e contestação. O novo teste do mapa verifica demora, falha, recuperação, controles por teclado e continuidade da busca, bloqueando tiles externos. A prova da cartografia normal veio do navegador, com imagens reais, e não do pixel local usado no teste de recuperação.

Layout inclui 320/390/768/900/1024/1440 px; a matriz visual principal usa desktop 1440×900, notebook 1280×720, tablet 768×1024 e mobile 390×844. O raster da captura AX pode diferir da viewport DOM; as dimensões reais estão no índice. Capturas de elementos/full-page podem registrar navegação fixa no meio da imagem: isso é interpretado junto ao contexto e à inspeção com rolagem, sem tratá-lo automaticamente como corte real.

O detector Impeccable foi executado uma vez. Achados mecânicos corrigidos: borda lateral de 3 px no protótipo legado e transição de largura. A execução retornou código 1 com dez achados; isso não é certificado de cobertura integral. Avisos de fonte/padding foram submetidos ao julgamento visual e ao contrato da direção. `DESIGN.md` e o sidecar foram extraídos e revalidados: schema v2, narrativa sincronizada, 13 hashes atuais e 10 snippets.

## Quality Review final

A revisão integral examinou 53 entradas em quatro formatos, os 18 contact sheets, originais críticos e estados suplementares. Confirmou as oito passadas e pediu uma última correção material no mapa indisponível. O verdict posterior confirmou essa correção como **resolved**, sem regressão material observada; disposição **ship** para o lote corrigido. A aprovação final combina essa revisão integral com o verdict da correção, sem repetir a auditoria inteira como se fosse nova.

| Critério | Nota /10 |
|---|---:|
| Design visual | 9 |
| UX | 9 |
| Consistência | 9 |
| Responsividade | 9 |
| Acessibilidade | 8,5 |
| Qualidade de implementação | 9 |
| Proximidade com produto profissional | 9 |

A nota de acessibilidade permanece abaixo de 9 porque leitor de tela, aparelho físico e teclado virtual não foram verificados. Semântica, nomes acessíveis, foco e teclado têm evidência; não foi identificado outro defeito concreto de código que justificasse mudança especulativa. A etapa restante é validação assistiva real. Essas notas são julgamento de revisão da interface e das evidências disponíveis, sem selo de conformidade, aprovação comercial ou revisão por funcionários das empresas de referência.

As 53 entradas têm os oito gates concluídos em `docs/frontend-coverage.json`. A revisão integral foi preservada em `.impeccable/review/FULL_REVIEW.md`; o verdict está em `INDEPENDENT_REVIEW.md` e os resultados/hashes em `FINAL_VALIDATION.json`. A ação Abrir mapa abriu a aba do OpenStreetMap com a região pública correta, observada e capturada no navegador.

## Limites práticos

Pagamento permanece em ambiente de testes e o provedor comercial não foi escolhido. Não há dinheiro real nas capturas. A interface não promete GPS ao vivo, suporte em tempo real ou vínculo oficial com a faculdade. Leitor de tela, aparelhos físicos e browsers alternativos não foram validados; a evidência de acessibilidade cobre semântica, nomes, foco e teclado nos testes executados. O redesign preserva regras da API; não constitui certificação jurídica, segurança de produção ou autorização para operar financeiramente.

## Referências utilizadas

Pesquisa realizada no Mobbin pelo navegador, conforme pedido do usuário. Foram 18 referências de 6 produtos, 11 fluxos completos, 39 capturas reais e 2 registros de acesso. As 67 posições percorridas incluem repetições, não 67 interfaces distintas. O catálogo Pro da Uber não foi contornado. A data de coleta é 05/10/2026; isso não torna uma versão histórica do catálogo a versão atual do aplicativo.

- **M1**: [Uber iOS — Home](https://mobbin.com/screens/58e88acd-c7a4-40f4-98a5-f9a1911dcae5)
- **M2**: [Uber Web — Home](https://mobbin.com/screens/93069522-5003-479c-8904-f607db0a3d74)
- **M3**: [Waymo — Onboarding](https://mobbin.com/flows/a75d54b5-0e7c-496f-a624-d5a7ba385c9a)
- **M4**: [Waymo — Booking](https://mobbin.com/flows/cc12cfa3-b0c0-4087-a293-f9eb23aa9333)
- **M5**: [Waymo — Schedule](https://mobbin.com/flows/21d09e8b-2c46-44b8-9b21-e30e4b080509)
- **M6**: [Waymo — Help](https://mobbin.com/flows/6d4aba2c-514e-4cea-a382-81fe9c21e4f7)
- **M7**: [Waymo — Wallet](https://mobbin.com/flows/44cede3c-813f-467a-b8b7-375e05349a1d)
- **M8**: [Waymo — History](https://mobbin.com/flows/fe7aebdd-588d-4381-a2fc-1b712436c9bd)
- **M9**: [Waymo — Profile](https://mobbin.com/flows/a083e36d-a595-4f6c-ad2d-1d41c5e469d0)
- **M10**: [Waymo — Notification preferences](https://mobbin.com/flows/72521b15-2434-4af7-81f5-0f7963a9af83)
- **M11**: [Uber — Onboarding (versão histórica)](https://mobbin.com/flows/3fe8ec28-3104-4ad4-a8fd-6067ed54d533)
- **M12**: [BlaBlaCar — Search](https://mobbin.com/screens/0729a3ab-c602-4671-a145-974f6d576fb1)
- **M13**: [Bolt — Home](https://mobbin.com/screens/419aa873-63df-4841-8e39-ffc7bf918f90)
- **M14**: [Lyft — Home](https://mobbin.com/screens/3c59ccff-1c5d-443b-9540-c18533f8d9a0)
- **M15**: [Grab — Transport](https://mobbin.com/screens/4a5f6894-adcf-4e2a-8cc9-9d8dbb9ca179)
- **M16**: [Uber — Map Home](https://mobbin.com/screens/c020d886-e55f-4eda-8110-acbd6399418d)
- **M17**: [Waymo — Feedback](https://mobbin.com/flows/ad082ab5-bb8a-401c-bfe8-a847222b4238)
- **M18**: [Waymo — Payment](https://mobbin.com/flows/0e7504e6-86b4-4ee5-8c7b-7eca902ddd95)

O mapa utiliza a versão estável [Leaflet 1.9.4](https://leafletjs.com/download.html), com licença incluída e distribuição local. URL de tiles, atribuição, referrer e cache seguem a [política de tiles do OpenStreetMap](https://operations.osmfoundation.org/policies/tiles/); não há prefetch, download em lote ou mapa offline. A disponibilidade do serviço externo continua sendo uma dependência real.

## Arquivos de evidência

- Auditoria e pesquisa: `<LOCAL_USER_HOME>`.
- Galeria: `<LOCAL_USER_HOME>`.
- Matriz e revisão independente: `.impeccable/review/capture-index.json`, `supplemental-index.json` e `INDEPENDENT_REVIEW.md`.
- Cobertura e oito passadas: `docs/frontend-coverage.json`.
- Contexto de produto: `PRODUCT.md`; documentação normativa do sistema: `DESIGN.md` e `.impeccable/design.json`, extraídos e revalidados em 09/10/2026.
