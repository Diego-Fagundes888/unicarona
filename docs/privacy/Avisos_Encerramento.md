# Avisos essenciais de encerramento

Backend V36 em validação, sem publicação na demonstração V26. Usa o provedor e a fila de e-mail existentes; os ensaios desta etapa usam destinatários sintéticos e transportes locais/simulados, sem novo envio real.

## Mensagens e fatos confirmados

| Aviso | Quando é preparado | Conferência antes de enviar |
| --- | --- | --- |
| Pedido recebido | Transação autenticada que registra o pedido do titular | Pedido mais recente ainda aberto, iniciado pelo titular, endereço atual verificado e aviso recente |
| Encerramento pela plataforma | Decisão administrativa ou assunção de pedido anterior, preservando prazo | Iniciativa da plataforma e estado aberto ainda atuais; não afirma que o usuário solicitou a medida |
| Encerramento cancelado | Cancelamento pelo titular ou pela administração | Mesmo pedido cancelado e ainda mais recente; uma confirmação antiga não pode sugerir que um pedido novo foi cancelado |
| Remoção de perfil concluída | Manutenção independente, depois de confirmar perfil removido e eliminação dos arquivos não retidos | Encerramento concluído, titular/aviso vinculados, perfil removido, janela de contato vigente e ausência de supressão por restauração |

O texto final informa o que esta etapa removeu, conserva a distinção dos registros retidos e não confirma quitação financeira. A data de registro do encerramento permite distinguir a confirmação de uma eventual conta nova criada com o mesmo endereço. O canal público de privacidade aparece no corpo; nunca se pede senha/código por e-mail. O botão não executa exclusão/cancelamento: ações sensíveis continuam autenticadas e reautenticadas.

Os avisos são essenciais, independentes da preferência promocional. Cancelar uma exclusão não reativa ofertas, renovação ou marketing nem retira automaticamente uma sanção. O prazo do pedido usa a data persistida e horário de São Paulo/Brasília; pendências impedem afirmar uma conclusão em data garantida.

## Contato e retenção

Ao começar o processamento irreversível, o serviço conserva somente o endereço verificado necessário ao aviso final, cifrado com finalidade/titular/encerramento autenticados. Não cria uma segunda cópia do nome, senha ou dados acadêmicos. A janela é fixada em 90 dias e não é renovada por retentativa. Este é um prazo operacional proposto, **não uma exigência legal universal**; **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

Ao preparar o job final, o contato é retirado da linha de encerramento; a fila conserva destinatário/URL/conteúdo cifrados somente para entrega/retentativa. O conteúdo some ao concluir/descartar o processamento, no expurgo de falha já existente ou ao vencer a janela específica. Remoção do contato e expiração do payload deixam evento mínimo de auditoria, sem endereço. Lease vigente de envio não é eliminado no meio da tentativa pela rotina de expiração.

Endereço não verificado não recebe o aviso. Falta de chave antiga ou falha ao preparar a mensagem conserva a exclusão já concluída e recebe retentativa própria em cinco minutos; não impede preparar avisos de outras contas. Encerrar a conta não depende da disponibilidade do SMTP/Resend. Se a janela se esgotar, a cópia transitória é eliminada, sem fabricar confirmação de entrega ou voltar a capturar o endereço.

Estes campos são dados pessoais cifrados, não anônimos. Sua expiração não significa que um endereço legitimamente conservado em outra evidência financeira/de caso tenha o mesmo prazo. Metadados de encerramento/auditoria continuam sujeitos à etapa de retenção integral ainda pendente.

## Integridade, envio e restauração

- Job/evento único por encerramento/fase, conteúdo congelado e identificação estável nas retentativas.
- Estado/participante/namespace são conferidos antes de entregar ao provedor. A fase `completed` não pode contornar essa conferência usando outro prefixo de evento.
- Nenhum lock de banco é mantido durante a chamada de rede ao provedor. Uma mudança concorrente depois da conferência não pode cancelar um e-mail já entregue ao transporte; o texto informa o registro/consulta do estado atual, sem apresentar a tentativa como transação atômica com o SMTP.
- Reaplicação da supressão de perfil/arquivo após restauração marca os avisos como suprimidos e limpa seus contatos/jobs antigos. Não gera nova comunicação da exclusão a cada reaplicação.
- Backup de job pendente ou falha depois de aceitação pelo provedor e antes do commit local ainda pode produzir repetição. Identificação estável não equivale a garantia universal de entrega exatamente uma vez, especialmente em SMTP. Não se prometeu deduplicação externa ilimitada.
- AES versionado cobre também o contato transitório, preservando prazo, titular e evidências. Nenhuma chave financeira foi trocada.

## Evidências

- `privacy-closure-notice-foundation.log`: dez unitários + 46 integrações aprovados na fundação, com migrações, direitos, identidade e controles de conta.
- `privacy-closure-notice-http-first.log`: 11 unitários + nove integrações novas aprovados, sem skips. Inclui obstrução física real, ausência de chave, expiração, falha de transporte, cancelamento administrativo e replay de supressão.
- `privacy-closure-financial-restore-final.log`: 24 unitários + 138 integrações aprovados antes da última proteção contra cancelamento antigo e dos eventos específicos de expiração. Inclui 61 cenários Mercado Pago com upstream simulado, e-mail, jobs, worker, retenções, rotação e as duas formas de restauração física.
- `privacy-closure-lifecycle-final.log`: 11 unitários + 44 integrações aprovados depois: dez avisos, 16 direitos, cinco rotação e 13 documentos jurídicos. Também verifica data de registro no texto, cancelamento anterior a um novo pedido, eventos de expiração e contato cifrado rotacionado.
- Rodadas se sobrepõem; não somar como testes distintos. Conteúdo/transportes de teste não comprovam entrega real no Gmail.

## Aparência e trabalho restante

As quatro variantes preservam o HTML/tabulações/cores/fontes dos e-mails existentes. `EmailProviderTest` gerou as prévias **exclusivamente sintéticas** em `.impeccable/review/privacy/closure-emails`, usando o mesmo renderer do backend, sem e-mail real ou token de autenticação. O servidor local desse diretório admite somente os quatro HTML e não serve o workspace/configuração privada.

A inspeção visual desktop/celular está **pendente**: tanto o navegador CUA quanto a alternativa documentada de Computer Use falharam antes da inicialização com `failed to write kernel assets: The system cannot find the path specified. (os error 3)`. Não há capturas novas nem declaração de validação visual. A abertura da prévia foi colocada na fila do painel do Codex; isso também não comprova renderização.

Antes de publicar: conectar centro de privacidade e leitor jurídico público, conferir o destino dos botões/rodapé com as URLs finais, testar as variantes visualmente e completar a revisão Impeccable independente no fluxo integral. A URL de privacidade atual do ambiente de desenvolvimento ainda é a ajuda pública de privacidade no acesso; não foi chamada de política jurídica final. Permanecem também recuperação externa/holds posteriores a snapshots, retenção financeira/logs/estatísticas, minimização e todo o restante do objetivo de 44 requisitos/16 entregáveis.
