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
