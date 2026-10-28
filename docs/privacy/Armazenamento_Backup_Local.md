# Armazenamento privado e recuperação local

Estado da etapa em 17/10/2026: o runtime nativo de demonstração foi migrado do volume D: sem ACL persistente para `%LOCALAPPDATA%/UniCarona/Private`, em NTFS. Essa mudança operacional preserva o jar imutável V26 e não publica as alterações V27–V32 nem suas minutas. A interface/aceites ainda estão em implementação.

## Separação efetiva

| Conteúdo | Destino privado | Controle |
|---|---|---|
| Configuração da aplicação | `config/app.env` | Somente conta Windows atual, SYSTEM e Administradores |
| Configuração Mercado Pago de teste | `config/mp-sandbox.env` | Mesmo controle; sem credenciais em argumento de processo |
| Configuração Compose | `config/docker.env` | Mesmo controle; Compose mantém seus caminhos internos de container |
| Credencial do operador PostgreSQL local | `config/pg-operator.pass` | Fora do repositório e dos argumentos de ferramentas |
| PostgreSQL nativo | `data/postgres` | Cópia a frio; serviço separado em 5432 preservado |
| Uploads / correio de desenvolvimento | `data/storage`, `data/mp-sandbox-storage`, `data/mail` | Acesso privado; APIs mantêm autorização por titular/finalidade |
| Diário independente de exclusões | `data/erasure-journal` | Fora do banco e do diretório de uploads; não restaurar uma cópia antiga sobre o diário atual |
| Java, Maven, PostgreSQL, jar e frontend da demonstração | `runtime/` | Execução nativa por arquivos protegidos e jar identificado por SHA256 |
| Logs atuais / evidência da migração | `logs/`, `archives/` | Não servidos por HTTP; relatos públicos limitados a metadados |
| Backup inicial nativo | `backups/migration-baseline/postgres` | Separado da cópia ativa e do ensaio de restauração |

`.env` na raiz agora contém **somente** `UNICARONA_PRIVATE_ROOT=...`; `.local/private-runtime.json` é um ponteiro sem credenciais. Não salvar novos tokens/senhas na raiz. Alterações de credenciais devem ocorrer no arquivo efetivo em `Private/config/`. Os resolvers recusam manifesto inválido, arquivo/diretório de configuração redirecionado e configuração novamente inserida no `.env` público.

O inicializador instalado em `Private/runtime/scripts/start-protected-sandbox-local.ps1` usa os executáveis, o jar, a configuração e os assets privados. Seu diretório de trabalho também é privado. O código de desenvolvimento continua no D: e deve ser considerado entrada confiável somente durante desenvolvimento; esta migração não transforma o computador compartilhado ou o checkout em um host de produção endurecido. Administradores e a conta do operador continuam podendo acessar os dados. ACL não equivale a criptografia integral do volume.

## Evidências já verificadas

1. API e worker antigos encerraram por sinal de console em consoles isolados, com validação dos processos participantes. Não foi usado encerramento forçado de Java. O ponto de controle encontrou zero jobs em processamento.
2. PostgreSQL de desenvolvimento foi encerrado com `pg_ctl stop -m fast`; nenhuma inicialização, seed ou reset ocorreu nos bancos da demonstração/legado.
3. Cópias verificadas por SHA256 por arquivo. O manifesto privado `archives/cold-copy-manifest.json` registra 13.368 cópias entre cópia ativa, backup, ensaio e arquivos de operação; são cópias, não arquivos distintos.
4. Uma terceira cópia iniciou em `127.0.0.1:55433`. Comparação de todas as tabelas públicas por contagem e impressão do conteúdo confirmou os três bancos presentes, inclusive o sandbox V26. Essa instância foi novamente encerrada normalmente.
5. A cópia ativa iniciou em `127.0.0.1:55432` com o mesmo conteúdo. O serviço Windows independente em 5432 foi preservado. `archives/migration/{before,clone,after}.json` registra a comparação sem linhas pessoais no relatório público.
6. API `/health/ready` respondeu `ready`, API e worker passaram a executar pelo Java privado e o ledger da demonstração permaneceu equilibrado. A versão da demonstração permaneceu 26.
7. Após essas verificações, 5.395 originais verificados, em nove diretórios e 683 arquivos de evidência na raiz de `.local`, foram retirados do D:. O ambiente original tem uma cópia privada verificada; os arquivos de evidência antigos estão em `archives/legacy-local/`, conservando o caminho relativo. Referências históricas `.local/arquivo.log` nos documentos correspondem a esse arquivo arquivado. Nenhuma mensagem de e-mail privada foi aberta no navegador nesta etapa.
8. A primeira verificação integral de ACL examinou 15.931 caminhos sem permissões Allow para terceiros fora dos três sujeitos autorizados. Essa é uma contagem da primeira rodada; a checagem atual deve ser repetida após arquivos novos.
9. Foram preservadas cópias verificadas dos 516 relatórios Java históricos em `archives/pre-migration-test-reports/`. A retirada recursiva inicial foi recusada pela revisão automática. A alternativa aprovada limitou a remoção a cada arquivo com hash idêntico à cópia protegida, preservando diretórios e qualquer arquivo alterado. Antes da retirada, a varredura dos 516 relatórios encontrou zero valores dos segredos atualmente configurados; isso não é prova de ausência de toda informação pessoal ou segredo antigo.
10. Os próximos relatórios Surefire/Failsafe e temporários de testes usam a pasta privada. O POM exige testes efetivos: uma execução diagnóstica mostrou `BUILD SUCCESS` com zero testes após um erro de escrita de relatório; não foi contabilizada como aprovação. O erro de montagem dos argumentos do launcher foi corrigido, e as rodadas válidas têm contagens explícitas.
11. Após a prova, a terceira cópia temporária de PostgreSQL (`data/restore-proof`, 3.349 arquivos) foi retirada por `retire-restore-rehearsal-local.ps1`, verificando instância parada, origem/destino, ausência de links e os dois relatos de recuperação. A cópia ativa e o backup a frio independente foram preservados; o cluster ativo permaneceu pronto. Esse descarte pontual não substitui a rotina de expurgo dos backups retidos.

O disco do WSL também foi movido, offline, para `data/wsl-Docker`. O script `relocate-private-wsl-local.ps1` validou nome, registro, origem/destino, permissões e SHA256 do VHDX, usando `wsl --manage ... --move`; o hash permaneceu igual e não ficou uma segunda cópia do VHDX no D:. `archives/wsl-migration.json` registra `offline-relocation-verified`. O ensaio de boot anterior falhou com `HCS_E_HYPERV_NOT_INSTALLED`; a migração offline não comprova boot, Docker ou Testcontainers funcionando.

Para abrir espaço mantendo reserva no C:, o download público de instalação do PostgreSQL foi preservado por cópia/hash em `.local/vendor-cache/postgresql-windows.zip`; a cópia antiga desse ZIP e o componente opcional pgAdmin extraído no cache foram removidos. São arquivos de distribuição recuperáveis pelo ZIP, não banco/configuração pessoal do pgAdmin. Os executáveis privados em uso e o serviço Windows separado não foram removidos.

## Operação

Na raiz do projeto:

```powershell
./backend-java/scripts/postgres-local.ps1 -Mode status
./backend-java/scripts/protect-private-files-local.ps1
node backend-java/scripts/privacy-runtime-status.mjs
node backend-java/scripts/privacy-security-audit.mjs
```

Para iniciar a demonstração preservada, executar o launcher **instalado** em dois terminais privados, com `-Mode api` e `-Mode worker`. Seu padrão é o hash V26 documentado; uma publicação posterior requer preparar/verificar um novo artefato privado e informar seu hash explicitamente. Não confundir esse comando com publicação do jar recém-compilado.

O modo local de testes lê a conexão e usa somente `unicarona_java_test`. O build deixou de importar todas as chaves financeiras, criptográficas e de e-mail do runtime para os testes. `TEST_PG_TOOLS_PATH` fornece ferramentas reais de dump/restore; o teste falha se elas faltarem. No Testcontainers, usa as ferramentas da própria instância. A variante Docker desse novo suporte ainda precisa ser executada quando a virtualização voltar a funcionar.

Os scripts de staging/cópia/retirada são etapas verificadas da migração, com recusas a sobrescrever caminhos alheios. Não constituem um instalador idempotente para repetir sobre o runtime ativo.

## Retenção e limites ainda abertos

- Manifestações da migração fixam expiração operacional em 35 dias. O catálogo, reserva/finalização de snapshot, expurgo em lotes e automação do worker foram implementados/testados em V33, sem publicação na demonstração. Ver `Retencao_Backups_Diario.md`; a rotina nativa antiga continua V26. Não declarar operação produtiva apenas pelo campo de expiração.
- Restauração física consistente é diferente de supressão após restauração. `PrivacyPhysicalRestoreIT` passou em `logs/privacy-physical-restore-full-archive.log`: três unitários e uma integração, todos aprovados e sem skips. Exercita `pg_dump`/`pg_restore`, bytes antigos, diário independente, perfil/sessão removidos, outra conta e ledger, além de restauração apenas do arquivo e reaplicação repetida. O primeiro ensaio expôs a ausência das definições de extensões em um dump filtrado por schema; o suporte passou a exportar o banco de teste completo, incluindo `unaccent`/índices. A rodada anterior validou 17 autenticação, oito arquivos e 16 direitos, mas terminou com erro no novo ensaio e não representa build integral aprovado.
- O diário independente não deve ser substituído pela cópia antiga do banco/uploads. Expiração coordenada e rotação das assinaturas foram implementadas em V33; AES versionado em V34. Permanecem recuperação externa, procedimento operacional final e publicação. Retenções de casos posteriores a um snapshot precisam ser preservadas na estratégia de restauração; não foram confundidas com o diário de exclusão.
- Ainda são necessários backups de produção fora do mesmo disco/computador, controle de acesso do operador, restauração ensaiada no ambiente final, alertas e rotina de expurgo. Não existe implantação pública configurada nesta etapa.
- Retirar os arquivos remove os caminhos acessíveis no sistema de arquivos; não se afirma limpeza forense de setores antigos do exFAT/SSD. Também não revoga credenciais anteriormente compartilhadas ou potencialmente acessíveis. A gestão/rotação de credenciais permanece parte da revisão operacional final.
- O teste nativo atual não comprova entrega real de novo e-mail, nova cobrança/reembolso, publicação das minutas ou operação das novas telas.
