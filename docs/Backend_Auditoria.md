# Auditoria do objetivo completo

Revisão de 02/10/2026. Fonte de produto: especificação consolidada do UniCarona.
O objetivo de backend amplia infraestrutura e testes, mas não aprova políticas
comerciais ausentes da especificação. O provedor de pagamento continua sem escolha.

Esta matriz distingue implementação local verificada, limite deliberado do MVP e
validação externa pendente. Não declara o lançamento público pronto. Resultados
executados e medições ficam em [Backend_Verificacao.md](Backend_Verificacao.md).

## Matriz dos 50 requisitos

| Item | Implementação e evidência | Situação ou limite |
|---|---|---|
| 1. Análise prévia | Especificação, Login, Dashboard, Backend_Plano e Backend_Decisoes mapeiam atores, estados, dados, conflitos e finanças. | Documentado; a especificação mantém autoridade sobre o simulador histórico. |
| 2. System Design | System_Design descreve cliente → frontend → API → serviços → PostgreSQL → adapters, worker, arquivos, falhas e monitoramento. | Monólito modular implementado. |
| 3. Crescimento | Três estágios e gatilhos medidos para réplicas, cache, armazenamento externo e extração de módulos. | Desenho de evolução; não afirma capacidade nacional já provisionada. |
| 4. Domínios | auth, identity, drivers, rides, reservations, subscriptions, billing, safety, notifications, jobs e admin. | Fronteiras implementadas; avaliações públicas excluídas do MVP aprovado. |
| 5. Banco | Migrations 001–013; PKs, FKs compostas, unicidade, checks, índices e triggers; Modelo_Dados contém ER. | Banco vazio e execução pelo papel sem superusuário verificados. |
| 6. Entidades | Usuário/estudante, academia, motorista/veículo, rota/oferta, pedido/reserva, mensal/agenda, cobrança/repasse, ocorrência, aviso e auditoria. | Rating modelado; nenhum endpoint de estrelas sem política de produto. |
| 7. Regras | Serviços aplicam compatibilidade, vagas, aceite, desconto, disponibilidade, ausência, crédito, carência e segurança. | reservation-finance, transfers-grace, profile-impact e admin-accounts usam banco real. |
| 8. Estados | Guards de pedido, viagem, reserva, parcela, mensal, pagamento e transferência; ledger correlacionado no COMMIT. | Transições regressivas e liquidação inválida rejeitadas por SQL direto. |
| 9. Concorrência | Locks ordenados por participantes/recursos/contas; idempotência na transação e versões otimistas. | Dez recebimentos pagos disputam uma vaga: uma reserva e nove créditos integrais. |
| 10. Autenticação | Argon2id, confirmação, login, logout, refresh rotativo, reset e troca de senha, expiração. | auth-database, auth-concurrency e E2E auth; reset durante login não emite sessão antiga. |
| 11. Autorização | Proprietário, participante, motorista aprovado e administrador conferidos no servidor; conta restrita conserva segurança/carteira. | IDOR, documentos, PIN, contas e apuração exercitados nas suítes reais. |
| 12. Vínculo acadêmico | AcademicVerificationProvider e adapter administrativo; validação por universidade. | Fluxo administrativo funciona; integração institucional oficial não existe nem é alegada. |
| 13. Viagens | Publicar, editar com versão, buscar, solicitar, aceitar, pagar, iniciar, embarcar, concluir e cancelar. | mobility, reservation-finance e jornada reservation até saldo/histórico. |
| 14. Busca | Região, data, sentido, horário, universidade, capacidade e compatibilidade mensal; limite/página explícitos. | performance mede consultas e plano; paginação E2E com 31 ofertas. |
| 15. Geolocalização | Endereços/coordenadas opcionais validados na API e banco; região e encontro usados pelo MVP. | Não inventa distância/GPS; mapa externo não é prova de geocodificação. |
| 16. Mensal | Agenda inteira, aceite específico, desconto congelado, holds de carência, ausência, Sem aula, crédito, renovação e interrupção. | Integração e E2E monthly-usage/monthly-grace; períodos antigos são fixtures declaradas. |
| 17. Pagamento desacoplado | PaymentProvider e TransferProvider, prazo/cancelamento externo e gateway assinado local. | Sem dinheiro real; adapter comercial depende da escolha e homologação. |
| 18. Idempotência | Chaves por ator/escopo/corpo, journals e eventos únicos, rollback/retry conservando identidade. | Replays concorrentes e resposta perdida após COMMIT verificados. |
| 19. Webhooks | HMAC canônico, janela de timestamp, corpo estrito, referência/valor, registro durável e status de processamento. | Repetição, conflito, rollback, retry e resultado desconhecido testados localmente. |
| 20. Repasse | Contas disponíveis, pendentes, taxas e transferências separadas; só resultado autenticado finaliza. | transfers-grace e monthly-usage; saldo futuro não pode ser sacado. |
| 21. Ledger | Partidas equilibradas, histórico imutável, proteção de saldo e guards de correspondência com recursos financeiros. | SQL inválido rejeitado; reconciliação fica zero nas jornadas financeiras. |
| 22. Notificações | Avisos persistentes próprios e e-mail por adapter; aceita/cancela/pagamento/carência/final/ocorrência. | E2E paginação/avisos e auth/worker; push é evolução prevista. |
| 23. Jobs | SKIP LOCKED, lease, backoff, limite/dead, manutenção e retomada administrativa justificada. | failures e admin-jobs verificam fencing, oitava falha e retomada sem aprovar dinheiro. |
| 24. Cache | Sem Redis no MVP; sessões, limits e jobs duráveis no PostgreSQL; pool e paginação. | Decisão documentada; futuras adições dependem de medição. |
| 25. APIs | /api/v1 com rotas por jornada e módulos; mesma origem de frontend/API. | Contratos e OpenAPI exportados; nenhum fallback de domínio no cliente. |
| 26. DTOs | Zod estrito de entrada e saída, serialização explícita, limites, UUIDs e centavos. | Mass assignment, combinações acadêmicas, campos faltantes e limites rejeitados. |
| 27. Erros | code/message/details/requestId, 4xx/409/422/429/503 sanitizados e retry visível. | failures comprova ausência de SQL/stack; E2E carteira/pagamento/rede. |
| 28. Segurança | SQL parametrizado, escape de HTML, CSP, CSRF/origem, ownership, throttle e replay. | Revisão abaixo e security-api; não substitui pentest externo. |
| 29. Privacidade | Documentos privados, fotos só por participação paga, bloqueio bilateral, contato restrito, sem diretório público. | participant-photos verifica URLs antigas revogadas; lists omitem foto, hash e telefone. |
| 30. Observabilidade | Logs JSON, request ID, métricas agregadas administrativas e health/readiness separados. | readiness verifica versões/checksums reais; falhas do pool não derrubam processo. |
| 31. Docker | Dockerfile multi-stage, node sem root, Compose com banco, migrate, seed, API/worker e volumes privados. | Pendente de execução: Docker Engine não está disponível neste computador. |
| 32. Migrations | Versionadas/checksum/lock; não editar versão aplicada; catálogo validado na readiness. | Banco temporário vazio recebeu 13 migrations, repetidas sem alterações. |
| 33. Seeds | Catálogos, quatro contas sintéticas, veículos/documentos e cenários por serviços reais. | Base/cenários repetidos sem duplicar contratos, pagamentos ou ledger. |
| 34. Unitários | Preço/documento, comissão, centavos, alocação mensal, datas/idade e isolamento físico de arquivos. | Cinco testes puros passaram; regras transacionais são verificadas com PostgreSQL. |
| 35. Integração | API + serviços + consultas + migrations/constraints no banco isolado. | Suíte de 79 testes, incluindo agrupadores, passou sem skip. |
| 36. API | Entrada válida/inválida, cookies, CSRF/origem, propriedade, inexistente, conflitos, limite e paginação. | Exercitados pelos testes de integração e browser reais. |
| 37. Concorrência | Última vaga, cota de arquivos, versionamento, saque e apuração disputados simultaneamente. | Uma vitória por operação exclusiva; nenhum overdraft ou overbooking observado. |
| 38. Financeiro | Duplicados, atraso/falha, crédito, estorno lógico, taxa, Sem aula, disponibilidade e transferências. | Ledger conferido por parcela, valor congelado e saldo global. |
| 39. E2E | Cadastro → confirmação → login; motorista/passagem → pagamento → PIN → final/histórico; mensal completo. | Dez jornadas passaram; gateway/caixa de e-mail locais, não respostas falsas do domínio. |
| 40. Visual | Capturas reais, geometria e overflow em seis larguras do início; principais views a 320/1440. | Telas mobile/desktop inspecionadas; sem certificação geral de leitor de tela/diff de pixels. |
| 41. Jornadas | Novo estudante, recorrente, motorista, mensalista e administrador com contextos separados. | auth, reservation, monthly, admin-driver, failures e pagination. |
| 42. Falhas | Banco/pool/timeout, rede após gravação, duplicado, provider, worker antigo e cancelamentos. | Falha controlada com recursos e ledger conferidos após retry. |
| 43. Performance | 5 mil estudantes, 20 motoristas, 3 mil ofertas/avisos, 10 recebimentos e buscas simultâneas. | Relatório local com consultas e EXPLAIN; não teste de capacidade de produção. |
| 44. OpenAPI | Método, URL, cookie, parâmetros/body, DTO, erro, CSRF, idempotência e assinatura descritos. | api:export passou; Swagger disponível em /api/docs. |
| 45. README | Instalação, variáveis, banco, seed, testes, Docker, stack, estrutura e limites operacionais. | Atualizado; credenciais vêm do ambiente privado. |
| 46. Simplicidade | Uma API, worker e PostgreSQL; sem Kafka, Kubernetes, CQRS ou microsserviços. | Dependências servem fluxos concretos; evolução por fronteiras. |
| 47. Ordem de trabalho | Fonte → regra → modelo → serviço → API → frontend → teste → revisão; decisões separadas das pendências. | Evidências organizadas por módulo e jornada, não só compilação. |
| 48. Auditoria | Busca de placeholders, código demonstrativo, queries, DTOs, permissões, erros e testes antigos. | Correções abaixo; histórico movido para tests/legacy e não servido. |
| 49. Done | Build, unitários, integração/API/financeiro/concorrência, E2E e migrations verificados localmente. | Aceite completo permanece aberto enquanto validações pendentes não forem resolvidas. |
| 50. Continuidade | Correções encontradas geram teste e repetição das suítes; performance e docs são revisadas. | Não declarar conclusão por existir arquivo ou por compilar. |

## Revisão de segurança e consistência

SQL de valores do cliente usa parâmetros. Fragmentos dinâmicos de ownership,
ordenação e filtros são escolhidos de enum/controle interno; não recebem SQL
fornecido pelo estudante. A API não aceita assignment de papel, preço, saldo,
motorista ou conta financeira. Serviços conferem proprietário/participação;
triggers não substituem essa autorização. O papel de aplicação não é superusuário.

HTML de conteúdo usa escape; campos do shell usam textContent/value. As jornadas
inserem nomes e opções com aparência de HTML e verificam ausência de execução.
CSP restringe scripts à origem. Mutações verificam Origin e token CSRF; refresh
rotaciona e reutilização revoga a família. Limites são persistidos em buckets
HMAC. Headers de proxy fornecidos pelo cliente não são confiados para obter
outra identidade de rede. Password reset revoga sessões e o login revalida o hash
sob lock antes de emitir uma sessão, evitando a corrida encontrada na revisão.

Fotos são recodificadas sem metadados; documentos têm finalidade/proprietário,
assinatura de tipo, limite e quota concorrente. Arquivos não são servidos pelo
static e o armazenamento verifica caminhos físicos, incluindo junctions/case do
Windows. Participantes pagos podem abrir a foto; bloqueio bilateral revoga esse
acesso e contato. IDs de fotos não aparecem em listas/pesquisa. PIN pertence ao
passageiro e somente aparece enquanto o embarque pode ocorrer; não é incluído
no compartilhamento nem libera saldo.

Logs de request usam caminho sem query e não imprimem bodies/cookies ou links de
autenticação. Erros de banco/worker registram código, não SQL/payload/documento.
Não há diagnóstico bruto em respostas ou na fila administrativa. `.env`, `.local`,
builds e capturas locais não são versionados; o contexto Docker exclui secrets.
Esta revisão é sobre os cenários e código examinados, não uma certificação LGPD
ou uma auditoria independente de infraestrutura.

## Correções encontradas durante o ciclo

- Migração ausente/checksum alterado não pode retornar readiness pronta.
- Resposta tardia de login/cadastro/recuperação não sobrescreve navegação posterior.
- Reset concluído durante login impede sessão com a senha anterior.
- Logout concorrente com refresh revoga também a sessão filha; emissão/revogação seguem a mesma ordem de locks.
- Tag excedente no shell retirava o mapa da grade; corrigida e protegida por geometria/estrutura.
- Modal de compartilhamento restaura foco e omite PIN/contato pessoal.
- Foto confirmada aparece na reserva; URL deixa de abrir após bloqueio bilateral.
- Mensal renovado não mantém ação de renovar o contrato anterior; ausência/Sem aula têm nomes visíveis.
- Trecho encerrado ou Sem aula deixa de apresentar PIN de embarque.
- Tarefa esgotada pode ser retomada com justificativa, identidade e tentativa preservadas.
- Assets demonstrativos e testes antigos foram arquivados; entradas antigas usam os ensaios conectados.

## Limites e validações restantes

Docker/Compose precisam de execução com Engine disponível. Nenhum container foi
executado aqui. O pagamento real depende de escolha/homologação de gateway e
entrega externa de e-mail depende de configuração SMTP. O mapa depende de terceiro.
Medição local não comprova capacidade de produção, rede externa ou milhões de usuários.

O YAML/anchors do Compose, a ordem de dependências, os entrypoints compilados,
a origem/porta e o volume privado compartilhado foram conferidos na continuação
da revisão. Docker/Podman não estão no PATH nem nos caminhos convencionais
examinados, e nenhum processo de engine foi encontrado. A seleção do ambiente
para a verificação de containers foi solicitada ao usuário; segue pendente.

A especificação exclui estrelas do MVP; o modelo Rating não autoriza publicar
esse fluxo. Retenção/anonimização, limpeza de órfãos e política comercial de
cancelamento/falta avulsa não têm regras finais aprovadas. O sistema conserva
evidências e encaminha o financeiro não definido para apuração, sem confiscar
valores ou apagar documentos por uma janela inventada. Esses pontos exigem uma
etapa própria antes do lançamento; não impedem o ensaio completo local.
