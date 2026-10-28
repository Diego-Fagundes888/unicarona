# Qualidade e verificação

Os números abaixo contam casos executados, não percentual de cobertura. Fixtures de e-mail, pagamentos, contas e documentos são fictícias e não se conectam a provedores reais.

| Camada | Como é verificada |
|---|---|
| Fontes do cliente | Sintaxe JavaScript, TypeScript das ferramentas e hashes de cache |
| Cliente e contratos | Node Test Runner: fluxos de privacidade/administração, erros, idempotência e OpenAPI |
| Ferramentas | Resolução de ambientes privados e rejeição de configurações inconsistentes |
| Java unitário | JUnit: regras, validações, criptografia, providers e modelos |
| Java integração | PostgreSQL real: contratos HTTP, permissões, concorrência, finanças, migrations e privacidade |
| Recuperação | Ferramentas nativas PostgreSQL: dump/restore, base backup e WAL/PITR em clusters descartáveis |
| Navegador | Playwright contra aplicação Java, com cadastro, reservas, mensalidades, estados de falha e layout |

`npm run test:java` usa Testcontainers por padrão. Não há fallback silencioso para banco em memória. A alternativa explícita `TEST_JAVA_DATABASE_URL` aceita apenas loopback e banco `unicarona_java_test`; os testes apagam seu schema. `TEST_PG_TOOLS_PATH` precisa conter os utilitários PostgreSQL 18 para os casos físicos e PITR. O usuário do sistema precisa poder iniciar clusters novos; `initdb` não roda como root.

Os atalhos Maven locais encaminham argumentos sem construir uma string de shell. `npm test` reúne lint, testes do cliente/ferramentas e Maven verify. O CI público executa as etapas portáveis e unitárias; testes de integração e Playwright são verificações separadas enquanto não houver runner preparado para todos os requisitos.

As jornadas de navegador compilam as classes de teste e criam seu classpath por `backend-java/scripts/e2e-local.ps1`. Capturas de execução ficam em `docs/previews/`, ignorado. `docs/images/` contém somente a seleção revisada para apresentação.

A migração preserva contratos HTTP fixos e vetores criptográficos, além de comparar instâncias Java independentes para sessões, idempotência e workers. As fixtures e controles do harness não são incluídos no JAR de produção. Os relatórios históricos de retirada do TypeScript informam resultados e falhas corrigidas de suas datas; não representam uma medição de cobertura.

Os resultados da preparação pública estão em [PUBLICACAO.md](PUBLICACAO.md).
