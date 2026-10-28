# Execução e limites

## Demonstração

O caminho recomendado para avaliar o portfólio está no [README](../README.md#demonstração-local). `scripts/demo.mjs` cria um cluster PostgreSQL próprio quando `PG_BIN` aponta para os binários da versão 18. Não aceita conexão existente. Ele inicia o servidor Java do classpath de testes, com documentos, aceites e contas sintéticas.

Essa execução não entrega e-mails externos nem movimenta dinheiro. A senha pública das fixtures não deve ser configurada em uma instância própria. A URL é gerada em loopback. Os arquivos criados ficam fora da árvore pública e não entram no Git. Após uma interrupção forçada do sistema, um cluster de demonstração pode precisar ser encerrado manualmente: identifique o processo pelo diretório específico da execução, sem parar servidores de outros projetos.

## Aplicação normal

1. Crie um banco vazio PostgreSQL 18 para desenvolvimento. Use uma role própria e uma senha aleatória. As migrations iniciais precisam das extensões `pgcrypto`, `pg_trgm` e `unaccent`; um administrador pode pré-criá-las quando a role da aplicação não tiver essa permissão.
2. Copie `.env.example` para `.env` somente em um clone novo. Não sobrescreva configurações existentes.
3. Remova as linhas de opções não utilizadas, em vez de deixá-las vazias. Defina `DATABASE_URL`, `APP_ORIGIN`, `TOKEN_PEPPER`, `PIN_ENCRYPTION_KEY`, `JWT_SECRET` e as opções dos provedores. Use `development` para pagamentos e e-mails locais. A chave do PIN tem 64 caracteres hexadecimais; os segredos de assinatura devem ter pelo menos 32 bytes aleatórios. Armazene-os fora do Git e preserve as chaves necessárias para ler dados anteriores.
4. Compile o JAR, execute o modo `migrate` e, se desejar dados fictícios, o modo `seed`, usando os atalhos do README.
5. Inicie API e worker em terminais separados. O frontend é servido pelo backend, sem build Node.

Flyway aplica as migrations ao iniciar. Não renomeie nem edite migrations já aplicadas; novas mudanças exigem novas versões. Hibernate valida o schema, sem recriá-lo automaticamente. O seed é restrito a ambientes não produtivos, requer senha explícita e usa endereços `example.com`.

O catálogo em `backend-java/src/main/resources/legal/catalog.json` está em `draft`. A API pode iniciar nessa condição em desenvolvimento, mas comandos que exigem documentos e aceites permanecem bloqueados. Uma implantação própria precisa revisar os textos e seu contexto, definir a identificação real do responsável nas variáveis `LEGAL_*` e realizar publicação explícita. Não use a publicação sintética do harness para declarar conformidade ou prontidão comercial.

## Docker

`compose.yaml` descreve PostgreSQL, inicialização de seeds, API e worker. A imagem contém Java e as fontes do frontend, sem o antigo backend TypeScript ou Node no runtime. A porta da API é publicada somente em loopback. O banco não publica porta no host.

Os valores `PG_SUPERUSER_PASSWORD`, `PG_APP_PASSWORD`, `JAVA_DATABASE_URL`, chaves de aplicação e `SEED_PASSWORD` devem ser fornecidos localmente. A URL interna usa o serviço `db` na porta 5432 e a role `unicarona`, com senha codificada para URL. A role da aplicação não é superusuária.

A configuração Compose foi validada estaticamente na preparação do portfólio. Um novo build e uma inicialização dessa stack não foram executados nesta máquina por falta de daemon Docker acessível; a demonstração e a suíte Java foram verificadas com PostgreSQL nativo. Essa limitação não é apresentada como validação de deploy.

## Integrações e dados

- Mercado Pago: configure uma conta de testes própria. Não reutilize tokens de outro ambiente. A implementação inclui checkout, webhooks e conciliação; aprovação de marketplace e repasses reais continuam pendentes.
- E-mail: o provedor local grava mensagens no armazenamento privado. SMTP/Resend dependem de credenciais do titular. Nunca publique links de verificação ou recuperação.
- Assets: tiles OpenStreetMap exigem acesso à rede e mantêm atribuição. O mapa possui tratamento de indisponibilidade.
- Backup/PITR, retenções e auditoria são funcionalidades com requisitos operacionais específicos. Consulte os documentos de privacidade antes de configurar uma instalação com dados reais. O repositório público não contém cópias de dados nem chaves dessa instalação.
