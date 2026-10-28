# Estados de conta, decisões e retorno

Implementação Java/PostgreSQL em validação em 16/10/2026. **As rotas e a V31 ainda não foram implantadas no sandbox de demonstração V26; a interface desta etapa permanece pendente.** Este documento não é uma política contratual publicada. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA**, especialmente proporcionalidade das sanções, retenção de evidências e valores em disputa.

## Diferenças efetivas

| Estado/ação | Efeito implementado | Retorno e proteção de valores |
| --- | --- | --- |
| Logout | Revoga a família de sessão pelo fluxo existente | Não encerra conta, não apaga dados, não cancela caronas |
| Desativação voluntária | Exige senha/manifestação; deixa de participar de novas combinações, pausa rotinas, desliga renovação e marketing, revoga outras sessões/tokens | Mantém sessão atual e permite novo login limitado; viagens pagas continuam operáveis. Titular pode reativar com senha. Não religa marketing, renovação nem ofertas |
| Suspensão | Decisão administrativa preventiva já existente; impede novas combinações e separa a análise dos trechos/valores afetados | Continua com segurança, direitos e valores acessíveis nos limites aplicáveis; análise financeira não é desfeita automaticamente ao restaurar o serviço |
| Banimento | Administrador reautenticado informa motivo público, justificativa interna e prazo individual de restrição entre um e o máximo configurado, limitado a 365 dias | Revoga sessões, inicia encerramento pela plataforma, admite novo login limitado a direitos/segurança/valores, motivo e recurso; não presume fraude por existir chargeback |
| Exclusão pelo titular | Confirmação com senha, versão/prazo lidos, janela reversível e processamento após obrigações | Pode ser cancelada antes do processamento irreversível. Reversão não remove sanções nem escolhas de desativação |
| Encerramento pela plataforma | Decisão fundamentada inicia processamento do perfil; se já havia pedido do titular, conserva prazo e evidência originais | Titular pede revisão, mas não cancela unilateralmente uma decisão administrativa. Não mantém perfil integral indefinidamente apenas para prevenir retorno |
| Perfil removido | Processamento existente remove identificação pública, acesso e arquivos não retidos, preservando vínculos mínimos necessários | Nenhum login ou reativação do perfil removido. Valores/evidências remanescentes são pseudonimizados e tratados pelo canal de atendimento, com identificação proporcional |

`voluntarily_deactivated_at` conserva a escolha do titular durante uma sanção superveniente. Uma revisão administrativa que libere o serviço devolve a conta ao estado desativado quando essa escolha continua vigente; a reativação exige ação do titular. O marcador é exportado ao próprio usuário e removido junto do perfil.

## Decisão administrativa

A V31 acrescenta os estados, o marcador de escolha voluntária, a ligação da restrição ao registro histórico e `privacy_account_decisions`. Decisões registram ação, estados anterior/resultante, código delimitado de motivo, razão pública, justificativa cifrada, administrador, instante, prazo individual quando houver banimento e retenção inicial de cinco anos. Evidências não podem ser editadas; expiração respeita retenção específica vigente. Cinco anos é prazo operacional proposto para defesa, não obrigação universal de guardar todos os dados por esse prazo.

As ações são distintas:

1. `ban`: aplica restrição, conserva efeitos financeiros da revisão preventiva e inicia remoção do perfil.
2. `close`: inicia encerramento pela plataforma sem criar automaticamente restrição de recadastro.
3. `lift_ban`: libera o identificador restrito e deixa a conta suspensa para decisão sobre o restante do caso.
4. `cancel_closure`: interrompe somente um encerramento iniciado/assumido pela plataforma que ainda seja reversível.

Se o recurso for procedente, o administrador precisa tratar expressamente banimento, encerramento e restauração de serviço, além da análise financeira eventualmente aberta. A operação anterior de revisão de conta não remove um banimento. Uma restauração de serviço também não restaura rotinas, renovações, marketing ou parcelas bloqueadas. Não há novo fluxo obrigatório de contato para a pessoa exercer o recurso: ela usa o protocolo de privacidade existente; após a exclusão, o contato público continua disponível.

O titular vê motivo público, ações e instantes, não a nota interna de investigação. Somente administrador autorizado consulta a nota, com registro de acesso, inclusive à evidência retida após remoção do perfil. A exportação inclui as decisões públicas do próprio titular. Justificativas internas não são gravadas em logs, notificações, resposta idempotente ou hash simples de texto: ficam cifradas; a impressão da requisição usa HMAC.

## Restrição de recadastro

O identificador é HMAC de e-mail normalizado, com separação de finalidade, código de motivo e vencimento por decisão. Não contém e-mail bruto, documento ou impressão de dispositivo. **Não é anonimização:** é um identificador protegido, relacionável no contexto autorizado.

Banimento e cadastro usam a mesma trava transacional por identificador antes dos demais bloqueios pertinentes. Cadastro sob restrição não insere usuário, recibo ou envio, mantendo a resposta genérica. A remoção do perfil não elimina prematuramente a restrição. Expiração ou liberação encerra seu uso, e o job elimina registros vencidos/liberados. Depois do prazo, um novo cadastro recebe identidade própria e seus aceites explícitos; não recebe um aceite atribuído à conta removida.

O mecanismo cobre o mesmo endereço normalizado. Não garante detectar a pessoa usando outro endereço, não faz inferências de fraude por semelhança de perfil e não mantém uma base de documentos para rastrear qualquer retorno. A limitação deve permanecer clara na operação. Procedimentos de rotação das chaves e recuperação precisam ser concluídos junto da etapa de backup/armazenamento protegido antes da publicação operacional.

## APIs

- Titular: `GET /api/v1/privacy/account`; `POST /api/v1/privacy/account/deactivate`; `POST /api/v1/privacy/account/reactivate`.
- Administração: `GET /api/v1/admin/privacy/accounts/{id}`; `POST /api/v1/admin/privacy/accounts/{id}/decision`.
- Recursos usam `POST /api/v1/privacy/requests` com `kind=appeal`; não suspendem automaticamente decisões ou efeitos financeiros.

Rotas têm contratos estritos e autorização explícita. Mutações exigem origem, CSRF, idempotência e senha quando sensíveis. Titular não escolhe um `userId` diferente. Decisões conferem estado esperado, papel/permissão atuais e sessão sob bloqueio ordenado de participantes; alterações concorrentes são reavaliadas.

## Evidência e limites da etapa

- `.local/privacy-account-controls-foundation.log`: 15 unitários + 61 integrações aprovados (direitos, documentos, compatibilidade de migrações, contas administrativas e catálogo HTTP).
- `.local/privacy-account-controls-http-verified.log`: 15 unitários + 35 integrações aprovados (28 casos de ciclo de caronas, com dois novos de desativação, e sete de controles de conta).
- `.local/privacy-account-controls-final.log`: 15 unitários + 45 integrações aprovados; nove casos de controles, incluindo prazo escolhido, recadastro após exclusão/expiração, recurso, preservação do prazo de exclusão e saldo/identidade mínima cifrada, além de documentos, direitos e migrações.
- `.local/privacy-account-auth-payment-regression.log`: três unitários + 37 integrações aprovados: autenticação, e-mail, invariantes de schema e contestação financeira assinada após remoção do perfil, sem restaurar acesso ou duplicar efeitos.
- `.local/privacy-account-states-preservation.log`: dez casos de controles de conta, 14 de contas administrativas e 16 de direitos passaram, além de três unitários. A única falha dessa rodada foi a comparação do schema antigo, que ainda incluía a coluna nova entre os objetos que deveriam permanecer idênticos. O caso novo comprova que a revisão de sanção devolve a conta à desativação voluntária e só o titular a reativa.
- `.local/privacy-account-compatibility-final.log`: em 17/10, três unitários + sete integrações de migração passaram após explicitar a coluna aditiva e verificar seus tipo/nulabilidade/default e os novos estados. As demais colunas, índices, constraints e triggers antigos continuam sendo comparados. Rodadas se sobrepõem; não somar como casos únicos.

Atualização de 23/10: interface administrativa integrada e AP-01 das filas resolvido após revisão independente; correções sensíveis, gestão de retenções, armazenamento protegido e ensaios físicos de restore foram implementados em etapas posteriores descritas nos documentos próprios. Isso não encerra a operação humana, a jornada renderizada dos direitos próprios, recuperação externa/holds posteriores ao snapshot, retenção financeira/logs, minimização restante, publicação e auditoria integral. Não declarar essas partes concluídas por causa dos testes acima.
