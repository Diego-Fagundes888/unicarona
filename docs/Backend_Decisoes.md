# Regras operacionais e pendências externas

Preservar pagamento após aceite e antes de confirmar reserva, conforme a
documentação consolidada e o frontend. O exemplo final do novo objetivo cita
pagamento depois da viagem, mas também pede fidelidade à fonte principal:
essa sequência ilustrativa não substitui pré-pagamento e parcelas futuras.

- Mensal exige aceite específico; `Subscription` representa plano de caronas,
  não assinatura cobrada do motorista para acessar o aplicativo.
- 72h de carência, retenção conjunta somente se todas as vagas estiverem livres,
  sem embarque no período novo não pago. Nenhuma reserva alheia é removida.
- Prazo de resposta/checkout será configurável e comunicado; valores iniciais
  são política operacional versionada, identificada como proposta.
- Três ausências no mês geram revisão; não há punição automática. Ausência
  avisada não revende vaga. Declaração de disponibilidade é separada do PIN.
- Janela de 24h usa aviso persistido; falha de e-mail não apaga o aviso no app.
  Uma ocorrência tardia permanece acessível e não debita dinheiro já sacado
  automaticamente. Casos financeiros definitivos exigem apuração separada.
- Avulso cancelado pelo passageiro gera análise, sem inventar multa.
- Contrato mensal com conflito parcial falha integralmente. Preços e parcelas
  já contratados não são recalculados por mudanças posteriores.
- Feriados conhecidos antes ficam fora; Sem aula posterior mantém aviso e
  contestação e, nas novas liquidações Java, gera crédito integral ao passageiro.
  A origem financeira permite solicitar devolução. Ver [regra revisada](privacy/Sem_Aula_Regra_Revisada.md).
- Contribuição calculada por distância informada e parâmetros versionados;
  valores iniciais são exemplos, não tarifas comerciais pesquisadas.
- Universidade inicial UNICEPLAC; curso/período/turma autodeclarados, status
  separado de validação institucional. Adapters permitem expansão futura.
- Mercado Pago via Orders foi escolhido para a integração em implementação.
  O ambiente padrão de desenvolvimento não cobra nem transfere recursos.
  Orders de teste exigem credencial de usuário de teste e webhook oficial.
  A integração ainda não foi homologada de ponta a ponta no sandbox externo.
  Repasse automático não é simulado: a obrigação fica no ledger, e a operação
  real depende de habilitação comercial. Produção exige configuração explícita
  e aprovação da operação financeira, sem fallback silencioso para simulação.
- E-mail usa SMTP configurável; caixa local de desenvolvimento permite
  verificar tokens reais sem enviar mensagens a pessoas.

## Administração e apuração financeira

- Parâmetros de custo e comissão têm versões imutáveis e atualização otimista.
  Duas alterações baseadas na mesma versão não se sobrescrevem. O desconto
  mensal permanece 10%, conforme a regra consolidada; o formulário não altera essa regra.
- Calendário administrativo mantém registro desativado e alcance por faculdade
  ou curso/período/turma. Criação de cotação e confirmação de agenda usam lock
  compartilhado da faculdade; alteração de calendário usa lock exclusivo.
  Alteração entre checkout e recebimento invalida a agenda congelada se acrescentar
  ou retirar trechos: nenhum contrato incompleto é criado; o recebimento externo
  vira crédito integral. Crédito apenas previsto no checkout não é consumido.
- Datas inseridas após contratação não alteram reservas ou parcelas pagas nem
  registram Sem aula automaticamente. A declaração posterior é feita por trecho.
- Suspensão administrativa preventiva exige justificativa, interrompe trechos
  ainda não iniciados e vincula o dinheiro afetado a uma análise separada. Não
  confisca saldos já liberados. Viagens em andamento preservam conclusão e
  confirmação final; restaurar acesso não reativa ofertas ou contratos interrompidos.
- Relatos relacionados mantêm a parcela bloqueada enquanto houver caso pendente.
  Uma decisão divergente exige reconhecimento explícito dos IDs de decisões
  anteriores, conferidos novamente dentro da transação. A justificativa e os IDs
  substituídos ficam na auditoria; relatórios antigos e ledger não são reescritos.
  Parcelas definitivamente liquidadas não são modificadas por esse mecanismo.
- A administração consulta os valores congelados, situação do trecho,
  disponibilidade e outros relatos de cada parcela antes de decidir. Esses dados
  não são expostos nas telas de relatos pessoais de estudantes.
- Atualizações de contato/região preservam validação acadêmica. Alterar faculdade,
  curso, período, turma ou turno limpa a validação, sua origem e a data anterior.
- Timeout ou falha de conexão com o gateway não significam recusa definitiva.
  Checkout e transferências continuam pendentes; a nova tentativa usa o mesmo ID.
  No navegador, a cobrança sem link pode ser retomada mesmo depois de recarregar.
  Crédito previsto só é apresentado como consumido após confirmação da reserva.
- A migration 013 congela contrato e recebimento do pagamento e contrato da
  transferência. Triggers diferidos exigem journals correspondentes para
  recebimento, separação de saldo e resultado da transferência. Confirmar dinheiro
  recebido atrasado continua permitido, com crédito integral quando não há vaga.
- A busca pública leva o trajeto ao painel e preserva esse destino pelo login.
  O retorno aceita somente `/dashboard.html` da mesma origem. Região e filtros
  ficam no endereço da busca; senhas e tokens não entram nesse endereço.

Cadastro/documentos, operação de suporte, retenção de dados e políticas finais
exigem decisões antes do lançamento público. Implementar controles e registrar
limites não equivale a afirmar aprovação da faculdade ou conformidade legal.

A especificação exclui avaliações por estrelas do MVP aprovado. A entidade
Rating foi modelada por exigência do objetivo de arquitetura, mas não autoriza
um endpoint público nem ranking sem decisão de produto. Não existe um botão
de avaliação prometendo esse fluxo na interface conectada.

Fotos só são apresentadas aos participantes de uma reserva paga; bloqueio
bilateral retira esse acesso e o contato, mesmo quando o trecho já terminou.
Dados de lista não incluem a URL da foto. O PIN só aparece ao passageiro
enquanto o trecho permite embarque e nunca é incluído no compartilhamento.

A emissão da sessão revalida a senha sob lock do usuário depois da verificação
Argon2id. Um reset concluído entre a leitura inicial e essa emissão invalida
o login antigo, em vez de permitir uma sessão nova após a revogação.

Login, refresh e logout coordenam emissão/revogação sob lock do usuário antes
do lock da sessão. Um logout concorrente com refresh revoga também a sessão
filha recém-criada; a consulta de revogação não perde essa linha por snapshot
anterior. O ensaio pausa após o INSERT real e aguarda o lock real do PostgreSQL.
