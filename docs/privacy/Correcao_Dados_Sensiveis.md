# Correção sensível de e-mail e nascimento

Implementada no servidor Java/PostgreSQL e validada em banco descartável em 17/10/2026. **A demonstração continua V26; a V32 e a interface nova não foram publicadas nela.** Não apresentar esta etapa como conclusão de toda a camada de privacidade. Textos públicos continuam minutas. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA**, especialmente tratamento de dados quando uma correção revelar menoridade e atendimento/valores associados.

## E-mail de acesso

1. Titular autenticado informa novo endereço e confirma a senha. A API não aceita `userId` escolhido pelo cliente.
2. Login atual permanece válido. Novo pedido substitui o anterior; cancelamento permite desistir da troca.
3. Se o destino puder ser utilizado, um link de 30 minutos é enviado ao novo endereço. O endereço atual recebe aviso de segurança sobre o pedido. Destino ocupado/restrito tem a mesma resposta genérica e a mesma forma de estado pendente, sem envio de confirmação a esse endereço.
4. A confirmação exige a sessão da mesma conta, senha novamente, link válido/não consumido e segurança de acesso compatível com a do pedido. Um link de outra conta, expirado, substituído, cancelado ou anterior a uma troca de senha não altera o login.
5. Disponibilidade e restrições do destino são verificadas novamente sob a mesma trava usada por cadastro e banimento. Uma conta criada nesse endereço depois do pedido não pode ser tomada pela confirmação.
6. Só então o endereço passa a ser o login confirmado. Todas as sessões e tokens anteriores são encerrados/invalidados; avisos de conclusão são preparados para o endereço anterior e o novo. É necessário entrar novamente.

Verificar novo e-mail não reativa conta desativada nem religa marketing, rotina ou renovação. Durante suspensão, banimento ou encerramento, a troca automática não fica disponível: o titular pode abrir protocolo de correção assistida, sem contornar a revisão de segurança. Dados acadêmicos, motorista, pagamentos, saldo e aceites não são substituídos por causa da troca de endereço.

O destino, a URL e o conteúdo congelado do novo envio ficam cifrados na outbox. Token persistido é HMAC, não texto do link. Entrega revalida vigência/destinatário/credenciais antes de chamar o provedor; nenhum bloqueio do banco é mantido durante SMTP/HTTP. Não se promete cancelar uma mensagem já em trânsito ou confirmar sua chegada à caixa de entrada por uma resposta do servidor de envio.

## Nascimento e elegibilidade

A correção exige senha, sessão própria vigente e data de calendário válida, sem data futura. O valor atualizado é autodeclarado; não equivale à autenticação documental da idade ou identidade. Não se conserva o nascimento anterior em log, resposta idempotente ou hash simples de texto. A impressão de repetição usa HMAC e a auditoria registra ação/efeito, sem a data completa.

Quando a correção indicar menos de 18 anos:

- O dado correto é salvo; não se força uma data falsa para manter uma constraint antiga.
- Conta ativa, desativada ou em encerramento passa à restrição de serviço. Nenhum cadastro novo de menor é habilitado. Acesso a privacidade e atendimento permanece disponível.
- Regras já existentes separam o tratamento financeiro dos trechos não iniciados. Testes com mensal e avulsa conservam o pagamento e o valor da parcela em análise; não confiscam valor nem criam dívida ou restrição de recadastro por causa da correção.
- A data não é divulgada ao outro participante em notificações. O motivo de indisponibilidade/financeiro é suficiente sem expor o nascimento.
- Corrigir novamente para uma data adulta não remove a suspensão automaticamente. A restauração administrativa exige revisão e elegibilidade; cancelar exclusão não restaura uma conta menor de idade ao serviço.

Banco, cadastro e revisão usam o mesmo corte de calendário no contexto `America/Sao_Paulo`. Foi corrigido o cálculo legado que adiantava para 29 de fevereiro o aniversário de uma pessoa nascida em 1º de março. Os testes também cobrem o nascimento em 29 de fevereiro e a vedação de ativar/desativar para o serviço uma conta com data menor de idade. O recurso não amplia transporte a menores. Eventual ampliação continua dependente de **VALIDAÇÃO JURÍDICA OBRIGATÓRIA** e nova concepção do serviço.

## Retenção, exclusão e exportação

V32 acrescenta `privacy_email_changes` e o tipo de token `change_email`, além da condição de idade restrita. Há um pedido pendente por conta. Destino e impressão da segurança atual são cifrado/HMAC, sem senha ou token recuperável na tabela.

- Conclusão/cancelamento elimina a informação do destino e a impressão de credenciais; após expiração, o job faz essa limpeza em lotes. Uma retenção específica pode preservar somente o destino cifrado pelo prazo fundamentado.
- Metadados da troca expiram em 30 dias, salvo retenção específica vigente. Token segue sua expiração/limpeza própria. Entrega concluída remove a carga da outbox; falha terminal segue a limpeza operacional já existente.
- Exportação autenticada inclui apenas as trocas do titular e o destino ainda pertinente, decriptado para ele. Não inclui HMAC de credencial, token ou informação de outra conta.
- Exclusão do perfil remove pedidos e envios pendentes. Se houver retenção específica de uma troca, o cofre conserva evidência necessária até seu vencimento, sem impressão de credencial, identificador de token ou flag interna de disponibilidade. Isso é retenção cifrada/pseudonimizada, não anonimização.

## APIs e segurança

`GET /api/v1/privacy/identity`; `POST /api/v1/privacy/identity/email`; `POST /api/v1/privacy/identity/email/confirm`; `POST /api/v1/privacy/identity/email/cancel`; `POST /api/v1/privacy/identity/birth`.

Contratos são estritos; há autorização explícita, origem/CSRF, autenticação adicional, limite de tentativas e idempotência. A confirmação não é um endpoint público que modifica uma conta apenas por conhecer um link. A atualização de idade bloqueia participantes em ordem e reavalia mudanças concorrentes antes dos efeitos sobre contratos. Identificadores e justificativas não são usados para declarar como fraude a simples correção de um dado.

## Evidência

- `.local/privacy-identity-foundation.log`: 15 unitários + 38 integrações de migrações, e-mail/verificação e direitos aprovados na fundação.
- `.local/privacy-identity-http-first.log`: 26 unitários + 74 integrações aprovados (autenticação 17, verificação/e-mail 15, administração 14, identidade/motorista 11, migrações sete, correção dez).
- `.local/privacy-identity-final.log`: 26 unitários + 47 integrações aprovados: correção 12, direitos 16, controles de conta dez, migrações sete e dois casos financeiros de correção de idade. Inclui troca de senha invalidando link, manutenção da desativação e corte correto em anos bissextos.
- `.local/privacy-identity-retention-final.log`: 26 unitários + 34 integrações aprovados (correção 13, direitos 16, invariantes de schema cinco). O caso adicional conserva evidência sob retenção específica, remove dados de credencial e conclui remoção do perfil.

As rodadas se sobrepõem; não somar como testes únicos. E-mails foram capturados por provedor sintético e SMTP/HTTP de teste em loopback; não houve envio real ao Gmail ou nova cobrança neste estágio. Layout-base do e-mail foi preservado e as variantes têm teste de conteúdo/link; a inspeção visual e a revisão independente precisam compor a entrega da interface de privacidade.

Ainda faltam interface integrada, confirmações finais de encerramento, atendimento/gestão de retenções, expiração financeira/logs, minimização, armazenamento protegido, backup/restore e revisão completa dos documentos com a operação final.
