# Criptografia de evidências e rotação

Implementação em validação em 21/10/2026. Não publicada na demonstração V26; não representa conclusão da camada integral de privacidade.

## O que mudou

`PrivacyEvidenceCrypto` usa AES-256-GCM com nonce aleatório de 12 bytes e envelope `pvc1.<id-chave>.<conteúdo-base64url>`. A autenticação adicional inclui versão, id da chave, finalidade/campo, titular e recurso. Trocar o conteúdo cifrado entre titulares, linhas, campos ou eventos de e-mail é rejeitado. A cifragem não substitui autorização/escopo de acesso nas APIs.

`PRIVACY_EVIDENCE_KEY_ID` seleciona a chave de novas gravações; `PRIVACY_EVIDENCE_KEYS` contém um objeto JSON de identificador para chave de 64 caracteres hexadecimais. Máximo de oito chaves, sem IDs repetidos, valores duplicados, chave financeira reutilizada ou configuração parcial. Produção exige anel explícito. A derivação local a partir do pepper é restrita a ambientes não produtivos e tem finalidade distinta.

Campos cobertos: detalhes dos protocolos, respostas, evidências retidas, motivos internos de decisões de conta, destinatário da correção de e-mail e os três campos cifrados de correio (destinatário, URL e conteúdo congelado). Cadastro, recuperação de senha e avisos de identidade usam esse anel. A rotação também contempla motivos de holds e IP de recibo caso existam; a implementação de aceites não passou a coletar IP por causa disso. A gestão administrativa de holds continua pendente.

O formato Base64 original continua legível exclusivamente com a chave histórica de `PIN_ENCRYPTION_KEY`. Esse legado não tem o vínculo criptográfico de campo/titular/recurso; a autorização continua obrigatória e a conversão atribui o contexto a partir da relação existente no banco. Novas gravações de privacidade usam o envelope dedicado. Envelope novo desconhecido/adulterado não tenta silenciosamente outra chave. Não foram trocados `PIN_ENCRYPTION_KEY`, `TOKEN_PEPPER`, JWT, Mercado Pago ou credenciais de e-mail.

`AuthTransactions` também deixou de escrever o destinatário em claro no outbox. A identidade do token, seu HMAC, validade, URL de confirmação, mensagem congelada, retentativa e identificação estável da entrega foram preservados. A consulta de atraso do correio usa o vínculo com titular/token sem exigir destinatário em claro. Jobs originais ainda são legíveis; a conversão usa o titular do token para proteger o endereço antigo e acrescentar o contexto do envelope. Se o token já não existir e não houver titular conhecido no payload, a rotação não inventa associação. Esses jobs continuam sujeitos ao descarte/limpeza de conteúdo e precisam ser considerados antes de retirar chaves.

## Conversão em lotes

`PrivacyEvidenceRotation` participa da manutenção, com no máximo uma tentativa por minuto. Cada item tem transação curta própria, seleção limitada e `SKIP LOCKED`; parada e orçamento são respeitados. Cursores por categoria e alternância evitam que um registro ilegível impeça converter os seguintes. Ausência de chave/erro conserva o conteúdo original e gera somente código e contagem em log; não imprime texto, chave, destinatário ou token e não interrompe a manutenção financeira.

O serviço abre o conteúdo com a chave anterior e o contexto original, sela com a chave atual e atualiza somente o campo cifrado. Não renova data, prazo de retenção, identidade do registro, sanção ou prova de aceite. Evidência antiga de troca de e-mail pode conter cifra aninhada: a conversão abre esse campo e guarda o endereço dentro do envelope externo, eliminando uma dependência oculta da chave anterior. Novas retenções já usam essa forma.

V34 permite mudar exclusivamente a representação cifrada dos motivos administrativos e IP de aceite em uma transação marcada para rotação. Os demais campos e os prazos permanecem protegidos. A marca local evita edição acidental; **não é proteção contra administrador do banco**, que também poderia alterar funções/objetos. A aplicação verifica a cifra antes de atualizar; o banco não conhece as chaves nem prova sozinho equivalência de texto.

## Operação e recuperação

1. Criar nova chave aleatória e adicionar ao anel, mantendo todas as anteriores. Nunca reutilizar ID para outro material.
2. Fazer cópia protegida e independente do anel e do controle operacional. Não armazenar a única cópia das chaves dentro do dump cuja recuperação depende delas. Recuperação fora deste computador continua pendente.
3. Alterar apenas o ID atual e publicar coordenadamente o código compatível, configuração e V34. Não usar binário antigo contra a base migrada.
4. Acompanhar conversão e falhas. Conferir também avisos em fila, cofres, holds e cópias antigas recuperáveis. Zero alterações numa rodada não prova que todas as chaves antigas podem ser removidas: itens já cifrados com a chave atual não são novamente autenticados pela seleção de conversão.
5. Ensaiar leitura, exportação e restauração com o conjunto de chaves necessário ao backup escolhido. A barreira de restauração continua obrigatória.
6. Somente retirar uma chave quando nenhum registro, cópia recuperável ou entrega em andamento depender dela. A aplicação não remove automaticamente chaves antigas. O número máximo do anel exige planejamento da frequência e dos prazos reais; não autoriza descartar evidência ainda necessária.

`install-private-evidence-keys-local.ps1` acrescenta chaves aleatórias distintas aos ambientes privados local e sandbox se ambas as variáveis estiverem ausentes; conserva todos os valores anteriores e recusa configuração incompleta. A repetição não gera nova chave nem modifica o anel. O comando não publica o backend nem migra o banco. Os anéis gerados não foram exibidos em chat/relatórios. O resolver/ACL exige a configuração privada em NTFS. Os launchers preservam um anel próprio do sandbox e não mascaram configuração parcial com outra chave do ambiente principal. A mesclagem foi exercitada com valores sintéticos, incluindo preservação financeira, correio compartilhado e fallback apenas quando não existe anel próprio.

## Evidência de testes

- `PrivacyEvidenceCryptoTest`: cinco casos de nonce/contexto, rotação com anel anterior, legado, adulteração, propósito do correio e configuração de produção.
- `Private/logs/privacy-evidence-first.log`: cinco unitários e 52 integrações aprovados: direitos, correções sensíveis, controles de conta, invariantes e migrações. Uma tentativa anterior de compilação falhou por colisão de nome local; foi corrigida antes desta rodada aprovada.
- `Private/logs/privacy-evidence-rotation.log`: cinco unitários e quatro integrações PostgreSQL aprovados, sem skips. As oito categorias foram convertidas; timestamps e metadados imutáveis preservados; falha de chave não sobrescreve conteúdo nem impede lotes pequenos seguintes; a cifra aninhada deixa de depender da chave retirada; a marca de rotação não permite reescrever motivo público ou reduzir retenção.
- `Private/logs/privacy-evidence-financial-restore.log`: 15 unitários e 115 integrações aprovados, sem skips, antes da extensão ao correio original. Inclui 61 cenários Mercado Pago com upstream simulado, autenticação, e-mail, worker/jobs e os dois ensaios físicos de restauração. Não houve envio real ou cobrança nova.
- `Private/logs/privacy-evidence-all-mail-final.log`: 15 unitários e 68 integrações aprovados depois da extensão ao correio original, sem skips. Exercita cadastro, reset, destinatário cifrado, aviso de atraso, retentativa, worker, correção sensível, rotação e conversão de destinatário legado vinculado ao token. A transação conserva o HMAC e a validade original do token; conteúdo congelado ausente em job antigo continua opcional.
- Os números repetem casos; não somar como total de testes distintos. A regressão de transporte, worker, pagamentos e restauração tem registro próprio após sua conclusão.

Ainda necessários: correio essencial/confirmacões finais, retenção financeira/logs/estatística, gestão de holds, minimização, interface/aceites, recuperação externa, publicação e revisões finais. Minutas legais continuam sem publicação.

## Complemento de 25/10 — controle de retenções e arquivos

Os propósitos AES `HOLD_RECOVERY_CONTROL` e `HOLD_RECOVERY_FILE` vinculam as cópias
independentes ao dataset/operação e ao blob, com titular/arquivo autenticados no
conteúdo protegido. A assinatura externa tem domínio próprio e o snapshot retém
hash do registro cifrado. `PrivacyHoldRecovery` coordena a troca com o marcador de
commit; a rotação do cofre usa a trava compartilhada e publica a representação nova.
Troca de cifra/assinatura externa também ocorre quando o estado claro é idêntico.

Os casos isolados de `PrivacyPostBackupHoldsIT` incluem conversão de dois holds,
dois eventos, uma cópia de perfil e o blob, restauração de um arquivo anterior à
troca e reinício após retirar as chaves antigas **daquele conjunto sintético**.
Datas e revisão das decisões são preservadas. Controles substituídos e blobs
órfãos são retirados antes desse reinício. Esse ensaio não autoriza retirar chaves
dos ambientes reais: todos os outros registros, filas, cópias e backups recuperáveis
devem ser considerados. Não houve remoção de chave operacional.

Escopo, rodadas e limitações em `Recuperacao_Retencoes.md`. Recuperação externa,
demais frentes do objetivo e publicação continuam abertas.
