# Preparação pública

## Escopo da auditoria

A revisão anterior ao primeiro push cobre os arquivos candidatos, configurações ocultas, 17 commits locais e 1.163 blobs alcançáveis. Foram combinadas busca por padrões, Gitleaks 8.30.1 e comparação com valores de credenciais existentes na configuração privada, sem imprimir seus valores. Relatórios detalhados permanecem em armazenamento privado.

Foram encontradas referências a contato pessoal e caminhos da estação de desenvolvimento. Elas foram substituídas por placeholders ou caminhos portáveis. A comparação não encontrou os valores das credenciais configuradas no conteúdo versionado. As ocorrências do Gitleaks nos fontes eram fixtures sintéticas de teste; suas exceções são restritas e identificadas, incluindo um vetor criptográfico fixo. Nomes como `password`, `token` e `secret` continuam necessários nos contratos e validações.

Como os dados pessoais também apareciam em commits anteriores, a publicação parte de uma nova raiz revisada. O histórico original de trabalho é preservado somente localmente, para auditoria e recuperação. Não há envio de branches antigas ou tags com esses conteúdos. Isso evita publicar os blobs antigos sem apagar o histórico local.

## Exclusões

- Ambientes e valores privados, dados PostgreSQL, backups, uploads, caixa de e-mail e logs.
- `.local/`, dependências Node, `target/`, relatórios de teste e capturas automáticas.
- Configuração de hospedagem `.openai/`, metadados de ferramentas e editores.
- PDF duplicado da especificação, ZIP exportado da marca e screenshots históricos de integração, preservados localmente. O Markdown e os fontes da marca continuam versionados.

`dist/` permanece público porque contém os fontes do frontend. A licença e os arquivos necessários ao Leaflet também são preservados. Todos os exemplos `.env.example` usam valores vazios; a demonstração gera credenciais novas de banco por execução.

## Limites

A revisão reduz o risco de publicação indevida, sem prometer ausência absoluta de vulnerabilidades. Não houve detecção de credencial real que exigisse revogação nesta preparação. Se alguma credencial tiver sido compartilhada anteriormente por outro canal, seu titular deve revogá-la no provedor. Não foram feitas chamadas financeiras reais, envio de e-mails reais ou alteração dos dados da instalação privada.

O projeto permanece sem licença própria, conforme a escolha do autor. O CI não declara cobertura total nem prontidão para produção. A operação comercial, o catálogo jurídico, a homologação dos repasses e o deploy hospedado continuam pendentes.

## Verificações de apresentação e execução

- 918 arquivos na árvore candidata, sem ambientes reais, dumps, logs, builds ou uploads.
- 30 arquivos retirados do versionamento, com cópias locais preservadas; 11 arquivos adicionados para documentação, imagens, CI e demonstração.
- 89 assets raster verificados quanto a metadados; nenhum EXIF, XMP ou comentário exigiu remoção. O QR da fixture contém somente texto sintético, sem dados de pagamento.
- Três screenshots capturados da aplicação Java com dados fictícios e revisados visualmente: início, login e Minhas caronas.
- Links locais do README e da nova documentação verificados sem destinos ausentes.
- `npm ci`, build Maven e demonstração verificados em cópia limpa, sem ambiente privado.
- Build, migrations, seed, API e worker verificados com configuração própria e banco PostgreSQL novo. O catálogo jurídico em rascunho responde 503; a disponibilidade técnica da API não habilita publicação jurídica.
- Lint aprovado; 52 testes do cliente e 1 das ferramentas passaram; auditoria npm sem vulnerabilidades reportadas.
- 13 jornadas Playwright passaram, sem falhas ou skips.
- Alteração final do contato público revalidada em cópia limpa: 15 testes de integração jurídica e 3 unitários do provider de e-mail, com Maven BUILD SUCCESS.

O workflow usa ações oficiais fixadas por SHA e permissão de leitura do código. Inclui lint, cliente/ferramentas, testes unitários Java e empacotamento. Não executa deploy nem usa credenciais de provedores. A execução completa de integração e navegador depende de PostgreSQL e dos utilitários nativos descritos em QUALIDADE.md.

O JAR produzido na cópia limpa foi inspecionado: 47 migrations SQL e ausência das classes de controle e fixtures do harness de testes. A imagem de produção continua sem Node e sem o servidor TypeScript retirado.

## Execução completa de 28/10/2026

Maven `clean verify`: **196 testes unitários + 747 testes de integração**, sem falhas, erros ou skips, com `BUILD SUCCESS`. Foram usados PostgreSQL 18 nativo e clusters descartáveis, incluindo os ensaios físicos de restauração e PITR. A verificação dirigida dos arquivos alterados na preparação pública e as 13 jornadas de navegador foram executadas adicionalmente sobre a cópia limpa. Esses números não são percentual de cobertura.
