# Documentos e aceites: comportamento implementado no servidor

Checkpoint de 16/10/2026. **Implementação em validação, ainda não publicada na demonstração V26.** Os arquivos em `backend-java/src/main/resources/legal/` continuam como minutas (`publicationState: draft`). Não apresentar essa etapa como conclusão de toda a camada de privacidade. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Conteúdo e identidade

Seis documentos consolidam os treze temas solicitados: Termos de Uso, Privacidade, Termos do motorista, Participação, Pagamentos e Segurança. Participação cobre passageiro, avulsa e mensal; Pagamentos cobre cancelamento, crédito, devolução, contestação e repasse; Segurança cobre comunidade, revisão, suspensão e encerramento. Privacidade inclui retenção e tecnologias necessárias. A consolidação evita políticas duplicadas.

O responsável informado é Diego Alves Fagundes, pessoa física, em Novo Gama/GO; contato de privacidade: privacy@example.invalid Não foram inventados documento, empresa ou endereço. Identificação comercial, enquadramento do transporte, retenção e transferências internacionais ainda exigem adequação antes da operação comercial.

## Evidência e publicação

- Corpo e título formam o conteúdo canônico, com SHA-256 e versão derivada de revisão explícita + hash. A leitura pública recalcula a integridade.
- Versões publicadas são imutáveis e continuam disponíveis pelo endereço de arquivo. A revisão humana não pode ser reutilizada para mudar silenciosamente conteúdo ou contexto público; um binário antigo não retrocede a versão atual.
- V30 registra natureza do documento, escopos, avisos individuais e regras de integridade dos recibos. Natureza/hash precisam corresponder ao documento publicado. Identidade, versão, origem e instante do aceite não podem ser editados; exclusão da prova exige prazo e condições de retenção.
- Cada recibo pertence ao titular da sessão e registra documento, versão, hash, natureza, origem e instante. IP não é coletado por padrão; leitura de aviso não se transforma em consentimento de marketing.
- Publicar/avisar não cria aceites. Avisos internos são deduplicados. Mudança material exige nova manifestação para futuras contratações; mudança informativa não material não fabrica prova de leitura nem apaga o recibo anterior.
- Catálogo em `draft` não é publicado pelo inicializador. Produção recusa iniciar com esse estado. As publicações usadas nos testes são explicitamente sintéticas e restritas ao banco descartável.

## Integrações funcionais

| Ação | Regra implementada |
| --- | --- |
| Cadastro novo | Termos e leitura da privacidade explícitos; cinco referências atuais obrigatórias; recibos, usuário, perfil e envio de confirmação na mesma transação |
| Endereço já cadastrado | Resposta genérica preservada; não altera recibos, conta ou marketing da pessoa existente |
| Candidatura de motorista | Exige documentos gerais e manifestação específica sobre os termos do motorista previamente registrada pelo endpoint de aceite |
| Publicar/editar oferta ou criar rotina | Exige documentos gerais e do motorista atuais |
| Reabrir rotina | Exige aceites atuais; pausar não exige novos aceites |
| Solicitar avulsa/mensal | Passageiro precisa de documentos gerais atuais; motorista precisa dos documentos aplicáveis. Indisponibilidade do outro participante é genérica, sem expor o histórico dele |
| Preparar renovação no worker | Sem aceite atual, não cria reservas, pagamentos ou retenção de vagas; mantém resultado de revisão da renovação e aviso |
| Contrato já existente | Alteração dos termos não bloqueia cancelamento, execução de viagem paga, devolução ou exercício de direitos. Não altera preços nem registros financeiros antigos |
| Pedido de exclusão | Confirmação inclui a regra efetivamente lida e a janela de segurança; alteração anterior ao envio exige nova leitura. Pedido já aberto conserva a regra e os dias aceitos |

As verificações acontecem após os bloqueios ordenados dos participantes e protegem a referência publicada durante a operação. Repetição idempotente de operação já concluída continua devolvendo o resultado histórico; não constitui contratação nova nem aceite inventado.

## APIs

Públicas, somente leitura: `GET /api/v1/legal/documents`, `GET /api/v1/legal/documents/{slug}` e `GET /api/v1/legal/documents/{slug}/{version}`. Leitura não exige conta.

Autenticadas: `GET /api/v1/privacy/legal`, `GET /api/v1/privacy/legal/history` e `POST /api/v1/privacy/legal`. Mutação exige origem/CSRF; não recebe um `userId` escolhido pelo cliente. Manifestação desatualizada retorna 409; ausência de manifestação necessária para uma contratação nova retorna 428.

## Evidência e limites

- `.local/privacy-legal-registration-verified.log`: 18 unitários + 42 integrações aprovados (autenticação 17, e-mail 15 e documentos dez).
- `.local/privacy-legal-service-verified.log`: 15 unitários + 71 integrações aprovados (identidade/motorista 11, ofertas 12, manutenção 20, documentos 12 e direitos 16). Inclui recusa real dos endpoints, termo específico do motorista, atualização material, cancelamento preservado, confirmação de exclusão antiga rejeitada e prazo congelado.
- Na rodada anterior dos mesmos controles, rotinas 15 e solicitações 16 passaram; três falhas de expectativa dos testes antigos foram corrigidas (catálogo de rotas e precondição de recibos). Não somar repetições como testes novos.
- `.local/privacy-legal-financial-regression.log`: três unitários + 162 integrações aprovados (pagamentos 19, Mercado Pago 61, arquivos oito, contratos 33, ciclo de caronas 26, administração de contas 14 e um caso específico de renovação). A mudança de termos sem novo aceite gera revisão sem vagas retidas, reserva paga, recebimento ou aceite inventado. O upstream Mercado Pago é simulado local; não houve nova cobrança real.
- `.local/privacy-legal-replay-final.log`: 15 unitários + 29 integrações aprovados (documentos 13, direitos 16). Reenviar o mesmo aceite ou pedido de encerramento após publicação de regra nova devolve o resultado histórico, sem criar recibo da nova versão nem novo encerramento. A regra vigente fica bloqueada para publicação concorrente durante uma confirmação nova.
- A varredura final de 640 arquivos não encontrou valores de segredos configurados nos locais examinados; não comprova proteção do armazenamento. A limitação de ACL do disco D: em exFAT permanece pendente.

Ainda faltam interface, conclusão das regras de desativação/banimento, correções sensíveis, retenção financeira/logs, backup/restore físico, minimização final e revisão de consistência dos documentos com tudo isso. A ausência desses itens impede apresentar as minutas como políticas operacionais definitivas.
