# Encerramento e tratamento dos dados — implementação do backend

Documento técnico de acompanhamento, de 16/10/2026. Não substitui a política pública, a interface nem a validação jurídica. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

Complemento 25/10: [confirmação de supressões](Confirmacao_Supressoes.md) separa
preparo, conclusão do banco/arquivos e liberação do diário. Formatos antigos são
lidos sem atribuir conclusão por idade ou rotação. Perfil parcialmente restaurado
é reprocessado mesmo com marcador de exclusão; arquivos cuja preservação terminou
voltam à supressão antes da prontidão. Provas e limites ficam no complemento,
sem anúncio de publicação na demonstração V26.

## Estados e segurança

- Pedido autenticado em `POST /api/v1/privacy/closure`, com senha novamente, confirmação expressa das consequências, versão da política e chave de idempotência. A conta é sempre obtida da sessão; não existe campo `userId` de destino.
- O estado `closing` impede novas combinações, ofertas e compras. Os serviços também conferem o estado ao criar contratos, dentro das transações.
- A suspensão anterior continua como `suspended`. Cancelar a exclusão não restaura uma conta suspensa, nem religa marketing, ofertas pausadas ou renovação automática.
- A janela padrão é de sete dias. É uma decisão de segurança, não um prazo imposto pela LGPD. A confirmação de senha reduz o risco de uma sessão roubada; a janela oferece tempo para perceber e reverter o pedido. O risco de conservar dados nesse intervalo exige transparência, bloqueio de uso novo e atendimento separado de pedidos específicos.
- As demais sessões são revogadas ao receber o pedido. A sessão atual pode acompanhar pendências e cancelar o encerramento com senha. O processamento irreversível revoga todas as sessões e tokens.
- `pending_obligations` indica operação em andamento. `scheduled` indica que a janela pode ser concluída sem essas operações. `erasing` indica processamento irreversível. `completed` significa perfil removido e arquivos sem retenção específica eliminados; não significa destruição de obrigações ou de toda evidência retida.

## Pendências e operações existentes

O preview consulta reservas, viagem iniciada, pedidos abertos, cobranças, comandos incertos do provedor, devoluções, transferências e pagamentos de regularização. Não altera seus valores para liberar a exclusão. Saldos e dívidas são apresentados separadamente.

Uma viagem paga pode terminar, registrar embarque e receber confirmação durante `closing`. Também continuam disponíveis os cancelamentos de contratos existentes, privacidade e carteira. Não é permitido iniciar outra contratação. A suspensão de segurança não recebe automaticamente essas permissões adicionais de `closing`.

Disputas e saldos não são usados como condição para manter o perfil público indefinidamente. Identificação mínima e contratos necessários podem permanecer sob retenção restrita. O canal administrativo para tratar valores após o encerramento ainda precisa estar integrado à interface e à política pública.

O repositório de participantes pode bloquear uma linha técnica de conta excluída para processar eventos financeiros posteriores. Isso não permite autenticação nem uma nova contratação. O teste de integração do Mercado Pago confirma que uma contestação assinada posterior à exclusão é conciliada sem recriar o perfil, duplicar o recebimento ou restaurar a sessão. O provedor nesse teste é uma simulação local explícita do protocolo; não é prova de uma nova operação real de produção.

## Perfil, localização, arquivos e evidência

| Dado | Ação no processamento |
| --- | --- |
| Nome, e-mail e senha de acesso | Substituídos por identificação técnica de conta excluída e credencial desabilitada |
| Nascimento, telefone e foto do perfil | Removidos dos campos do perfil |
| Perfil acadêmico e perfil de motorista | Linhas do perfil removidas |
| Veículo | Retirado da operação; modelo, cor e placa deixam o cadastro visível. Identificação do veículo de contrato pago pode ser conservada cifrada para defesa |
| Endereços, coordenadas e ponto de encontro próprios | Removidos da rota operacional. Evidência de rota paga fica cifrada por prazo reduzido; disputa vinculada ou retenção específica altera esse prazo |
| Notas próprias e motivos livres de solicitações/reservas | Retirados dos registros operacionais tratados pelo fluxo |
| Comentários de avaliação envolvendo o titular | Removidos; notas numéricas e relações históricas ainda são dados pseudonimizados |
| Sessões e tokens de autenticação | Revogados e removidos; jobs de e-mail de autenticação perdem o conteúdo vinculado |
| Arquivos | Acesso normal removido imediatamente no processamento; eliminação física verificável e repetível, salvo retenção específica ativa |
| Valores, pagamentos, parcelas e ledger | Preservados. Exclusão não é baixa contábil, quitação, estorno nem repasse |
| Snapshot mensal de IDs, agenda e preços | Preservado como evidência do contrato; não é chamado de dado anônimo |

O cofre `privacy_retained_records` usa cifra autenticada, finalidade, base, recurso e vencimento. A identidade mínima é conservada quando há relação financeira, ocorrência ou protocolo pendente. Uma retenção específica de perfil conserva sua cópia cifrada até o vencimento definido. Não são copiados hashes de senha ou credenciais para esse cofre.

Cinco anos para determinadas evidências de defesa é uma política proposta, sujeita a análise da espécie de pretensão, necessidade e proporcionalidade. **Não é obrigação universal de retenção estabelecida pela LGPD.** Rota de contrato pago tem prazo padrão menor, de 180 dias após o processamento; ocorrência vinculada pendente ou hold documentado exige tratamento próprio. Os prazos precisam ser confrontados com os documentos públicos finais.

O registro financeiro técnico e um UUID ligado a outras tabelas **não são anonimização**. A expiração completa de metadados financeiros, a compactação de histórico e a minimização dos demais logs ainda precisam ser concluídas antes de declarar a política de retenção integralmente implementada.

## Falhas de armazenamento

`erased_at` só é gravado após uma remoção verificada ou a constatação de ausência. Falha de disco mantém o arquivo pendente e o pedido em `erasing`, com código operacional sem conteúdo pessoal. A retentativa é adiada para evitar repetição contínua e não impedir o avanço de outros arquivos.

Holds de arquivo possuem justificativa cifrada, revisão e vencimento. Arquivo retido permanece fora do acesso normal. A liberação/expiração permite limpeza posterior pelo worker, inclusive quando o perfil já foi encerrado. A gestão administrativa desses holds e o acesso excepcional auditado ainda precisam de interface/endpoints próprios.

## Proteção ao restaurar backup

O diário independente conserva apenas UUIDs, datas, versão e assinatura HMAC, em uma pasta privada fora dos uploads e do banco. Antes de o perfil ser removido, sua instrução irreversível é registrada e sincronizada no disco. O diário precisa sobreviver à restauração do banco e da pasta de uploads.

Na inicialização, `PrivacyRestoreGuard` verifica a integridade e reaplica supressões necessárias. API, readiness e processamento do worker aguardam essa etapa. Uma alteração inválida do diário impede a recuperação de ser considerada concluída. Perfis e arquivos restaurados de uma fotografia anterior são removidos novamente; saldos e contratos não são descartados para isso.

O teste automatizado restaura linhas, uma sessão e bytes antigos no banco descartável, reaplica o diário duas vezes e verifica a supressão, o isolamento de outra conta e a invalidação do acesso. **Ainda falta repetir a prova com dump/restore físico, integrar a rotação real de backups e definir a cópia protegida do próprio diário.** Não substituir nem apagar esse diretório ao restaurar um backup antigo. Rotação das chaves também precisa preservar a verificação de entradas existentes.

## Expiração operacional já implementada

- Conteúdo de jobs de e-mail concluídos: limpo ao terminar, incluindo o destinatário; a identificação técnica do job permanece.
- Evidência cifrada vencida: removida em lotes, respeitando retenção específica ativa.
- Consentimento: prova de uma autorização ainda vigente é conservada; revogação estende a evidência necessária para o período de defesa. Expiração não reativa marketing.
- Aceites: IP eventualmente registrado é limpo no próprio prazo; evidência contratual não é removida enquanto a relação permanece ativa.
- Protocolos concluídos: expiração de cinco anos, salvo hold. Protocolos ainda pendentes não são apagados para esconder atraso.
- Tokens antigos e famílias de sessões expiradas: limpeza após 30 dias; notificações, após 90 dias, salvo hold específico.
- Restrições de cadastro expiradas/liberadas: eliminadas. A aplicação no cadastro e o fluxo de banimento foram implementados/testados em V31, sem publicação; ver `Estados_Conta_Backend.md`.

## Trabalho restante para a entrega completa

Documentos/aceites, estados/restrições, correções, armazenamento e restauração físicos, rotação e APIs de retenção foram implementados/testados nas V30–V35, sem publicação coordenada. Ver os documentos específicos e o plano atualizado. Permanecem centro de privacidade/interface completa, avisos finais, canal operacional de valores/direitos após encerramento, recuperação externa e restauração das retenções posteriores ao snapshot, expiração de metadados financeiros/logs, estatísticas anônimas, minimização de contato/foto/localização, publicação e revisões finais. O objetivo completo permanece ativo.
