# Medição local

> Benchmark histórico do backend TypeScript retirado. Seus comandos não se aplicam
> ao projeto atual, e essas medições não representam o desempenho Java.
> Veja [Retirada_Backend_TypeScript.md](../Retirada_Backend_TypeScript.md).

Execute `npm run test:performance` separado das outras suítes: ele reconstitui
o banco isolado `unicarona_test`. São 5 mil perfis, 20 motoristas, 3 mil ofertas,
3 mil avisos e 10 reservas pagas. Todas as requisições verificadas passaram por
Fastify, autorização, DTOs e PostgreSQL; confirmações de pagamento usam assinatura
do gateway local. Senhas, tokens, PINs e parâmetros SQL não são registrados.

`latest.json` contém a última execução. Tempos incluem autenticação, rate limit,
validação e banco. A injeção Fastify exclui transporte HTTP e navegador. O número
de consultas inclui BEGIN/COMMIT e locks. As cinco requisições simultâneas têm
contagem agregada, pois compartilham a janela de instrumentação.

A busca mantém quatro chamadas SQL ao passar de 30 para 100 resultados. Isso
verifica ausência de uma chamada por resultado nesses endpoints; não prova custo
constante dentro do PostgreSQL. O plano da consulta inclui subconsultas de vagas
e precisa ser revisitado conforme crescer a ocupação. Neste conjunto com muitas
ofertas na mesma data/faculdade, o planner pode preferir scans sequenciais; o
relatório apresenta somente os índices realmente usados no plano observado.

Há poucas reservas pagas comparadas à quantidade de perfis e ofertas. O teste
não cobre pico prolongado, infraestrutura de produção, HTTP externo, cache frio,
distribuição geográfica, milhares de confirmações simultâneas ou SLA contratado.
As amostras de login e fluxos financeiros são pequenas. Serve como referência
reproduzível para detectar aumento de consultas e investigar alterações lentas.
