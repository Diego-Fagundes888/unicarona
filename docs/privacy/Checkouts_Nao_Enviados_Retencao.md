# Cotações abandonadas e checkouts nunca enviados — V44

26/10/2026. Fonte de desenvolvimento, sem publicação. Demonstração protegida V26
e frontend congelado preservados. Esta etapa não encerra a retenção do ledger,
recebimentos, contratos cumpridos ou do objetivo integral de privacidade.

## Diagnóstico

Aceitar um pedido cria um pagamento local antes da chamada ao provedor.
`ride_requests` pode conservar uma cotação JSON com referências acadêmicas,
negociação e participantes; `request_lines` conserva agenda/preços cotados.
Essas linhas não tinham expurgo mesmo sem reserva, envio ou recebimento. O
trigger financeiro proibia excluir qualquer pagamento. Eventos, cache e avisos
podiam continuar apontando para uma negociação encerrada.

Pedido remoto recusado ou resposta perdida são casos distintos de checkout
nunca enviado. A regra implementada não confunde essas situações.

## Prazo e dados retirados

A janela operacional proposta é de **90 dias de 24 horas** para suporte e
diagnóstico da negociação. O marco considera criação, prazo original ou
encerramento anterior pertinente, criação das tentativas locais e atos reais
da negociação. A expiração automática tardia não cria nova janela: o evento
`request_expired`, polling, retentativa técnica e restauração não são novo aceite.

| Registro | Destino quando o conjunto está vencido e comprovadamente sem envio/recebimento |
| --- | --- |
| Pedido, cotação JSON, motivo e IDs acadêmicos nela repetidos | Excluir `ride_requests` |
| Agenda/preços somente cotados | Excluir `request_lines`; a oferta, rota e carona permanecem |
| Pagamento local nunca recebido | Excluir `payments`, preservando a proibição de excluir recebidos/submetidos/incertos |
| Comando falho nunca iniciado, sem tentativas e sem entrada operacional necessária | Excluir com o pagamento; conteúdo transitório segue primeiro o ciclo V41 |
| Eventos classificados do pedido | Excluir apenas `request_created`, `request_accepted`, `request_rejected`, `request_cancelled` dos participantes e `request_expired` do sistema |
| Resposta idempotente vencida que aponta diretamente ao pedido/pagamento | Excluir; resposta ainda válida impede o expurgo do conjunto |
| Aviso dispensável vinculado ao pedido | Excluir para os participantes, salvo preservação específica da notificação |
| Entrega local de cartão/Pix com identidade conferida, ainda não enviada | Excluir com o pagamento quando sem processamento ativo; lease vigente ou entrega desconhecida impedem expurgo |

Os formatos efetivamente produzidos pelo backend são conferidos: resposta com
`id`, com `paymentId` e UUID JSON escalar do comando de cartão. Comparação aceita
as letras de UUID em maiúsculas/minúsculas, inclusive em notificações financeiras;
isso não cria uma segunda identidade nem permite descartar prova por formatação.

Valores separados entre crédito e cobrança externa podem ser apenas uma intenção
de checkout. A decisão confere recebimento, journal, reserva, envio, referência
remota e tentativas. Retirar uma preparação nunca enviada não gasta nem devolve
crédito disponível. Adaptadores desconhecidos não têm seu protocolo presumido;
preparações de desenvolvimento com distribuição financeira ficam fora do expurgo.

## Barreiras e atomicidade

Impedem a retirada:

- Recebimento, reserva, parcela, lançamento ligado ao pagamento, pedido remoto,
  reembolso, apuração/revisão ou evento financeiro pertinente. Pedido confirmado
  também não é uma cotação abandonada.
- Notificação financeira durável já registrada, inclusive ainda não conciliada.
  Ausência de pedido remoto anexado não apaga a evidência do recebimento do evento.
- Pedido de acesso, portabilidade ou restrição ainda pendente de um participante,
  para atender o direito sobre dados disponíveis. Concluir esse atendimento não
  cria novos 90 dias; solicitar eliminação, por si só, não congela o expurgo.
- Envio iniciado, contagem de tentativas positiva ou comando ainda pendente/com
  entrada necessária. Falha e tempo decorrido não comprovam ausência de envio.
- Entrega em processamento com lease vigente/indeterminado ou tipo/identidade
  não classificados. O expurgo não deixa um job conhecido apontando para o
  pagamento retirado nem apaga uma operação futura desconhecida por conveniência.
- Preservação financeira vigente de qualquer participante. Caso de outra pessoa
  não conserva todas as negociações; avisos têm sua preservação própria.
- Comando ainda referenciado pelo controle independente da identificação
  contratual. Seu testemunho não pode desaparecer para simular quitação.
- Evento de pedido desconhecido ou atribuído a pessoa incompatível. Novas ações
  precisam de classificação antes do expurgo.
- Dependência relacional adicional: uma FK restritiva pode impedir a retirada;
  nesse caso a transação reverte o conjunto, em vez de confirmar sucesso parcial.

O coordenador autentica o controle independente, tranca os participantes em
ordem, pedido, pagamento e comandos, reconfere elegibilidade e retira o conjunto
na mesma transação. Confirma o snapshot antes do commit. Esconder fatos conhecidos
na finalidade registrada não permite substituir um controle válido. Uma falha
reverte eventos, cache, avisos, comandos e pagamento já retirados na transação.

A migração V44 contém funções, guardas e índices, sem exclusão em massa. Audit
continua sem UPDATE; journal, parcelas e distribuição de recebimento conservam
suas guardas. A exceção ao `payment_no_delete` vale só para a família comprovada.

## Prova do envio e recuperação

O primeiro marcador de envio não pode ser apagado ou trocado; contadores de
cartão/Pix não podem regredir. Legado com tentativas positivas e início ausente
não é nunca enviado: o input pendente não é retirado apenas pelo vencimento,
e nova submissão automática é recusada para conferir o envio anterior. Não se
inventa data perdida, contato ou dinheiro. Retomadas normais conservam o marcador;
retorno verificado do provedor continua com seu fluxo de conciliação.

Manutenção pula titular ocupado e tenta novamente sem monopolizar a fila.
Recuperação usa travas bloqueantes e reaplica a regra antes da prontidão, após
replay de casos e identidades. Preservação financeira posterior ao backup pode
impedir a retirada quando seu controle for recuperado. Não foi criado formato
novo de snapshot, tabela de identidade ou coleta de documento.

As datas de origem tornam a retirada repetível no backup restaurado. Isso depende
da integridade do backup e dos controles operacionais; não resolve recuperação
financeira integral de fontes perdidas ou restaurações parciais inconsistentes.
Dados sob outra finalidade permanecem pessoais/pseudonimizados; esta etapa não
os declara anônimos.

## Fundamento e minutas

A negociação solicitada pelo titular tem finalidade pré-contratual. Apoio e
defesa exigem necessidade e escopo próprios. A
[LGPD, arts. 6º, 7º e 15–16](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm)
orienta a separação de cotação sem envio e prova financeira necessária. Noventa
dias é política operacional proposta, não prazo universal legal ou prescrição
de toda reclamação.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Validar janela de suporte,
preservações e enquadramento das obrigações antes da operação real.

Privacidade r8 permanece em `draft`; r7 preservada exatamente em
`history/privacy-r7.md`, conferida contra a evidência V43. As outras cinco minutas
não foram reescritas. Não houve aceite retroativo, publicação, cobrança real,
envio de e-mail ou alteração de CSS/componentes/assets.

## Verificação e continuidade

[Evidência e hashes](evidence/unsubmitted-checkout-retention-26oct.json) registram
as rodadas e falhas de preparação dos testes: campo `status_detail` obrigatório,
ativação do provedor simulado e acesso ao construtor local do cliente final.
O teste foi colocado no pacote de testes do provedor, conforme a API de simulação
já existente; não se abriu o construtor de produção nem se alterou a biblioteca
de mocks. PostgreSQL de teste em loopback, porta 55432, com pessoas e valores
sintéticos. Cliente desse teste aponta somente para loopback, sem cobranças reais.
Não somar rodadas sobrepostas.

| Rodada | Unitários | Integrações | Resultado e escopo |
| --- | ---: | ---: | --- |
| Primeira implementação | 5 | 14 | Aprovada: migrações e expurgo do conjunto básico |
| Casos iniciais | 10 | 61 | Um erro na preparação do pedido remoto rejeitado, sem `status_detail` |
| Regressão ampla | 50 | 272 | Aprovada; anterior aos reforços de intenção preparada, legado e fila |
| Preparação e legado | 50 | 143 | Um erro de configuração do teste: provedor padrão sem bean Mercado Pago |
| Cliente local, primeira tentativa | 0 | 0 | Compilação recusou acesso ao construtor de teste por pacote; não houve testes executados |
| Cliente local, segunda tentativa | 50 | 128 | 21 erros de inicialização da classe: mock de cliente final indisponível nessa configuração |
| Fixture no pacote do provedor | 10 | 19 | Aprovada: cinco cenários direcionados e 14 de migração |
| Prova completa do escopo e mensal | 50 | 149 | Aprovada: inclui 24 cenários próprios, 65 Mercado Pago e 13 de dados transitórios |
| Formatos de cache e UUID — fonte final | 50 | 71 | Aprovada: 24 próprios, 14 migração, 18 direitos e 15 jurídicos |

A última rodada exercita a fonte final. A rodada de 149 integrações precede
somente a ampliação dos formatos de referência do cache/observação; a seguinte
reconfere o escopo afetado. Não são contagens de cenários distintos acumuláveis.
Esta é revisão técnica pelo implementador, não red team independente ou parecer
jurídico global. Erros de preparação não foram convertidos em aprovações.

Continuam pendentes o ciclo finito do ledger, recebimentos e contratos cumpridos,
demais auditorias e tentativas de regularização de dívida, prova de elegibilidade,
estatística realmente anônima, minimização restante, recuperação externa/financeira
integral e capacidade, red teams e fechamento dos 44 requisitos e dezesseis
entregáveis. Inspeção visual adiada por instrução do usuário; sem tentativa de
contornar o bloqueio do navegador.
