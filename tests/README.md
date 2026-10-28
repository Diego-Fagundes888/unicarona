# Verificação Java e referências históricas

Os controles do cliente de privacidade e do leitor jurídico têm testes funcionais
isolados, sem navegador: `node --test tests/privacy-ui.test.mjs`. Eles verificam
versões dos aceites, escape de conteúdo, comandos sensíveis, renovação de sessão,
idempotência e exportação temporária. Não comprovam aparência ou interação real
em desktop/celular; essa inspeção continua sendo uma etapa separada.

Os comandos principais usam o backend Java:

`npm test` inclui `npm run lint`, `npm run test:client`,
`npm run test:tooling` e a verificação Maven. `npm test -- -LocalDatabase`
encaminha a seleção explícita do banco descartável para o helper Java.
`test:java` executa somente a etapa Maven; `test:unit` conserva os unitários Java.

```powershell
./backend-java/scripts/start-docker-local.ps1
npm test
npm run test:e2e
```

JUnit executa unitários, segurança, controllers, repositories, concorrência,
finanças, migrations e comparação com a implementação anterior. Os testes
usam PostgreSQL real via Testcontainers. A alternativa local explícita requer
`unicarona_java_test` em loopback; o reset recusa qualquer outro banco.

As jornadas em `backend-java/e2e` iniciam serviços Java reais. O navegador
usa o frontend existente e os controles de falha ficam somente no classpath
de testes. Não importam serviços do backend TypeScript. As capturas ficam
em `docs/previews/java-connected`. Node/tsx, pg, Playwright e sharp são
ferramentas de teste, não dependências da imagem ou do jar em produção.

As entradas que executavam o backend TypeScript foram removidas. Os contratos
fixos em `backend-java/src/test/resources/api/` não são regenerados pelos testes.
`JavaApplicationPeer` inicia outra instância Java para verificar sessões,
idempotência persistida e workers sobre o banco descartável compartilhado.
As expectativas financeiras e os estados esperados continuam explícitos nos
cenários de domínio. Cinco vetores fixos preservam os formatos históricos dos
fingerprints do ledger. As migrations históricas continuam verificadas pelo
Flyway, sem backend antigo.

`tests/legacy` preserva o protótipo em memória. Seus assets não são servidos
em `dist`, e seus testes não comprovam o backend conectado.
