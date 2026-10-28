# Endpoints anteriores à migração

| Método | Rota | Função |
|---|---|---|
| GET | /health | Verificar se o processo responde |
| GET | /health/ready | Verificar conexão e todas as migrations desta versão, incluindo checksums |
| GET | /api/v1/admin/metrics | Métricas agregadas do processo atual e do pool, sem dados de alunos |
| POST | /api/v1/auth/register | Criar conta e enviar confirmação |
| POST | /api/v1/auth/login | Entrar e criar sessão segura |
| POST | /api/v1/auth/verify-email | Confirmar e-mail com token de uso único |
| POST | /api/v1/auth/resend-verification | Reenviar confirmação sem enumerar contas |
| POST | /api/v1/auth/forgot-password | Solicitar recuperação com resposta genérica |
| POST | /api/v1/auth/reset-password | Redefinir senha e revogar sessões |
| POST | /api/v1/auth/refresh | Rotacionar sessão e detectar reutilização |
| POST | /api/v1/auth/logout | Encerrar toda a família da sessão |
| POST | /api/v1/auth/change-password | Trocar senha mediante senha atual |
| GET | /api/v1/auth/me | Consultar perfil da sessão atual |
| POST | /api/v1/drivers/applications | Solicitar análise com documentos privados |
| GET | /api/v1/drivers/applications | Consultar solicitações próprias de motorista com paginação |
| GET | /api/v1/admin/drivers/applications | Consultar cadastros e documentos para análise com paginação |
| POST | /api/v1/admin/drivers/applications/{id}/review | Aprovar ou recusar motorista com trilha de decisão |
| POST | /api/v1/files | Enviar arquivo privado com tipo e tamanho validados |
| GET | /api/v1/files/{id} | Ler arquivo somente com autorização de proprietário ou análise |
| POST | /api/v1/rides | Publicar carona com preço calculado no servidor |
| GET | /api/v1/rides | Buscar caronas publicadas da faculdade do aluno |
| PATCH | /api/v1/rides/{id} | Editar oferta preservando preços contratados |
| GET | /api/v1/rides/{id} | Consultar carona com dados mínimos conforme participação |
| GET | /api/v1/rides/mine | Listar ofertas próprias e histórico; próximos trechos em ordem de saída |
| POST | /api/v1/routines | Publicar rotina com agenda e preço calculado |
| GET | /api/v1/routines | Buscar rotinas da faculdade, com compatibilidade de turma |
| GET | /api/v1/routines/mine | Rotinas próprias, inclusive pausadas |
| GET | /api/v1/routines/{id} | Consultar agenda da rotina conforme vínculo ou oferta publicada |
| POST | /api/v1/subscriptions/preview | Calcular agenda proporcional e exclusões conhecidas |
| PATCH | /api/v1/routines/{id}/availability | Pausar novas adesões ou reabrir rotina |
| GET | /api/v1/subscriptions | Mensais próprios e carência |
| GET | /api/v1/subscriptions/{id} | Acompanhar contrato próprio |
| POST | /api/v1/subscriptions/{id}/cancel-renewal | Desativar renovação preservando período pago |
| POST | /api/v1/subscriptions/{id}/remove | Motorista remover vínculo e creditar parcelas futuras |
| POST | /api/v1/waitlist | Registrar interesse compatível, sem reservar assento |
| GET | /api/v1/waitlist | Consultar interesses próprios |
| POST | /api/v1/waitlist/{id}/leave | Retirar interesse próprio |
| POST | /api/v1/ride-requests | Solicitar avulsa ou agenda mensal completa sem ocupar vaga |
| GET | /api/v1/ride-requests | Solicitações próprias, recebidas ou enviadas, com filtro de situação |
| GET | /api/v1/ride-requests/{id} | Detalhes e agenda congelada de solicitação própria |
| POST | /api/v1/ride-requests/{id}/respond | Motorista aceitar ou recusar pedido pendente |
| POST | /api/v1/ride-requests/{id}/cancel | Encerrar pedido antes da reserva confirmada |
| GET | /api/v1/reservations | Reservas e histórico próprios; PIN só para o passageiro |
| GET | /api/v1/reservations/{id} | Acompanhar reserva própria e dados de embarque |
| GET | /api/v1/reservations/{id}/contact | Contato de participante confirmado, respeitando bloqueios |
| POST | /api/v1/rides/{id}/start | Motorista iniciar trecho de hoje |
| POST | /api/v1/rides/{id}/finish | Declarar conclusão e disponibilidade mensal; abrir janela de 24 horas |
| POST | /api/v1/rides/{id}/cancel | Cancelar trecho próprio com crédito integral das parcelas pagas |
| POST | /api/v1/rides/{id}/no-class | Sem aula posterior ao contrato mensal; confirmação ou contestação separada |
| POST | /api/v1/reservations/{id}/board | Validar PIN individual sem liberar valor |
| POST | /api/v1/reservations/{id}/final | Passageiro confirmar conclusão e liberar sua parcela |
| POST | /api/v1/reservations/{id}/contest | Contestar somente a parcela envolvida |
| POST | /api/v1/reservations/{id}/cancel | Passageiro cancelar avulsa, com política financeira em análise |
| POST | /api/v1/reservations/{id}/absence | Avisar ausência mensal mantendo vaga e valor |
| POST | /api/v1/rides/{id}/time-proposal | Propor alteração coletiva que exige consenso |
| POST | /api/v1/rides/{id}/keep-time | Preservar horário original e retirar proposta |
| POST | /api/v1/reservations/{id}/time-response | Responder proposta de horário; avulsa recusada recebe crédito integral |
| GET | /api/v1/runtime | Ambiente das integrações públicas |
| POST | /api/v1/development/payments/{id}/result | Gateway local: produzir evento assinado para cobrança própria de teste |
| POST | /api/v1/payments/{id}/checkout | Preparar cobrança com crédito opcional; navegador não aprova pagamento |
| GET | /api/v1/payments/{id} | Consultar cobrança própria |
| POST | /api/v1/webhooks/payments | Receber evento autenticado e idempotente do gateway configurado |
| GET | /api/v1/wallet | Saldo próprio calculado do ledger e das parcelas |
| GET | /api/v1/admin/reconciliation | Conferir conservação global dos valores recebidos |
| POST | /api/v1/transfers | Solicitar saque disponível ou devolução de crédito, sem liberar saldo futuro |
| GET | /api/v1/transfers | Histórico próprio de transferências |
| POST | /api/v1/webhooks/transfers | Resultado de saque ou devolução autenticado pelo provedor |
| POST | /api/v1/occurrences | Relatar antes, durante ou depois; acesso preservado em conta restrita |
| GET | /api/v1/occurrences | Acompanhar relatos próprios |
| POST | /api/v1/blocks | Bloquear novas combinações e interromper mensal futuro para análise |
| GET | /api/v1/blocks | Listar bloqueios pessoais com paginação |
| POST | /api/v1/blocks/{id}/remove | Remover bloqueio sem reativar vínculos encerrados |
| GET | /api/v1/admin/occurrences | Fila administrativa de ocorrências pendentes |
| GET | /api/v1/admin/occurrences/{id} | Consultar trechos, valores congelados e decisões relacionadas à ocorrência |
| POST | /api/v1/admin/occurrences/{id}/review | Resolver caso com justificativa e reconhecimento explícito de decisões divergentes |
| GET | /api/v1/notifications | Notificações próprias, com paginação e total não lido |
| POST | /api/v1/notifications/{id}/read | Marcar aviso próprio como lido |
| GET | /api/v1/users/me | Consultar dados próprios |
| PATCH | /api/v1/users/me | Editar perfil com confirmação explícita do impacto nos mensais |
| PATCH | /api/v1/users/me/settings | Preferências próprias; avisos essenciais permanecem ativos |
| POST | /api/v1/users/me/academic-verification | Solicitar validação acadêmica por adapter administrativo |
| GET | /api/v1/admin/academic-verifications | Fila administrativa de vínculo acadêmico |
| POST | /api/v1/admin/academic-verifications/{id}/review | Registrar decisão acadêmica com origem administrativa explícita |
| GET | /api/v1/admin/jobs | Fila de envios com contagens e metadados; sem destinatários, links ou payloads |
| POST | /api/v1/admin/jobs/{id}/retry | Retomar tarefa esgotada com justificativa e controle de versão; preserva identidade e tentativas |
| GET | /api/v1/admin/accounts | Contas de estudantes para análise administrativa; diretório privado |
| POST | /api/v1/admin/accounts/{id}/review | Suspensão preventiva ou restauração com justificativa; finanças são analisadas separadamente |
| GET | /api/v1/admin/pricing | Parâmetros versionados da faculdade |
| POST | /api/v1/admin/pricing | Criar nova versão sem alterar ofertas e parcelas contratadas |
| GET | /api/v1/admin/calendar | Calendário da faculdade com escopo e histórico de desativação |
| POST | /api/v1/admin/calendar | Excluir data de novas contratações; não alterar parcelas pagas |
| POST | /api/v1/admin/calendar/{id}/deactivate | Desativar exclusão com justificativa e histórico preservado |
| GET | /api/v1/catalogs | Faculdades, cursos e regiões disponíveis |
