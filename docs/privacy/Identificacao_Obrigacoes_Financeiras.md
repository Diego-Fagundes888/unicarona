# Identificação mínima e obrigações financeiras — V43

26/10/2026. Implementação de desenvolvimento, ainda sem publicação. O banco da
demonstração protegida permanece na V26. Esta etapa não encerra a camada de
privacidade nem a retenção do histórico financeiro e contratual completo.

## Falha reproduzida

O expurgo de `privacy_retained_records` usava o vencimento fixado no encerramento
da conta. Uma identidade contratual podia desaparecer mesmo com crédito
restituível ou dívida ainda em aberto. O teste com crédito histórico de sete anos
reproduziu a perda da única cópia mínima do reclamante. O saldo permanecia no
ledger: apagar o perfil não deveria tornar impossível identificar a obrigação.

O fluxo normal já adia o processamento enquanto há cobranças, comandos,
devoluções, repasses ou regularização em curso. Isso não equivale a exigir que
todo saldo e toda disputa sejam zerados antes de remover o perfil: valores são
apresentados separadamente, e a disputa preservada não obriga a exposição pública
da pessoa. A guarda mínima também atende fatos supervenientes, conciliação,
registros legados e recuperação, sem enfraquecer o resumo de obrigações existente.

Outra situação ocorria na execução tardia de uma exclusão já preparada. A data
original de defesa podia ter passado antes da criação efetiva da cópia. A solução
conserva a data real de criação e o limiar original, sem inventar uma nova janela
de cinco anos ou retroagir a data da cópia.

## Regra implementada

| Situação | Tratamento da evidência mínima |
| --- | --- |
| Crédito, recebível, repasse ou devolução pendente; pagamento/operação em processamento; análise pertinente ainda aberta | Preservar enquanto houver finalidade concreta, independentemente do limiar de defesa original |
| Liquidação ou outro fato de negócio pertinente posterior | Considerar esse fato no limiar operacional de defesa, sem alterar valores, titular ou condições financeiras |
| Somente total histórico `paid_out` ou `refunded`, sem outra finalidade | Não manter identidade indefinidamente pelo valor acumulado; expirar após a janela e sem preservação específica |
| Cópia de veículo antigo e crédito do mesmo usuário como passageiro sem vínculo com esse veículo | O crédito não conserva a placa/modelo do veículo antigo |
| Consulta do provedor ou manutenção repetida | Não usar `checked_at`, `next_check_at` ou relógio de recuperação como novo fato; observações que só mudam versão do provedor não renovam prazo/revisão |
| Restauração com fatos anteriormente observados ausentes | Preservar a evidência mínima e exigir recuperação/conciliação; não interpretar carteira antiga vazia como quitação |
| Exclusão preparada antiga, já sem finalidade e com limiar vencido | Não criar novamente uma cópia vencida durante o scrub |

A identificação contratual conserva somente a cópia já definida pelo fluxo de
encerramento, cifrada e privada. A etapa não coleta CPF, CNH ou dados novos e não
abre identidade ou veículo para outros participantes. Holds específicos seguem
fundamento, revisão, vencimento e controle próprios.

## Implementação e recuperação

- V43 adiciona `financial_purpose` e funções de finalidade/vínculo, sem expurgo em
  massa na migração. Um trigger impede excluir evidência ainda necessária. Guardas
  de imutabilidade financeira e reconciliação permanecem preservadas.
- Metadados tipados registram revisão, pendência, último fato relevante e limites
  de fatos conhecidos. As oito famílias são entradas do ledger, ocorrências,
  observações verificadas do provedor, transferências, comandos de cartão/Pix,
  pagamentos de dívida e protocolos de direitos. IDs e hashes são dados
  pseudonimizados, não estatística anônima.
- O snapshot independente passa ao formato 3. Formatos genuínos 1 e 2 são
  convertidos sem atribuir atividade recente; esquemas mistos, tipos coercíveis,
  campos desconhecidos, perda de fatos ou regressão de revisão são recusados.
  Há limites de 10 mil fatos por cópia e 50 mil no conjunto, com falha explícita.
- Expurgo exige conferir o checkpoint independente, trancar o titular e conferir
  finalidade e preservações. Uma mudança de finalidade já conhecida deve chegar
  ao controle antes da exclusão em uma transação posterior. Na inicialização,
  recuperação e limpeza pertinente antecedem a disponibilidade normal.
- A cópia privada e sua finalidade podem ser recuperadas; esse protocolo não
  reconstrói entradas do ledger, usuários ordinários ou dinheiro desaparecido.

O expurgo de protocolos também foi separado do ciclo da identidade. Após cinco
anos da conclusão, relato e respostas vencidos são removidos, salvo preservação
específica vigente. `details_retired_at` registra a retirada do relato; permanece
uma mensagem explicativa, não o texto anterior. Se uma cópia contratual ainda
depender do protocolo, conservam-se seu vínculo, tipo, estado e datas mínimos
para conferir o fato. A manutenção não muda `created_at` ou `resolved_at`.
O registro mínimo é removido somente após um checkpoint sem a cópia dependente,
evitando que a própria limpeza pareça perda de fatos por restauração antiga.
Uma recuperação reaplica essa retirada de conteúdo antes da disponibilidade.
Exportações informam a data da retirada, sem devolver o relato apagado.
Na manutenção normal, uma linha ocupada pode ser tentada na rodada seguinte.
Na recuperação, o coordenador espera pelas travas pertinentes: não declara a
limpeza concluída depois de simplesmente pular um titular/protocolo ocupado.
Um teste de concorrência mantém o titular trancado por outra transação e confere
que a recuperação só prossegue depois da liberação.

Na regressão ampla apareceu um atraso de uma rodada no cursor após liberar uma
preservação: o percurso agora retoma o início na mesma chamada ao alcançar o fim
da fila. O teste existente manteve a exigência de expiração; não foi afrouxado.
O teste posterior de protocolo/identidade também encontrou uma transição que
precisava de duas etapas: se a obrigação já terminou e o limiar está vencido, o
snapshot deve primeiro registrar a finalidade encerrada, em vez de filtrar a
cópia antes de confirmar esse estado. Só a transação posterior pode eliminá-la.
Uma cópia vencida presente nesse checkpoint transitório não recebe novo prazo e
não é restaurada como finalidade vigente sem uma dependência efetiva.

O checkpoint registra o que foi observado pelas operações de privacidade. Não é
um backup atômico de toda operação financeira: fatos posteriores ao último
checkpoint continuam dependendo de backup financeiro e conciliação com o
provedor. Ausência de fatos já registrados protege a identidade, mas não produz
saldo e não certifica recuperação financeira completa. Esse limite deve entrar
no procedimento operacional de restauração.

## Fundamento e documentos

A finalidade é execução da obrigação ainda existente e exercício regular de
direitos, conforme o tratamento, com necessidade e minimização. A
[LGPD, arts. 6º, 7º e 15–16](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm)
não estabelece uma guarda universal de cinco anos para todos os dados. O
[Código Civil, arts. 205–206](https://www.planalto.gov.br/ccivil_03/leis/2002/l10406compilada.htm)
também diferencia os prazos conforme a pretensão. O limiar operacional herdado
de cinco anos não é parecer de que toda obrigação prescreve nesse prazo nem
substitui preservação específica fundamentada.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.** Validar prazos por espécie de
obrigação, marcos e hipóteses de suspensão/interrupção, obrigações fiscais e
enquadramento do serviço antes de publicação. A manutenção de uma pendência deve
ter tratamento operacional; não pode ser pretexto para abandono permanente.

Privacidade r7 permanece em `draft`; a fonte exata da r6 está preservada em
`history/privacy-r6.md`, conferida pelo hash da evidência V42. Termos financeiros
e regras de crédito, dívida, contestação e revisão administrativa não mudaram.
Não foi criado aceite retroativo nem publicada minuta durante os testes.

## Verificação e pendências

| Ataque ou cenário verificado | Propriedade exigida |
| --- | --- |
| Crédito antigo ou recebível aberto depois do encerramento | Identidade mínima e saldo preservados |
| Total histórico já pago/restituído | Valor terminal não perpetua identificação |
| Liquidação e consultas repetidas | Fato de negócio informa o limiar; polling não o renova |
| Recuperação com carteira anterior vazia | Não inventar dinheiro ou quitação nem perder identidade com fatos conhecidos ausentes |
| Remover fatos/revisão do banco e tentar substituir o controle | Recusar regressão e manter o controle válido |
| Crédito de passageiro sem vínculo com veículo | Não prolongar guarda da placa antiga |
| Exclusão executada muito depois da preparação | Datas reais e limiar original; não recriar evidência vencida sem finalidade |
| Protocolo com conteúdo vencido e dependência financeira | Remover relato/respostas, conservar somente testemunho mínimo e depois retirá-lo |
| Restaurar relato vencido ou liberar hold específico | Reaplicar expurgo antes da disponibilidade; respeitar hold enquanto vigente |
| Migração V42 e formatos antigos de controle | Preservar dinheiro/bytes/datas; reconhecer somente formatos conhecidos e completos |

Essa é uma revisão técnica do escopo V43, realizada pelo implementador. Não é um
red team independente nem encerra os red teams jurídico/técnico globais pedidos.

Logs, contagens, casos e hashes ficam em
[evidência V43](evidence/financial-identity-purpose-26oct.json). Os testes usam
somente pessoas/valores sintéticos no PostgreSQL de teste em loopback, porta
55432. Nenhuma cobrança real, envio de e-mail ou alteração na demonstração foi
necessária. Rodadas se sobrepõem e não devem ser somadas.

| Rodada delimitada | Unitários | Integrações | Resultado |
| --- | ---: | ---: | --- |
| Reprodução inicial da perda de identidade | 5 | 1 | Uma falha reproduzida antes da correção |
| Primeira implementação | 5 | 13 | Aprovada |
| Casos de recuperação, documentos e segurança | 45 | 48 | Aprovada |
| Datas, observações repetidas e exclusão tardia | 10 | 39 | Aprovada |
| Primeira regressão ampla | 50 | 130 | Uma falha no cursor de expiração após hold |
| Protocolo e expiração dependente | 10 | 62 | Um erro no checkpoint da transição já encerrada/vencida |
| Confirmação das três correções específicas | 10 | 3 | Aprovada |
| Regressão ampla corrigida | 50 | 240 | Aprovada; inclui autenticação 18, pagamentos 24 e Mercado Pago 65 |
| Após reforçar as travas na recuperação | 50 | 63 | Aprovada; 17 cenários financeiros, 13 de migração, 18 de direitos e 15 jurídicos |

A última rodada usa a fonte final. A rodada de 240 integrações antecede somente
o reforço de espera pelas travas de protocolo na recuperação; a rodada posterior
exercita esse ajuste, inclusive concorrência. Não são 303 cenários distintos.

A inspeção visual permanece adiada por instrução do usuário após o bloqueio do
Chrome. Nesta etapa não houve mudança de CSS, JavaScript, componentes ou assets.

Continuam abertos: ciclo finito dos próprios pagamentos, contratos, ledger,
eventos financeiros e prova mínima de elegibilidade; estatísticas efetivamente
anônimas; minimização restante; recuperação financeira/externa e capacidade;
revisão final cruzada das seis minutas, red teams e matriz dos 44 requisitos;
jornadas visuais e publicação coordenada. A guarda proporcional da identidade
não equivale a fechar essas frentes.
