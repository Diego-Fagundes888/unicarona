> Registro histórico do protótipo em memória de 01/10/2026. O sistema conectado atual usa API e PostgreSQL. Consulte [Backend_Verificacao.md](Backend_Verificacao.md), [System_Design.md](System_Design.md) e o README para implementação, execução e resultados atuais. Os controles e testes demonstrativos descritos abaixo não são fallback da aplicação.

# MVP navegável: implementação e verificação

Entrega em 01/10/2026. Frontend estático responsivo, com fluxos em memória,
orientado pela especificação de 19 páginas e pelas correções do usuário.
`dist` é a fonte. Nada foi publicado ou integrado a serviços de pagamento,
documentos, autenticação, WhatsApp ou localização em tempo real.

## Como percorrer

1. Abra a página inicial e entre, ou complete o cadastro demonstrativo.
2. No painel, abra **Apresentação → Usar perfil de exemplo** para carregar Ana,
   ADS, 3º período, turma A, Lago Azul. Essa escolha é explícita; um perfil
   genérico não recebe dados acadêmicos inventados silenciosamente.
3. Busque Lago Azul, ida, na data do exemplo. A carona das 18:30 é automática;
   a das 19:00 depende de aceite manual de Lucas.
4. Solicite, simule pagamento, abra a reserva e confira veículo e PIN.
5. Em Apresentação, troque para Carolina. Abra Oferecer, gerencie a oferta,
   valide o PIN informado pelo passageiro, inicie e declare a conclusão.
6. Retorne a Ana para confirmar o registro final ou contestar a parcela.
7. Para mensal, escolha o plano de Carolina, confira a agenda inteira,
   solicite, aceite como Carolina e pague como Ana. Gerencie o plano e os
   trechos em Minhas caronas.
8. Para apresentar aprovação, envie o veículo fictício de Ana, troque para
   Administração e analise o cadastro. Publique uma oferta após a aprovação.
9. Administração também analisa ocorrências, suspensões, calendário,
   transferências, parâmetros e a conciliação. O relógio do exemplo permite
   percorrer prazo de confirmação, vencimento e carência.

**Apresentação** permite escolher busca normal, erro ou sem conexão e pagamento
aprovado, pendente ou recusado. Recarregar, sair ou reiniciar apaga a sessão.
A alternância de papéis é uma ferramenta de apresentação, sem segurança de
autenticação real. As transições ainda verificam o proprietário e o papel
necessário para impedir decisões incoerentes durante o teste.

## Matriz concluída

| Requisito | Tela/ação conectada | Evidência |
|---|---|---|
| Acesso e maioridade | Cadastro, perfil, confirmação fictícia, login, recuperação e painel | login, academic-profile, dashboard |
| Busca | Origem/destino, ida/volta, data, hora mínima, modalidade; preço/aceite/vaga no avulso e compatibilidade acadêmica no mensal | dashboard, mvp-flow, mvp-states, mvp-domain |
| Avulsa | Resultados → veículo/encontro → pedido → aceite → pagamento → reserva → embarque → confirmação/contestação → histórico | mvp-flow, mvp-domain |
| Aceite manual | Lista do motorista, aceitar, rejeitar com motivo, encerrar pedido não pago, aviso ao passageiro | mvp-states, mvp-domain |
| Mensal | Mesma combinação acadêmica, dias/sentidos/início, agenda completa, exclusões, desconto, aceite específico, pagamento e parcelas | mvp-flow, mvp-domain |
| Continuidade mensal | Renovação, carência, conflito de agenda, vencimento, desativação da renovação, remoção e mudança acadêmica | mvp-domain; controles no plano e relógio |
| Ausências | Avisar ausência sem liberar vaga, disponibilidade separada do PIN, contagem de faltas e análise sem punição automática | mvp-domain; reserva e gerenciamento |
| Motorista/veículo | Pacote fictício, envio, pendência/rejeição/aprovação, reanálise e capacidade | mvp-flow; domínio reviewDriver |
| Oferta | Publicar avulsa/rotina, preço ilustrativo, editar mantendo parcelas, pausar novos planos, passageiros, iniciar/finalizar, cancelamento | mvp-flow; domain create/edit/routineAvailability |
| Mudança de horário | Proposta coletiva, aceite/recusa, crédito do avulso recusante, preservar horário original | mvp-domain; gerenciamento e reserva |
| Sem aula | Registro por trecho após contratação, aviso, janela proposta, contestação e retenção específica | mvp-domain; gerenciamento e administração |
| Pagamento/carteira | Pix/cartão fictícios, pendente/falhou/aprovado/vencido, crédito reutilizado, saldo futuro/bloqueado/disponível, saque/devolução | mvp-flow, mvp-states, mvp-domain |
| Segurança | Relato sem reserva ou antes/durante/depois, recusa por veículo divergente, bloqueio bilateral, grave imediato, acesso ao relato durante restrição | mvp-states, mvp-domain |
| Compartilhamento/contato | Resumo copiável iniciado pelo usuário, sem PIN/GPS, contato de participante após confirmação, 190/180 informativos | mvp-states; reserva e segurança |
| Notificações | Pedido, decisão, pagamento, mudança, conclusão, ausências e análise; abrir/leitura | mvp-states; domínio notify/readNotice |
| Perfil/configuração | Editar dados/foto, turma A/B/C e dica recolhida, impacto acadêmico, avisos opcionais de interesse, preferência registrada no saque, bloqueados e saída | dashboard, mvp-states, login, mvp-domain |
| Interesse/substituição | Entrar/retirar interesse, lista do motorista, prioridade proposta após remoção sem garantia ou retirada de outra reserva | domínio waitlist/removeMonthly; telas mensal/minhas/motorista |
| Administração | Aprovações, ocorrências e decisões financeiras separadas, contas, calendário, transferências, parâmetros, diário e conciliação | mvp-flow, mvp-states, mvp-domain |
| Estados e formulários | Carregando, vazio, erro, offline, validações, foco, avisos, sucesso e todos os estados financeiros | mvp-states, dashboard, login |
| Responsividade | Navegação superior/inferior, mapa e formulário, nove telas em seis larguras, sem overflow | mvp-flow, dashboard, check-copy |

Os nomes das evidências referem-se aos arquivos em `tests/*.cjs`. Os fluxos
automatizados usam identidades, documentos e dados fictícios, sem POST nem
armazenamento persistente. Os testes completos da interface fixam a data em
01/10/2026 para que a agenda de 40 trechos seja reproduzível.

Verificação final: `mvp-domain`, `mvp-flow`, `mvp-states`, `dashboard`, `login`
(incluindo `academic-profile`) e `check-copy` passaram após as alterações.
Os testes de navegador foram executados em sequência. Nove rotas do MVP foram
conferidas em 320, 390, 768, 900, 1024 e 1440 px; o perfil também foi verificado
com nome longo no celular. Nenhum erro JavaScript nem envio dos formulários
foi detectado nos cenários automatizados.

A preferência de avisos controla somente a lista de interesse; decisões,
pagamentos e alertas de segurança continuam chegando. A frequência de saque
fica registrada na solicitação para a administração demonstrativa: não agenda
transferências reais.

## Regras financeiras verificadas

- Preço ilustrativo de 20 km: R$ 5,93, com os parâmetros iniciais de teste.
- PIN registra embarque e deixa o valor futuro; confirmação final é separada.
- Confirmação repetida, cancelamento repetido e decisão repetida não duplicam
  dinheiro. Vaga concorrida é revalidada antes de receber qualquer valor.
- Plano mensal exige vaga em todos os trechos, sem vender parte da agenda.
- 40 trechos de R$ 6,00, desconto 10%: R$ 216,00.
- Exemplo integral da documentação: 36 parcelas liberadas = R$ 174,96 ao
  motorista + R$ 19,44 de comissão; 2 cancelamentos = R$ 10,80 de crédito;
  2 Sem aula = R$ 10,80 retidos. Total R$ 216,00, diferença zero.
- 40 trechos de R$ 5,93: total R$ 213,48; 28 parcelas de R$ 5,34 e 12 de
  R$ 5,33. Distribuição determinística, sem perder centavos.
- Crédito de R$ 5,93 pagando outro trecho de R$ 6,00 exige só R$ 0,07 de
  nova entrada fictícia. O novo trecho continua com base R$ 6,00.
- Cancelamento do motorista credita a parcela efetivamente paga, sem comissão
  e sem cancelar o sentido oposto. Desativar renovação preserva o período pago.
- Contestação bloqueia a parcela específica. Segurança bloqueia o futuro
  afetado, sem liquidar contestações alheias. Decisão de acesso pode deixar
  o dinheiro em análise; essa análise continua disponível para conclusão.
- Ausência mensal avisada mantém vaga e preço. Disponibilidade cumprida sem
  PIN registra ausência, não inventa embarque. Relato grave não espera três faltas.

## Propostas e limites preservados

As escolhas para percorrer pendências da especificação estão detalhadas em
[MVP_Fluxos.md](MVP_Fluxos.md): pagamento após aceite; pedido não pago sem vaga
retida; janela demonstrativa de 24h; carência de 72h sem embarque novo não pago;
arredondamento por parcela; capacidade mínima sobre referência de cotação;
cancelamento ou falta avulsa do passageiro sob análise; prioridade de interesse
sem promessa de substituição. Não são políticas comerciais consolidadas.

O mapa real usa OpenStreetMap, com atribuição e região pública fixa. Não mede
rotas, recebe endereços digitados nem oferece GPS. As fotos opcionais ficam em
memória, até 2 MB. Não são solicitados documentos reais. Não há chat próprio,
avaliações por estrelas, assinatura, preferência por mulheres, backend ou
operações reais, conforme o escopo da especificação.

A revisão visual está em `design-qa.md`. Capturas de fluxo ficam em
`docs/previews` e as comparações com a referência selecionada ficam na pasta
de visualizações do chat. Não foi feita certificação completa com leitor de
tela nem teste de integrações externas, que não existem nesta entrega.
