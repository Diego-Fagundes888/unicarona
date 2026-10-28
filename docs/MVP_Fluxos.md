> Registro histórico do protótipo em memória de 01/10/2026. O sistema conectado atual usa API e PostgreSQL. Consulte [Backend_Verificacao.md](Backend_Verificacao.md), [System_Design.md](System_Design.md) e o README para implementação, execução e resultados atuais. Os controles e testes demonstrativos descritos abaixo não são fallback da aplicação.

# Plano de implementação e auditoria do MVP

Objetivo: implementar todo o frontend navegável descrito no arquivo de objetivo
do usuário, preservando a referência visual selecionada da Uber e os detalhes
da UNICEPLAC. A conclusão depende de verificar cada fluxo, e não só a abertura.

Fontes: `docs/CaronaUni_Especificacao_MVP.pdf` (igual ao PDF original em D:,
SHA-256 E3E2CC42C7E6050E095C15C8B51E27338BAA4F73C7A60B442CB148157710DF57),
`docs/CaronaUni_Especificacao_MVP.md`, instruções recentes do usuário e
`<LOCAL_USER_HOME>/.codex/attachments/5c918026-f107-4979-869f-a3ed7fd0ec8a/goal-objective.md`.
As alterações anteriores solicitadas pelo usuário prevalecem sobre o texto
antigo: número do período obrigatório quando numérico, turno obrigatório e
turma opcional A/B/C com dica. Compatibilidade mensal exige curso, período
numérico e turma completos e iguais.

## Regras preservadas

1. Cadastro somente 18+, e-mail comum, faculdade fixa e dados autodeclarados.
2. Motorista e veículo precisam de aprovação; mudança relevante exige reanálise.
3. Capacidade por trecho. Pedidos pendentes não equivalem a reservas.
4. Mensal somente na mesma combinação curso/período/turma; todos os trechos
   devem caber, sem vender plano parcial nem retirar reservas já confirmadas.
5. Agenda inclui somente sentidos e dias contratados. Feriados/recessos
   conhecidos são excluídos antes da cobrança. Entrada proporcional é futura.
6. Preço e parcelas congelados na contratação; desconto mensal 10%.
7. PIN individual por trecho registra embarque, sem finalizar/liberar dinheiro.
8. Confirmação final separada; declaração do motorista abre janela 24h.
9. Contestação bloqueia apenas a parcela envolvida. A ausência mensal com
   disponibilidade cumprida é separada do embarque e não gera desconto.
10. Cancelamento do motorista credita parcela paga integral, sem comissão;
    no mensal, usa parcela com desconto. Não cancela o sentido oposto.
11. Cancelamento mensal comum impede renovação e mantém o período pago.
    Mudança acadêmica/remoção do motorista encerra futuro e credita parcelas.
12. Segurança interrompe vínculo imediatamente; análise financeira é separada.
    Bloqueio impede novas combinações, inclusive aceitação automática.
13. Sem aula posterior: nas novas confirmações/liquidações Java, crédito integral ao passageiro, elegível ao fluxo de devolução, sem receita da plataforma. Contestação continua em análise. Ver [regra revisada](privacy/Sem_Aula_Regra_Revisada.md).
14. Três faltas geram alerta/análise, sem punição automática não documentada.
15. Pagamento recebido é separado de futuro, bloqueado, disponível, crédito,
    comissão, retenção e saída. Eventos repetidos não duplicam valores.
16. Dados/documentos/pagamentos são fictícios; sem chamadas, documentos,
    autenticação ou operações financeiras reais.

## Decisões operacionais explícitas da demonstração

Pendências do documento permanecem identificadas como propostas. Para tornar
o protótipo percorrível, a implementação usa:

- Solicitações pendentes ou aceitas ainda sem pagamento não ocupam vaga;
  aprovação do pagamento revalida a capacidade de forma indivisível.
- Mensal sempre passa por aceite específico do motorista antes do pagamento.
- Janela de 24h começa na notificação da declaração de conclusão/disponibilidade.
- Carência demonstrativa de 72h após vencimento, com tentativa de retenção
  conjunta das vagas, sem embarque de período novo antes do pagamento.
  Conflito pré-existente impede renovação parcial; não desloca outros usuários.
- Cancelamento/falta avulsa pelo passageiro vai à análise financeira, pois sua
  política não está definida; não se inventa multa ou devolução automática.
- Registro de Sem aula tem notificação, contestação e apuração; calendário
  administrativo demonstrativo. Ordenação prioritária é proposta, sem garantia.
- Distância é informada manualmente em km ilustrativos; o mapa não mede rota.
- O número de passageiros de referência na cotação é limitado à capacidade
  informada. Não depende de ocupação real nem altera preços já contratados.
- Valores são centavos inteiros. O desconto é calculado no total e os centavos
  restantes distribuídos na ordem da agenda; a comissão é arredondada por
  parcela devida. O nível de arredondamento comercial permanece uma proposta.

## Matriz de entrega

| Área | Fluxo/artefatos a verificar | Situação inicial |
|---|---|---|
| Acesso | Cadastro, maioridade, e-mail, login, recuperação, configuração inicial | Acesso existe; configurar perfil ainda incompleto |
| Design system | Tokens, botões, campos, badges, listas, modais, loaders, toast, navegação | Parcial |
| Passageiro avulso | Busca/filtros → resultados → detalhes → pedido → aceite → pagamento → reserva → PIN → conclusão/contestação → histórico | Só busca vazia |
| Mensal | Compatibilidade → agenda/dias/sentidos → cálculo/proporcional → aceite → pagamento → reservas → renovação/carência/encerramento | Informativo |
| Motorista | Documentos fictícios → veículo → aprovação/rejeição → criar/editar oferta → pedidos → passageiros/PIN → execução/disponibilidade → histórico | Informativo |
| Financeiro | Pagamentos pendente/aprovado/erro/vencido, crédito, contestação, comissão/retido, saque/devolução e conciliação | Ausente |
| Segurança | Compartilhamento iniciado pelo usuário, recusa de embarque, denúncia antes/durante/depois, bloqueio, ajuda sem chamada real | Informativo |
| Notificações | Aceites, mudanças, pagamento, prazo, falta, contestação e leitura | Vazio |
| Perfil/configurações | Editar dados/foto, turma com impacto no mensal, preferências, bloqueados, saída | Somente leitura |
| Administração | Papéis de demonstração, aprovações, calendário, incidentes/disputas, parâmetros e trilha de decisão | Ausente |
| Estados | Loading, vazio, sucesso, erro, offline, pendente/recusado/cancelado/bloqueado, estados financeiros | Parcial |
| Responsividade | Mobile prioritário, tablet e desktop, teclado, foco, texto longo, ausência de overflow | Painel básico verificado |

## Organização prevista

- `dist/mvp-domain.js`: estado e transições em memória, regras e cálculos puros;
  exportação para testes Node, sem credenciais ou efeitos externos.
- `dist/mvp-ui.js`: páginas/ações conectadas ao shell existente do dashboard,
  componentes reutilizados e histórico de navegação.
- `dist/design-system.css`: tokens e superfícies consistentes.
- `tests/mvp-domain.cjs`: invariantes de vagas, elegibilidade, PIN, política
  de mensalidade e conservação/idempotência do dinheiro fictício.
- `tests/mvp-flow.cjs`: fluxos completos e estados pelo navegador.
- `docs/MVP_Verificacao.md`: auditoria final com evidência por requisito.

## Ordem de trabalho

1. Mapear e testar o domínio de reservas/parcelas e dados fictícios.
2. Conectar busca, resultados, detalhes, solicitação e pagamento.
3. Concluir motorista, execução e mensal.
4. Concluir carteira, segurança, perfil, configuração e administração.
5. Percorrer todos os cenários, capturar telas e corrigir diferenças visuais.
6. Auditar a matriz completa antes de declarar o objetivo concluído.
