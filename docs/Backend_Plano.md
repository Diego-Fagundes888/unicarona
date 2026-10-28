# Backend: escopo, sequência e evidências

Objetivo de 01/10/2026: transformar o frontend existente em um sistema persistente,
com autenticação e autorização reais, PostgreSQL, fluxos completos e integração
sem depender das identidades em memória da apresentação anterior.

Fonte principal: `CaronaUni_Especificacao_MVP.md` e PDF correspondente. O novo
objetivo amplia infraestrutura, autenticação, integração e testes; não transforma
propostas econômicas em políticas comerciais aprovadas. O usuário informou que
ainda não escolheu o gateway. Nenhuma cobrança real é autorizada nesta execução.

## Sequência obrigatória

1. **Análise**: documentação, interface, entidades, estados, atores, concorrência,
   finanças e dados pessoais. Registrar arquitetura antes do código.
2. **Fundação**: aplicação modular, configuração, banco, migrations, seeds,
   observabilidade, contratos, erros, Docker e testes sobre PostgreSQL real.
3. **Identidade**: registro, confirmação, login, sessões/refresh, logout,
   recuperação/troca de senha, permissões, catálogos e perfis.
4. **Mobilidade**: motorista/veículo/documentos, ofertas/rotas, pesquisa, pedidos,
   reservas e concorrência; integrar telas existentes.
5. **Mensal**: elegibilidade, agenda, descontos, capacidade conjunta, parcelas,
   carência, renovação, ausência, calendário e encerramento.
6. **Financeiro**: provider, eventos autenticados, idempotência, ledger,
   liquidação, créditos, transferências, devoluções e conciliação.
7. **Operação**: notificações/outbox/jobs, segurança/bloqueio/ocorrências,
   administração, histórico, armazenamento e integrações acadêmicas.
8. **Auditoria**: suíte completa, E2E, screenshots, autorização, concorrência,
   falhas, consultas/índices/performance, API e documentação.

## Matriz de aceite do objetivo

| Itens do objetivo | Entrega e prova necessária |
|---|---|
| 1–4, 46–47 | análise e desenho; módulos com responsabilidade e fronteiras claras |
| 5–6, 15, 32–33 | ER, constraints/índices, migrations do zero e seed sem dados reais |
| 7–9, 13–14, 16 | serviços e máquinas de estado; invariantes e concorrência real |
| 10–12, 26–29 | autenticação, DTOs, autorização/IDOR, privacidade, adapter acadêmico |
| 17–21 | provider, assinatura/replay, idempotência, ledger, saldo e repasses |
| 22–24 | avisos persistentes, entrega assíncrona, retries; decisão explícita sobre cache |
| 25, 30–31, 44–45 | API v1, OpenAPI, health/ready/métricas, logs, Docker, README |
| 34–38 | unitários, API + PostgreSQL, financeiro e 10 concorrentes/última vaga |
| 39–43 | frontend conectado; jornadas, visual, falhas e performance |
| 48–50 | auditoria por requisito e evidência atual; nenhum principal só demonstrativo |

O status final deve ser baseado em resultados executados, não na existência
destes arquivos. Pendências externas precisam ser descritas, nunca ocultadas.

[Backend_Auditoria.md](Backend_Auditoria.md) registra os 50 requisitos
individualmente, sua evidência e as limitações de aceite.

## Mapeamento da interface

`login.js`: acesso, cadastro em etapas, confirmação e recuperação.
`dashboard.js`: shell responsivo, mapa e busca.
`backend-ui.js`: resultados, detalhes, pedido, checkout, reserva, mensal, motorista,
passageiros, carteira, perfil, avisos, segurança e administração.
`tests/legacy/domain-model.cjs`: referência histórica dos cenários anteriores.
As regras aprovadas vêm da especificação; o backend as aplica no servidor e o
navegador consome DTOs. O modelo antigo não é servido nem usado como fallback.

O modo conectado remove a troca arbitrária de identidade e o relógio fictício.
O gateway local permite somente ensaios autenticados sobre pagamentos próprios;
não existe em produção. A demonstração anterior permanece no arquivo histórico,
sem rota pública.
