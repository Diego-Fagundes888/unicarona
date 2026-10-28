# Consistência das seis minutas e do comportamento

Conferência de desenvolvimento de 26/10/2026. Catálogo em `draft`: Termos r1,
Privacidade r11, Motorista r3, Participação r1, Pagamentos r1 e Segurança r1.
Este documento cruza as minutas atuais com controles e provas delimitadas; não
declara a operação publicada nem substitui a auditoria final dos 44 requisitos.

Complemento de minimização: Privacidade r11 descreve a [proteção dos dados acadêmicos](Dados_Academicos_Entre_Participantes.md)
nas projeções de oferta da contraparte, com r10 preservada exatamente. Valores
próprios, elegibilidade e compatibilidade permanecem; correspondência de turma
continua inferível e não é descrita como anônima. Mudança informativa em draft,
sem publicar ou registrar aceite retroativo. QA visual segue adiado.

Complemento V46 sem alteração das minutas: o [ensaio nativo de recuperação](Recuperacao_Transacional_PITR.md)
passa a demonstrar eventos posteriores a uma base física em captura finita,
seguidos do replay independente das regras de privacidade. Isso não autoriza
prometer recuperação sem perda ou publicação: serviço contínuo, cópia externa,
RPO/RTO e demais retenções permanecem pendentes. Não foi criado aceite ou versão
jurídica nova para um mecanismo ainda não instalado na demonstração protegida.

Os seis documentos estão em `backend-java/src/main/resources/legal/`. A política
de cancelamento/reembolso integra Pagamentos; regras avulsas/mensais integram
Participação; retenção/cookies integram Privacidade; suspensão/encerramento
integram Segurança. Não há um segundo texto concorrente para a mesma regra.

| Tema e documentos cruzados | Regra coerente a preservar | Comportamento e prova pertinente | Limite ou pendência |
| --- | --- | --- | --- |
| Responsável e vínculo: Termos §1; Privacidade §1 | Diego Alves Fagundes como pessoa física; contato e local por configuração; projeto independente da UNICEPLAC | Contexto do catálogo e publicações é verificado por `LegalHttpIT`; fontes anteriores preservadas | Identificação comercial exigível e enquadramento real ainda precisam de validação; não inferir CNPJ/endereço/afiliação |
| Idade e motorista: Termos §3; Motorista §1; Privacidade §2 | 18 anos; nascimento/e-mail/autodeclaração não são garantia de autenticidade; upload não certifica habilitação | Cadastro/correção/restrição e revisões têm testes próprios; `IdentityCorrectionHttpIT` integra a regressão V43 | Operação de conferência real de identidade, idade, CNH e veículo ainda é requisito de lançamento |
| Oferta, preço e mensal: Termos §5; Participação §§1–6; Pagamentos §§1,3,6 | Contratação por agenda/trecho, versão de preço aceita, vaga paga preservada e ausência distinta de cancelamento | Guardas de reserva/pagamento e imutabilidade seguem em `PaymentHttpIT`/migrações; V43 não os altera | Validar enquadramento da atividade, regras de disponibilidade e direitos legais no caso concreto |
| Cotação sem envio: Privacidade §8; Termos §5; Pagamentos §§1–3 | Cotação/pagamento local vencido sem recebimento, reserva, envio ou caso pertinente não equivale a contrato cumprido/recebido; janela específica de 90 dias | V44 e `UnsubmittedCheckoutRetentionIT` conferem conjunto, cache/avisos, fatos conhecidos e restauração. Distribuição apenas preparada não é recebimento | Não aplicar a pedido remoto recusado ou resultado incerto. Ciclo dos recebidos/ledger continua pendente; tentativas sem marcador coerente exigem conferência |
| Renovação: Participação §§3,6; Termos §5 | Intenção de renovar/carência não é autorização de débito automático nem período já pago | Documentação distingue renovação mensal de pagamento recorrente; V43 não implementa recorrência do PSP | O cenário de renovação por callback não deve ser anunciado como assinatura com cobrança automática sem esse produto |
| Sem aula: Participação §5; Pagamentos §7 | Nas novas liquidações Java, crédito integral; parcela disputada segue análise; decisões históricas não são reescritas | `Sem_Aula_Regra_Revisada.md` e testes financeiros próprios; regressão de pagamentos preservada | Não confundir fixture já liquidada pelo modelo anterior com regra nova nem prometer restituição já executada |
| Crédito/devolução/repasse: Pagamentos §§4,8,10; Motorista §5; Segurança §7 | Crédito não afasta restituição legal; processamento não equivale a envio concluído; provedor precisa estar habilitado | Comandos, confirmação, conciliação e falhas em `MercadoPagoHttpIT`/`PaymentHttpIT` | Recebimento comercial de motoristas e operação real continuam sujeitos à disponibilidade efetiva; uma API interna não elimina essa dependência |
| Estorno e dívida: Pagamentos §9; Segurança §§2–3; Termos §§9–10 | Só a diferença apurada do crédito de cancelamento gasto gera recebível; carona realizada sem esse crédito segue decisão administrativa; contestação não prova fraude | `PassengerDebtService`, `MercadoPagoReviewService` e casos do provedor; V43 preserva identidade mínima sem inventar lançamento | Revisão humana e solução dos valores não são substituídas por retenção ou por bloqueio de compras |
| Estados e reversão: Segurança §§4–6; Termos §10; Privacidade §10 | Logout, desativação, sanção e exclusão distintos; janela configurada, senha e reversão antes do irreversível; não reativar marketing/ofertas automaticamente | `AccountControlsHttpIT`, `PrivacyRightsHttpIT`, `PrivacyClosureTransactions` | Jornadas visuais remanescentes e publicação coordenada ainda não concluídas |
| Pendências e fim do perfil: Segurança §7; Pagamentos §10; Privacidade §8; Termos §10 | Operações em curso podem adiar processamento; saldo e disputa não exigem manter perfil público; encerramento não é quitação | `PrivacyClosureTransactions.obligations()` marca operações em curso como impeditivas; `reviews` não impede por si; `balances()` informa valores separadamente | A expressão antiga da matriz “aguarda operações/valores/disputas” era imprecisa e foi corrigida; não impor bloqueio geral de exclusão por qualquer dívida |
| Identificação, protocolo e defesa: Privacidade §8; Pagamentos §10; Segurança §7 | Cópia mínima restrita enquanto necessária; fato pertinente pode informar limiar; polling/restore não renovam; relato vencido não acompanha indefinidamente a cópia | V43, `PrivacyContractIdentityPurpose`, `PrivacyProtocolRetention`, `FinancialIdentityRetentionIT` | Não chamar cinco anos de prescrição universal; expurgo das próprias tabelas financeiras e recuperação completa continuam abertos |
| Documentos de motorista: Motorista §8; Privacidade §8; Segurança §7 | 30 dias de análise/30 após decisão para anexos; decisão/aprovação têm finalidade distinta; hold não reabre download ordinário | `DriverDocumentsRetentionIT`, V40 e retenção de metadados; formatos antigos de snapshot conservam datas | Reavaliar prova mínima de aprovação junto ao futuro expurgo de contratos/ledger; arquivo removido não certifica validade atual de CNH |
| Dados entre participantes: Motorista §6; Participação §7; Privacidade §4 | Dados para encontro/viagem, sem marketing próprio ou exposição indevida; histórico não libera nova localização privada; curso/período/turma exatos da contraparte fora da oferta | `ParticipantIdentity`, projeções de leitura condicionadas ao titular e compatibilidade mensal; complemento V46 acadêmico e documentação de localização | Completar revisão de identificadores, texto livre, histórico/caches e inferência pela compatibilidade; não afirmar anonimização por remover nome/curso |
| Aceites e mudanças: Termos §§1,10; Privacidade §§3,10; catálogo | Contrato, ciência de privacidade e marketing separados; versão/hash/recibo; nenhum aceite retroativo ou consentimento criado por nova redação | `LegalDocumentService`, publicação explícita e `LegalHttpIT`; minutas não publicadas nos testes | Executar a jornada de atualização material renderizada e revisar a classificação material antes da publicação definitiva |
| Responsabilidade e conflitos: Termos §§6,8–11; Motorista §7; Segurança §8; Pagamentos §10 | Deveres por atividade, falha própria não excluída; sem teto universal, foro abusivo ou arbitragem obrigatória; rótulo de intermediação não decide enquadramento | Texto atual conserva essas ressalvas; não há mecanismo técnico que produza uma imunidade jurídica | Red team jurídico final e análise de transporte remunerado/recorrente, seguro, CDC, tributos e Goiás/DF continuam necessários |

## Critérios de publicação ainda não satisfeitos

No complemento V44, acesso/portabilidade/restrição pendentes conservam dados
ainda disponíveis até atender o direito. Pedido de eliminação sozinho não bloqueia
o expurgo da cotação. Evento financeiro durável ainda não conciliado e tarefa de
envio ativa/desconhecida não são presumidos como ausência de envio/recebimento.
Essas guardas não criam crédito, quitação ou um novo prazo de negociação.

V46: [PIN de embarque](PIN_Embarque_Retencao.md). Privacidade r10 informa prazo e
retirada da credencial; Motorista §3 mantém uso apenas para embarque real. O
código preserva `boarded_at`, estado/atos e valores, sem tratar segredo como prova
financeira permanente. A própria exportação informa datas sem PIN/HMAC. Expiração
restaurada não renova a janela; recuperar consumo ocorrido depois do backup não
foi implementado nesta etapa e continua sendo requisito antes de publicar.

V45 acrescenta [expurgo de tentativas de transferência definitivamente falhas](Transferencias_Falhas_Retencao.md).
A minuta r9 descreve cinco anos de finalidade e as condições de conciliação por
conta; r8 foi preservada integralmente. O código só aceita o protocolo genérico
de desenvolvimento reconhecido, preserva dependências/holds e não apaga operações
recebidas ou devoluções Mercado Pago. Conclusão de disputa tem finalidade própria;
polling, restore e recibo repetido não criam prazo. Não habilita repasse real.

O texto de retenção financeira descreve finalidade, não prova que todo dado
financeiro já possui expurgo finito implementado. A matriz dos 44 requisitos
continua registrando essa lacuna. Também permanecem recuperação externa,
estatísticas verdadeiramente anônimas, volumes/categorias e minimização restante.

Os placeholders de fornecedores e identificação devem ser resolvidos pela
configuração real, sem publicar exemplos ou promessas de infraestrutura não
contratada. O pacote final deve ser novamente conferido depois das pendências;
esta matriz não autoriza publicar só uma das seis minutas desconectada das demais.

A inspeção visual atual foi adiada por instrução do usuário. Os testes HTTP não
certificam leitura, contraste ou execução da jornada na interface publicada.
Evidências V43 ficam em `evidence/financial-identity-purpose-26oct.json`; não somar
rodadas sobrepostas nem interpretar testes sintéticos como dinheiro real recebido.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**
