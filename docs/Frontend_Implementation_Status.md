# UniCarona — acompanhamento da implementação visual

Status: **em andamento**. Este arquivo registra trabalho e evidências; não é o relatório final nem uma aprovação do produto.

## Escopo e critérios

O objetivo autorizado está em `<LOCAL_USER_HOME>/.codex/attachments/d9ee6468-74ee-4baa-b4b7-add30c708195/goal-objective.md`. Abrange todo o front-end, os estados existentes, desktop/notebook/tablet/mobile, preservação das regras e integração da API, oito passadas de revisão e uma avaliação final. O escopo permanece completo.

A auditoria inventariou 53 entradas, incluindo variantes de estado, distribuídas por quatro documentos HTML e rotas do painel. A cobertura individual é acompanhada em `docs/frontend-coverage.json`. Uma alteração compartilhada não comprova automaticamente a aprovação de cada tela.

## Evidências de referência

Pesquisa pelo navegador, conforme solicitado, em `<LOCAL_USER_HOME>`: auditoria completa, matriz de referências, galeria, 18 referências de seis produtos e capturas observadas. Os limites de acesso ao Mobbin estão declarados no material. Não houve acesso ao catálogo pago da Uber nem comprovação de hover/motion pelas imagens.

- Busca/início: M1/M2/M16 Uber, M12 BlaBlaCar; função principal antes de conteúdo secundário.
- Decisão de carona/pagamento/status: M4 Waymo, com adaptação ao aceite do motorista, às vagas e à contribuição do UniCarona.
- Carteira: M7; valor de uso principal separado dos demais estados financeiros.
- Perfil/configurações: M9/M10; agrupamento de dados e preferências.
- Ajuda/relatos: M6/M17; orientação contextual e recuperação. Nenhum canal de atendimento ao vivo foi inventado.
- Autenticação/onboarding: M3/M11; implementação aplicada, aprovação visual final pendente.

## Mudanças aplicadas até esta etapa

- `dist/ui.js`: componentes compartilhados de campos, selects, checkbox, formulários, grupos, avisos, resumos, disclosure, pessoa, trajeto, vazio, skeleton e botão ocupado. Valores de usuário escapados; regras e chamadas de API permanecem nos fluxos.
- `dist/design-system.css`: tokens semânticos, escala de espaçamento, tipografia operacional, estados e contraste dos controles. Correção de conflitos de especificidade com o shell.
- Início: busca primeiro no celular, mapa disponível por disclosure, próxima carona e contexto acadêmico separados das ações secundárias. Mapa real e atribuição preservados.
- Navegação: menu de conta por popover nativo, sem modal para simples navegação; voltar mantém o histórico interno inclusive após recarga.
- Resultados/detalhes: horário, trajeto e preço separados; filtros adaptados ao celular; motorista, encontro e próximo passo explícitos.
- Pedidos/reservas: ação seguinte contextual, agenda longa recolhida, PIN priorizado, ações secundárias de cancelamento/ausência/contestação por disclosure.
- Publicação/perfil: campos agrupados por tarefa, nomes acessíveis separados de dicas, upload com nome de arquivo e foco por teclado.
- Motorista: separação de trechos, solicitações e rotinas para evitar que uma agenda longa esconda a gestão mensal.
- Carteira: crédito ou saldo disponível dominante conforme o papel, demais valores em lista.
- Configurações/ajuda/notificações: preferências separadas de senha/bloqueios, ajuda por dúvidas reais, acesso ao destino de avisos quando o servidor fornece uma rota válida.
- HTML: versões de CSS/JS derivadas do conteúdo para evitar uso de arquivos desatualizados no navegador.

## Validações já observadas

Ambiente Java local isolado em `127.0.0.1:4186`, banco `unicarona_java_test`, contas/ofertas sintéticas. Nenhum pagamento real.

- Sintaxe de `ui.js`, `backend-ui.js`, `dashboard.js`: `node --check` passou após os ajustes desta etapa.
- Busca avulsa com ofertas reais da API sintética, detalhe, pedido pendente e aceite do motorista: observados pelo navegador.
- Perfil salvo e preferências salvas: confirmação visível da aplicação.
- Foco do arquivo por Tab: controle com foco e outline do sistema observados.
- Oferta alternada entre avulsa/mensal e ida/volta: interação observada; completar conferência dos campos condicionais no formulário específico.
- Renderizações de início, carteira, resultados, detalhes, perfil e configurações em desktop/mobile. Cobertura ainda incompleta em notebook/tablet, estados com valores não zero e demais rotas.

Capturas intermediárias: `<LOCAL_USER_HOME>`. Algumas capturas anteriores têm versões já substituídas. Recapturar os arquivos finais antes da aprovação e conferir as versões de assets carregadas; uma captura antiga não valida código posterior.

## Trabalho obrigatório restante no registro anterior (histórico)

1. Completar estados de checkout/reserva e fluxo de pagamento sintético, revisão de mensal/renovação/contrato/histórico e gestão de passageiros.
2. Implementar e revisar todas as telas administrativas, segurança/ocorrências, autenticação, onboarding, página pública e simulador.
3. Confirmar responsividade em desktop, notebook, tablet e mobile de cada entrada e os estados importantes, incluindo teclado, erro, loading, vazio e confirmação.
4. Executar testes de regressão apropriados; dez suítes existentes passaram entre a execução inicial e as reexecuções direcionadas; mudanças posteriores exigem os testes relacionados.
5. Corrigir falhas encontradas, realizar comparação global e completar as oito passadas com evidência.
6. Rodar detector final uma vez; revisão independente final da Impeccable com capturas válidas; documentar o sistema final com `DESIGN.md` e `.impeccable/design.json`.
7. Só então produzir o relatório final e marcar o objetivo completo.

## Continuidade

A etapa anterior foi **progresso**: código e evidências mudaram. Reinício externo do ambiente encerrou processos/abas; os arquivos foram preservados. Retomar processos apenas após verificar que os handles/ports anteriores realmente não estão ativos. Manter o objetivo ativo enquanto houver requisitos sem evidência.

## Atualização de implementação e testes — 05/10/2026

- Autenticação: controles do sistema, agrupamento pessoal/acesso/academia, progresso após título, links de regras e dados antes do aceite, ícones compartilhados. Corrigido carregamento do catálogo ao abrir/recarregar diretamente o perfil acadêmico. Recuperação e confirmação preservam os comportamentos da API.
- Histórico: registros agrupados por mês, com data/horário/trajeto/valor. O endpoint inclui reservas programadas; a interface declara essa abrangência.
- Mensal: prévia da agenda e valor, cabeçalho do contrato, informações contratuais antes da agenda no celular, confirmações e análises visíveis antes de próximos trechos recolhidos. Direitos, carência e renovação preservados.
- Passageiros: informações do trecho antes da lista; operação de embarque vinculada à data e status; reagendamento/cancelamento por disclosure.
- Segurança: contexto da reserva, protocolo persistente depois do relato e na recarga, registros separados de bloqueios e cuidados.
- Administração: navegação lateral no desktop e disclosure no celular, filas em registros planos, títulos específicos, conciliação com feedback conforme diferença real. Formulários e confirmação de decisões preservados.
- Página pública: navegação para quatro seções, CTA real para Minhas caronas, menu móvel nativo, foco restaurado ao fechar regras, continuidade monocromática ao longo da página. Capturas atuais dos quatro formatos listadas na cobertura.
- Pagamento de teste: tokens/componentes compartilhados, skeleton, identificador inválido com orientação, feedback ocupado/erro. A restrição a ambiente de testes foi preservada.
- Ícones de homepage e painel agora vêm de `ui.js`, em vez de dois mapas privados duplicados.

Testes: seis suítes passaram na primeira execução; `failures`, `layout`, `pagination` e `reservation` passaram após correções. O problema real encontrado foi o overflow de nomes longos sem espaços na saudação. Depois da mudança de navegação pública e ícones, `layout` (320/390/768/900/1024/1440) e `auth-navigation` passaram novamente. `signup-controls` precisou apenas atualizar o nome acessível do link Voltar, após substituir a seta textual por SVG; reexecução em andamento. Não confundir comportamento automatizado com inspeção visual final.

Todas as 53 entradas têm implementação aplicada e revisão final pendente. Os oito critérios de aprovação continuam abertos até a matriz final e a revisão independente. A captura anterior `30-results-long-name-320.png` foi rotulada incorretamente: o viewport real observado era 390 px; o teste automatizado de 320 px passou separadamente.


## Fecho — 09/10/2026

Este fecho sucede os registros históricos acima: as pendências de implementação/captura/cadastro/revisão ali descritas foram concluídas. Todas as 53 páginas e variantes foram implementadas e aprovadas nas oito passadas, dentro do alcance de frontend/evidência. A matriz possui 212 capturas primárias, 18 contact sheets e 106 entradas suplementares com possível reuso de primárias. Os índices guardam dimensões reais e hashes, com capturas das correções atualizadas.

O último material fix era o mapa externo indisponível: controlador próprio com Leaflet local e mesmo OpenStreetMap agora apresenta loading, demora, erro e recuperação, sem GPS ou mudanças financeiras. Cartografia real nos quatro formatos e saída externa foram observadas no navegador; testes automatizados bloqueiam tiles e verificam somente eventos/estados.

Regressão final: `node .local/run-frontend-e2e.mjs`, 11/11 passaram, zero falhas/skips/cancelamentos, 268.061 ms. Documentação normativa: schema v2, narrativa sincronizada, 13 hashes e 10 snippets validados. Detector executado uma vez; não foi repetido. Revisão integral preservada em `.impeccable/review/FULL_REVIEW.md`; verdict final em `INDEPENDENT_REVIEW.md`, material fix 1 resolved / ship, sem regressão material identificada.

Seis notas finais são 9/10; acessibilidade é 8,5/10 por falta de validação assistiva/aparelhos físicos, sem outro defeito concreto de código identificado. Pagamento continua sandbox, provedor comercial não escolhido, sem certificação comercial/assistiva ou alegação de aprovação por funcionários da Uber. Relatório completo: `docs/Frontend_Entrega.md`; cobertura: `docs/frontend-coverage.json`; pacote final: `.impeccable/review/FINAL_VALIDATION.json`.
