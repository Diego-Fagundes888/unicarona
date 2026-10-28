# Acesso entre participantes — telefone, foto e placa

Complemento posterior em 24/10: o checkout passou a mostrar encontro/notas
autorizados e valores por trecho antes da primeira cobrança, com revisão atual
sob trava; ver `Checkout_Conferencia.md`. Os seis campos estruturados extras de
endereço/coordenadas deixaram de ser aceitos/copiados pela API Java e passaram a
ter limpeza de legado e reaplicação antes da prontidão, respeitando casos e
retenções vigentes; ver `Retencao_Localizacao.md`. Os diagnósticos históricos
abaixo descrevem seus checkpoints e não são certificados integrais do produto.

Checkpoint de 21/10/2026. Implementação Java em validação, **não publicada na demonstração V26**. Sem migration nova; fonte permanece V36. Este documento cobre os campos abaixo, não declara concluída toda a minimização ou o objetivo de privacidade.

## Problema confirmado

Uma reserva antiga autorizava telefone e foto por tempo indeterminado. O download de foto conferia vínculo com o dono, mas não se o arquivo solicitado ainda era a foto escolhida no perfil: conhecer o UUID antigo permitia continuar baixando a imagem substituída. A placa completa também aparecia no histórico da reserva e no detalhe da carona para participantes antigos, inclusive após bloqueio pessoal.

Não bastava esconder a imagem na interface. O endereço privado do arquivo precisava aplicar a mesma regra das projeções autenticadas.

## Regra operacional implementada

| Situação | Telefone/foto da outra pessoa | Placa completa ao passageiro |
| --- | --- | --- |
| Reserva `confirmed` ou `boarded`, carona `planned` ou `active`, sem bloqueio bilateral | Disponíveis para organizar/realizar o trecho, somente enquanto não passou o limite abaixo | Disponível |
| Reserva aguardando confirmação financeira, concluída, cancelada ou em análise | Indisponíveis | Protegida |
| Carona concluída, cancelada ou sem aula, mesmo se a reserva ainda não foi conciliada | Indisponíveis | Protegida |
| Registro ainda aberto com saída prevista há 48 horas ou mais | Indisponíveis, sem depender de limpeza pelo worker | Protegida |
| Bloqueio pessoal ativo em qualquer direção | Indisponíveis nos dois sentidos | Protegida |
| Perfil removido | Indisponíveis | Protegida; dados privados seguem as regras de retenção/remoção |
| Foto substituída ou removida do perfil | UUID antigo não concede acesso à contraparte | Não se aplica |

O prazo é estritamente menor que a saída prevista mais 48 horas. Usa data e horário da carona convertidos pelo **fuso da universidade**, não o fuso da JVM ou um `current_date` global. A confirmação financeira não prolonga o acesso. A janela é uma decisão operacional proposta, não um prazo imposto pela LGPD. Reserva futura confirmada continua permitindo contato para combinar a viagem; este checkpoint não introduziu uma janela de abertura anterior à saída.

Foto requer também finalidade `photo`, pertencimento ao dono e o **mesmo ID atualmente escolhido** no perfil. A URL salva não dispensa autorização. Selecionar novamente uma foto anterior a torna atual por ação do titular; não libera as demais imagens. Arquivos mantêm `private, no-store`.

O titular conserva acesso aos seus arquivos privados enquanto existentes. A autorização administrativa já existente permanece distinta; um hold não transforma evidência em perfil público. O motorista continua vendo a placa do próprio veículo. Essas leituras não apagam contratos, parcelas, pagamentos, saldos, direitos ou PINs necessários ao fluxo existente.

## Pontos de aplicação

- `ParticipantAccessSql.OPERATIONAL`: predicado SQL fixo, compartilhado pelos leitores; nenhum fragmento SQL vem do cliente.
- `FileAccessRepository` / `FileService`: autorização do download privado, incluindo ID atual da foto.
- `ReservationReadRepository`: contato, foto no detalhe e placa nas listas/detalhes.
- `RideReadRepository` / `RideService`: separação entre direito de consultar o contrato antigo e necessidade atual de ver a placa completa. Histórico preservado não é licença permanente para consultar contato.

Fora da finalidade operacional, telefone responde `404 CONTACT_UNAVAILABLE`, foto conhecida responde `404 FORBIDDEN` e a placa aparece como `Placa protegida`. O formato dos DTOs foi preservado. Busca de oferta sem vínculo mantém sua apresentação anterior de placa não disponível.

## Acesso a direitos e segurança quando a verificação do e-mail é perdida

A regressão ampliada encontrou uma barreira global que também bloqueava um relato sobre contrato antigo quando uma sessão ainda válida deixava de ter e-mail verificado. O filtro passou a admitir somente direitos próprios de privacidade, ocorrências/bloqueios, leitura de `auth/me` e logout nesse caso. Titular da sessão, relação com a outra pessoa, autorização por papel, contrato da requisição, CSRF, limites e senha adicional continuam sendo exigidos conforme a operação.

Novos cadastros sem confirmação continuam sem login normal; a mudança não emite sessão nem libera novas caronas, arquivos da contraparte ou administração. Conta sem sessão válida deve usar o fluxo de recuperação ou atendimento assistido. O teste novo cobre pedido próprio, revogação, exportação com senha, recusa de outra conta e acesso administrativo, CSRF, bloqueio de busca/arquivo e revogação efetiva da sessão ao sair.

## Evidências de teste

Logs privados em `%LOCALAPPDATA%/UniCarona/Private/logs/`, usando PostgreSQL real descartável e serviços externos simulados:

| Rodada | Resultado e alcance |
| --- | --- |
| `privacy-participant-access-first.log` | Cinco unitários aprovados; 57 integrações, com 11 falhas por composição SQL ainda incorreta. Rodada preservada como diagnóstico |
| `privacy-participant-access-final.log` | Cinco unitários + 58 integrações aprovados depois da correção; arquivos, caronas e contratos. Antes do caso adicional de projeção de carona concluída |
| `privacy-participant-contract-lifecycle-final.log` | Dez unitários aprovados; 144 integrações com duas falhas e um erro: expectativa antiga de placa, teste usando a chave financeira anterior e barreira global de e-mail na ocorrência histórica. O problema de autorização foi corrigido no servidor; expectativas passaram a verificar somente as diferenças intencionais e o envelope dedicado |
| `privacy-participant-rights-auth-final.log` | **Dez unitários + 200 integrações aprovados**, sem falha/erro/skip, após todas as correções. Auth 17, e-mail 15, arquivos 13, caronas 12, mensal 34, ciclo de viagem 30, segurança/ocorrências 26, controles de conta dez, correção de identidade 13, documentos 13 e direitos 17 |

As rodadas se sobrepõem; não somar como casos distintos. Há cinco cenários novos de arquivo/compartilhamento, um cenário adicional de projeção da carona e um de direitos após perda de verificação. Os casos de arquivos usam relacionamentos sintéticos para exercitar o leitor privado; os de mensal/ciclo usam contratos pagos com crédito de teste e verificam o ledger real do banco descartável. Nenhum novo e-mail ou pagamento real foi enviado.

As fronteiras incluem 47h59 de acesso e recusa a partir de 48h no fuso `Pacific/Honolulu`, estados inconsistentes entre carona e reserva, os dois sentidos de bloqueio, troca/remoção/reseleção da foto, dono/admin, acesso por URL antiga, apresentação em lista/detalhe, e preservação da história e do ledger. O teste de evidência verifica também que a chave financeira não abre o novo envelope de privacidade.

## Fundamentação e limites

A aplicação do mínimo necessário e a prevenção de acesso indevido orientam a decisão: [LGPD, art. 6º, III, VII e VIII, e art. 7º, V](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm). O prazo e a adequação ao atendimento de viagens atrasadas precisam de revisão operacional. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

Ocorrências e disputas continuam sendo tratadas pelos canais da plataforma; a contraparte não recebe acesso permanente ao perfil como substituto do atendimento. A mudança impede novas leituras no servidor, sem prometer apagar o que alguém já recebeu legitimamente ou capturou fora do aplicativo.

Ainda precisam de trabalho próprio: nomes/IDs exibidos no histórico, ponto de encontro e endereços, localização/agenda, expurgo de arquivos substituídos de contas ativas, retenção financeira e logs, estatística efetivamente anônima, recuperação de holds posteriores a snapshots e interface integral. A fonte não foi instalada no runtime V26.

## Complemento: nomes, ponto de encontro e rotina atual

Implementação posterior ao checkpoint de contato/placa acima, ainda sem publicação. `ParticipantIdentity` reduz a apresentação ao primeiro nome e inicial final, com tratamento de espaços Unicode, nomes de um termo e limite de tamanho. A marca de conta removida prevalece nas projeções mesmo se uma coluna de nome estiver incoerentemente restaurada. Isso reduz exposição e continua sendo tratamento de dados pessoais; não é estatística anônima.

Aplicado às ofertas de carona/rotina, solicitações em lista/detalhe, reservas/contato, contratos mensais, interesses, lista pessoal de bloqueios e resumo de pagamento. Perfil e ofertas do próprio motorista conservam a identificação completa; consultas administrativas de pessoas/ocorrências continuam sujeitas às autorizações existentes. IDs pertinentes ao próprio contrato seguem privados/autenticados para embarque, cancelamento e ocorrência; não foi criado um diretório público de perfis nem apagada a ligação financeira sob retenção.

| Contexto | Encontro exato e observações | Identificação / demais campos |
| --- | --- | --- |
| Consulta inicial por pessoa elegível da instituição | Ocultos | Nome reduzido, região, horários e condições de comparação |
| Solicitação aceita, ainda válida, com pagamento aguardando/pendente e trecho previsto | Disponíveis ao solicitante antes de pagar | Nome reduzido; aceite não libera telefone/foto/placa |
| Reserva confirmada/embarcada com finalidade vigente | Detalhes do próprio trecho | Regra operacional de contato/placa já descrita |
| Histórico sem finalidade operacional ou bloqueio | Protegidos na reserva/detalhe da carona | Contrato consultável; detalhe histórico da carona também deixa de expor curso/turma atuais |
| Rotina nova/alterada | Ponto privado não herda autorização de uma rota antiga | Catálogo elegível tem visão reduzida; bloqueio/inatividade não são contornados por contrato antigo |
| Titular do veículo/rotina | Próprios detalhes preservados | Não transforma consulta do titular em exposição à contraparte |

A autorização de viagem paga usa o vínculo efetivo, sem depender de o perfil acadêmico ainda estar preenchido. Em contexto com mais de um direito, uma solicitação aceita adicional não reduz os direitos da reserva já paga.

A escolha de revelar o ponto após aceite, antes do pagamento, combina a necessidade de minimização com a informação pertinente à decisão de contratar. É uma decisão de produto fundamentada, não uma afirmação de que a lei determina essa sequência exata: [CDC, arts. 6º, III, 31 e 46](https://www.planalto.gov.br/ccivil_03/leis/l8078compilado.htm) e [LGPD, art. 6º](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm). **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

Antes da publicação, a interface de checkout deverá mostrar esses detalhes aceitos antes da confirmação de pagamento; a API disponível não comprova que a pessoa já os viu na tela antiga. A API atual não permite editar o ponto de encontro de uma carona ou trocar o de uma rotina; a edição de distância copia os campos anteriores. Observações são editáveis, e sua apresentação/prova no checkout ainda exige integração e revisão. Endereços/coordenadas ainda existentes no armazenamento e textos livres em campos de região exigem tratamento próprio; ocultar uma projeção não executa retenção ou elimina inferências pelo contexto.

O complemento V37 implementa supressão independente por arquivo e prepara uploads sem vínculo para remoção após 24 horas do último envio. Isso inclui fotos substituídas que perderam o vínculo, preservando foto atual, documentos associados e casos vigentes. A restauração aplica a instrução individual antes da prontidão, inclusive se o banco antigo aponta a foto removida como atual ou não contém a linha do arquivo. Ver `Arquivos_Sem_Uso.md`. Metadados e documentos de candidaturas antigas, bytes órfãos sem linha/entrada prévia e recuperação de holds posteriores ao snapshot continuam na revisão de retenção. Não se declara concluída a minimização integral ou a recuperação externa.

### Evidências deste complemento

- `privacy-participant-identification-foundation.log`: três unitários + dois HTTP aprovados para a apresentação e o isolamento da rota nova.
- `privacy-name-location-regression-first.log`: oito unitários aprovados; 184 integrações com 12 diferenças esperadas das antigas projeções de nome/encontro e do desvio por contrato histórico. As expectativas de leitura do legado foram ajustadas somente nesses campos, sem adaptar resultados reais nem remover comparações financeiras.
- `privacy-name-location-prepayment-final.log`: oito unitários aprovados; 213 integrações, dez falhas na preparação de perfil que ainda não declarava aceites no catálogo sintético. A fixture passou a criar usuários com aceites explícitos de teste, como as demais suítes; nenhuma regra de publicação/aceite do servidor foi desativada.
- `privacy-name-location-rights-final.log`: **oito unitários + 213 integrações aprovados**, sem falha/erro/skip, após acesso por sessão independente do perfil acadêmico, prioridade da reserva paga sobre nova solicitação e inclusão da preparação jurídica da fixture de perfil. São 19 de pagamento, 13 arquivos, 12 caronas, 38 contratos, 15 rotinas, 30 ciclo de viagem, 16 solicitações, 26 segurança, 14 perfil, 13 documentos e 17 direitos. Rodadas se sobrepõem; não somar como casos distintos.

Os quatro casos HTTP adicionais de contrato verificam nomes literais com sobrenomes múltiplos, titular com nome completo, filas/resumos, busca sem encontro/nota, detalhes pré-pagamento após aceite válido, recusa por expiração/bloqueio, rota nova versus trecho pago e marca de perfil removido apesar de nome cru incoerente. A coordenação paga não depende de perfil acadêmico preenchido; uma proposta adicional não reduz a placa já autorizada. Unicode, nome único, rótulo de remoção e limite sem quebra de caractere suplementar têm cobertura unitária.

`LegacyPrivacyProjection` é usado apenas sobre a expectativa das leituras legadas. Não reduz dados da resposta Java para fazer a comparação passar; os cenários novos verificam saídas literais e não dependem desse adaptador. Histórico, valores, estados, autoria e demais campos continuam comparados.
