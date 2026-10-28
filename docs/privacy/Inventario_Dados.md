# Inventário de dados e diagnóstico inicial — UniCarona

Complemento V46: [minimização acadêmica nas ofertas](Dados_Academicos_Entre_Participantes.md).
Curso, período e turma continuam coletados no perfil próprio para elegibilidade
e compatibilidade; banco e acessos próprios/administrativos autorizados mantêm
suas finalidades. Projeções de busca/detalhe da contraparte não retornam valores
exatos; sequer os selecionam para o mapper JDBC. Mensal compartilha apenas a
correspondência `compatible`, que pode permitir inferência da própria turma e
não é anônima. Sem novo fornecedor, aquisição ou prazo de retenção; esta frente
reduz divulgação e não substitui expurgo de históricos/IDs/agenda.

Complemento V46: [base física e WAL cifrados](Recuperacao_Transacional_PITR.md).
Não há nova coleta do usuário: são cópias dos dados do cluster para recuperação,
que continuam pessoais mesmo cifradas. Índice conserva UUID técnico de recurso/
dataset, identidade do cluster, criação/vencimento, alvo/LSN/WAL e autenticação;
acesso exclusivo do operador autorizado, sem novo fornecedor. Base, segmentos e
staging pertencem a um recurso finito do catálogo (padrão 35 dias, máximo 90),
com início da base como ponto conservador. Chaves independentes ficam fora da
cópia; controles de supressão/preservação não são restaurados de uma versão
antiga sobre o estado independente atual. Ensaio sintético local concluído em
escopo delimitado; cópia externa e operação contínua ainda pendentes.

Complemento V46: [credencial de embarque](PIN_Embarque_Retencao.md).
PIN cifrado e HMAC deixam de ser prova permanente do contrato pago. Prazo máximo
é o fim da data original no fuso da instituição; uso/cancelamento/encerramento
retiram o código. Prazos e prova de embarque são protegidos de reescrita. Fonte:
confirmação de pagamento e validação do motorista; acesso próprio do passageiro,
com datas disponíveis na exportação, sem novo fornecedor ou coleta. Recuperação
de consumo posterior ao backup ainda depende de recuperação dos fatos da viagem.

Complemento V45: [tentativas de transferência falhas](Transferencias_Falhas_Retencao.md).
Titular, referência do provedor, valor, motivos, diários/lançamentos, recibos,
auditorias classificadas, cache, avisos e entregas encerradas têm ciclo conjunto
após cinco anos dos fatos pertinentes, somente com efeito zero em cada conta.
Origem: criação/retorno da transferência; acesso: próprio titular e administração
conforme papel; processamento interno e protocolo de desenvolvimento já existente,
sem novo fornecedor ou nova coleta. Disputa, hold, acesso em curso, fonte de prova
dependente e contexto desconhecido conservam o conjunto. Não é anonimização;
recebimentos, transferências concluídas e devoluções Mercado Pago não são retirados.

Complemento V44: [cotações e checkouts nunca enviados](Checkouts_Nao_Enviados_Retencao.md).
Pedidos abandonados, cotações JSON com IDs acadêmicos, linhas, pagamentos locais,
comandos falhos nunca iniciados e eventos classificados passam a expurgo após
90 dias da origem pertinente, sem recebimento/reserva/envio ou preservação.
Cache vigente impede retirada; cache vencido e avisos dispensáveis vinculados
são retirados. A origem é a contratação atual; acesso dos participantes e da
administração autorizada, sem novo fornecedor. Marcador e contador já existentes
ganham proteção de integridade, sem nova coleta. Contabilidade recebida, tentativa
incerta e outras finalidades não são apagadas por essa regra nem chamadas anônimas.
Evento financeiro durável ainda não conciliado, entrega ativa/desconhecida e
pedido pendente de acesso/portabilidade/restrição impedem perda prematura. O pedido
de eliminação não é usado por si só para congelar essas cotações. Dados já retirados
não são reconstruídos por essa política.

Complemento V43: [identificação e finalidade financeira](Identificacao_Obrigacoes_Financeiras.md).

| Dado acrescentado/tratado | Coleta, finalidade, armazenamento e acesso | Guarda e destino |
| --- | --- | --- |
| `privacy_retained_records.financial_purpose`: revisão, pendência, data de fato e referências/hash tipados | Derivados de registros já existentes para decidir a guarda da cópia mínima contratual/veículo. Banco e controle independente privado; acesso de manutenção/recuperação autorizado, sem novo fornecedor ou exposição à contraparte | Acompanham a cópia enquanto obrigação real, limiar de defesa ou preservação justificar. Não renovar por polling; falta de fato conhecido exige recuperação. Expirar a cópia quando a finalidade termina e a janela vence |
| Snapshot de retenção formato 3 | Finalidade registrada com evidência cifrada; formatos 1/2 convertidos sem fabricar atividade | Dependências operacionais do catálogo/controle; não restaura dinheiro e não substitui backup financeiro. IDs/hash são pseudonimizados, nunca chamados anônimos |
| `privacy_requests.details_retired_at` e protocolo mínimo dependente | Marcar expiração do relato/respostas e preservar somente o fato de conclusão necessário à cópia contratual; acesso próprio/admin já autorizado. Exportação inclui a data da retirada | Relato/respostas após cinco anos da conclusão, salvo hold específico. Registro mínimo enquanto houver cópia contratual dependente; excluir após checkpoint sem ela. Restauração não concede novo prazo ao texto |

Não altera valores, cobrança, repasse ou coleta de documentos. O ciclo finito
das tabelas financeiras e o plano de recuperação externo continuam pendentes.

Complemento V40 (prova delimitada concluída): [documentos de motorista e metadados](Documentos_Motorista_Retencao.md).

| Dado acrescentado/tratado | Finalidade e acesso | Guarda e destino |
| --- | --- | --- |
| Prazo, retirada e versão da política de anexos em `driver_applications` | Delimitar a análise e a guarda; titular e administração autenticados, coordenadores internos. Sem novo fornecedor de verificação ou compartilhamento público | 30 dias para solicitação pendente, 30 após decisão; retirada dos vínculos. Decisão/aprovação são objetos distintos; seu ciclo de metadados ainda exige fechamento |
| Identificação/habilitação já coletadas | Análise de cadastro e revisão próxima; após prazo, preservação específica somente no fluxo auditado do caso | Cópias não permanecem apenas por constarem em candidatura antiga; finalidade atual/reenvio/caso justificado conferidos antes da eliminação física |
| Metadados de `stored_files` após remoção física | Conferir limpeza e recuperação, sem chamar UUID/hash de anônimo | Elegíveis à retirada após 90 dias da remoção, sem bytes/vínculos/preservação; diário independente e demais registros têm finalidades próprias |
| Último upload em snapshot de preservação | Preservar a data real, sem simular atividade humana na recuperação; acesso só operacional | Formato tipado 2; legado usa criação conservadora. Não concede consulta comum ou novo prazo de candidatura |

Não foram coletados novos campos de documento, número de CNH, CPF ou biometria
por esse complemento. O conteúdo real dos anexos e suas bases legais exigem
análise própria; a etapa não afirma autenticação documental garantida.

Complemento 25/10 de protocolo (prova delimitada): os registros pseudônimos de
supressão passam a distinguir preparo de conclusão assinada. Contas/arquivos
conhecidos conservam UUIDs pertinentes, preparo, prazo mínimo, confirmação,
chave/signatura e índice independente em armazenamento privado; acesso somente
por coordenadores internos e operador autorizado, sem compartilhamento público.
Finalidade: impedir reintrodução por cópias e verificar conclusão antes de retirar
a instrução. O prazo mínimo não basta: exigem-se conferência atual do banco e dos
bytes, horizonte confirmado e ausência de cópias anteriores à conclusão. Dados
que reaparecem reabrem a prova, sem renovar o pedido original. Não são dados
anônimos. Ver [protocolo e limites](Confirmacao_Supressoes.md).

Levantamento de 16/10/2026, anterior à implementação da camada de privacidade. Este arquivo descreve o que foi encontrado, não certifica conformidade ou anuncia funcionalidades ainda não concluídas. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

Responsável informado pelo titular do projeto: **Diego Alves Fagundes**, pessoa física, Novo Gama/GO; contato de privacidade **privacy@example.invalid**. Não há empresa constituída informada. Não foi coletado CPF para esta auditoria. Identificação fiscal/endereço exigíveis para oferta comercial devem ser avaliados antes do lançamento; cidade e e-mail não substituem todas as informações do fornecedor previstas no Decreto 7.962/2013.

Atualização técnica de 21/10: a implementação em validação usa anel AES dedicado e versionado para evidências de privacidade e correio, com campo/titular/recurso autenticados. Novos jobs de cadastro/recuperação não repetem destinatário em claro no outbox; o endereço continua necessário no perfil e no transporte. Legados são legíveis/conversíveis em lotes e a rotação não aumenta retenção. Isso é proteção de dados pessoais, **não anonimização**. Ver `Criptografia_Evidencias.md`; demonstração segue V26, sem publicação desses controles.

Complemento de minimização, ainda sem publicação: telefone, foto atual e placa completa para a contraparte passaram a depender do trecho operacional confirmado/embarcado, da ausência de bloqueio e do limite de 48 horas após a saída prevista no fuso da instituição. Fim da carona ou etapa de confirmação financeira encerra esse acesso; foto antiga substituída não é autorizada por um vínculo genérico com o dono. Contratos e leituras do próprio titular permanecem distintos. Ver [escopo e limites](Minimizacao_Participantes.md); nomes, localização e retenção de arquivos/financeiro ainda exigem continuação.

Complemento em validação de nomes/encontro: projeções para contraparte passaram a usar primeiro nome e inicial do último sobrenome. Cadastro, perfil próprio e leituras administrativas autorizadas conservam identificação pertinente. Busca/detalhe de ofertas sem relação aceita ocultam encontro exato e observações; aceite válido permite conhecê-los antes de pagar. Leitura da reserva paga mantém o encontro do próprio trecho, enquanto histórico/bloqueio encerram o acesso operacional. Consulta de rotina corrente perdeu o desvio por simples contrato antigo, e uma rota nova não herda permissão da rota contratada anterior. São dados pessoais minimizados, sem alegação de anonimato. Integração de interface/checkout, retenção de localização/arquivos e controles independentes de restauração continuam necessários; fonte V36 ainda não publicada.

## Fontes e abrangência

Complemento V38 de controle em desenvolvimento: `privacy_hold_control_state`
contém somente sequência, UUID da operação e confirmação pendente. A fundação
`PrivacyHoldControlJournal` conserva snapshots cifrados/assinados no controle
independente, com vínculo ao dataset e à operação; fonte de testes usa somente
dados sintéticos. Captura tipada dos holds/cofre/arquivos e integração dos comandos
administrativos ainda não foram ligadas. `Recuperacao_Retencoes.md` descreve o
protocolo, o que foi testado e as lacunas. Não interpretar esses componentes como
nova coleta genérica, estatística anônima ou restauração integral já concluída.

Complemento 24/10 ainda sem publicação: a API Java deixou de aceitar os seis
campos estruturados de endereço/coordenadas de rota, que não são consumidos pelo
fluxo atual. Edição de distância não os copia; limpeza por lote e antes da
prontidão trata legados sem finalidade, respeitando retenção de localização e
ocorrência vinculada em tratamento. Região, encontro, distância e contratos não
são removidos por essa rotina. Texto livre pode continuar contendo localização
informada pelo usuário; não há alegação de anonimização ou de retenção integral
resolvida. Ver `Retencao_Localizacao.md`, incluindo a limitação sobre recuperação
independente de holds posteriores ao snapshot.

- Schema PostgreSQL corrente V26, introspectado em modo somente leitura, sem copiar linhas de pessoas: 52 tabelas de aplicação/infraestrutura e a view de direitos mensais. Campos e constraints confrontados com migrations V1–V26.
- Java/Spring é o único backend atual; o backend TypeScript foi retirado. Autenticação, perfis, arquivos, motorista, ofertas, solicitações, reservas, mensal, ocorrências, financeiro, Mercado Pago, jobs e auditoria foram examinados nos controllers, DTOs, services e repositories correspondentes.
- Frontend: `login.js`, `backend-ui.js`, `dashboard.js`, `api.js`, `map.js`, `mercado-pago.js`, páginas HTML e componentes existentes. Cadastro, configurações, consentimentos atuais, mapas, cartão e comunicações confrontados com as rotas de backend.
- Evidência de estrutura detalhada, sem dados pessoais de linhas, em `.local/privacy-database-inventory.json`. Produção pública/hospedagem externa ainda não está configurada; o runtime examinado é local, com PostgreSQL privado e pagamentos de teste.

Atualização operacional de 17/10: o runtime nativo continua no schema V26, mas
configuração, banco, uploads, e-mails locais, evidências e VHDX do WSL foram
movidos para `%LOCALAPPDATA%/UniCarona/Private`, em NTFS com ACL verificada.
As evidências antigas citadas como `.local/arquivo` estão em
`Private/archives/legacy-local/arquivo`. Os relatórios Java antigos foram
arquivados separadamente; novos relatórios e temporários são privados.
O [relatório de armazenamento/backup](Armazenamento_Backup_Local.md) registra
os limites, incluindo expurgo automático de backups/diário ainda pendente,
virtualização indisponível e ausência de implantação pública.

Atualização técnica V33 (ainda sem publicação na demonstração): o inventário independente registra as cópias que podem reintroduzir dados; o diário individual expira somente após eliminação confirmada dessas cópias. Há chaves de assinatura separadas e um marco mínimo de recuperação sem os IDs da conta/exclusão. Não se confunde esse controle técnico com estatística anônima nem com anonimização de transações. Ver [retenção de backups/diário](Retencao_Backups_Diario.md).

## Mapa do diagnóstico V26: dado → coleta → finalidade → armazenamento → acesso → compartilhamento → retenção encontrada

| Categoria e dados | Onde/coleta e finalidade | Onde armazena | Quem acessa / compartilhamento real | Retenção atual encontrada / lacuna |
| --- | --- | --- | --- | --- |
| Nome, e-mail, nascimento, declarações de cadastro | Cadastro; acesso, identificação no serviço e regra de 18 anos | `users`; auditoria genérica de cadastro | Titular e administração; nome também em ofertas/solicitações/reservas. E-mail nos envios de autenticação; produção de pagamento utiliza e-mail do pagador | Enquanto existir a linha; não existe limpeza de perfil encerrado |
| Senha e sessões | Cadastro/login/recuperação; autenticação | Argon2id em `users.password_hash`; hashes de sessão/refresh/CSRF em `sessions` | Servidor; cookies access/refresh HttpOnly, CSRF legível pelo cliente. Não compartilhar senha com contraparte | Expiração/revogação existem; expiração não elimina automaticamente todas as linhas históricas |
| Tokens de autenticação | Confirmação e recuperação | HMAC em `auth_tokens`; link/template AES-GCM temporariamente na outbox | Servidor, destinatário e serviço de e-mail | Confirmação 60 min, recuperação 30 min; consumo/substituição invalidam. Payload de envio concluído é removido; filas esgotadas e metadados ainda precisam de retenção |
| Telefone | Edição de perfil, opcional; contato da combinação | `users.phone` | Titular/admin e contraparte de reserva confirmada, embarcada, aguardando confirmação **ou concluída**, se não houver bloqueio bilateral | Contato de reserva concluída permanece acessível sem limite temporal: corrigir |
| Universidade, curso, período, turno, turma, região | Cadastro/edição; compatibilidade de ofertas e mensal | `student_profiles`, `classes`, `courses`, `regions` | Titular/admin; dados acadêmicos resumidos do motorista aparecem em ofertas e mensal | Sem eliminação automática após encerramento; não há matrícula numérica no cadastro atual |
| Verificação acadêmica | Solicitação e decisão administrativa | Status/fonte em `student_profiles` e motivo em `audit_logs` | Titular/admin | Sem política de expiração/revisão; não afirmar autenticação institucional ou parceria com UNICEPLAC |
| Foto | Upload privado; reconhecimento no encontro | `stored_files`, disco privado, `users.photo_file_id` | Titular/admin; contraparte autorizada por reserva e sem bloqueio | Arquivo antigo não é automaticamente purgado. Acesso de contraparte também pode persistir após viagem concluída |
| Documento de identidade e CNH | Candidatura de motorista; análise manual | Imagens privadas, IDs em `driver_applications` | Titular/admin; não são disponibilizados à contraparte | Sem prazo de exclusão/limpeza física. Upload não comprova autenticidade, validade ou EAR; não há biometria/reconhecimento facial implementado |
| Modelo, cor, placa, capacidade e decisão do veículo | Candidatura de motorista; reconhecer o veículo e controlar capacidade | `vehicles`, `driver_applications`, `driver_profiles` | Titular/admin; placa mascarada na busca, completa para participantes relacionados à carona | Sem prazo após retirada/encerramento; visibilidade histórica deve ser minimizada |
| Região, encontro, origem/destino e endereços opcionais | Publicação/edição de oferta; combinar trajeto | `ride_routes`, `rides`, `routines`; snapshots do mensal | Usuários elegíveis à oferta e participantes/admin; não há GPS ao vivo no código | Sem prazo de limpeza de endereços, encontro, observações e histórico de trajetos |
| Colunas de latitude/longitude de rota | Existem no schema; o mapa examinado é fixo no campus e não usa `navigator.geolocation` | `ride_routes` contém colunas; `universities` contém coordenadas públicas do campus | Não foi encontrado rastreamento de localização ao vivo | Não coletar coordenadas precisas para uma função inexistente; remover/limpar dados desnecessários conforme uso confirmado |
| Mapa externo | Tiles OpenStreetMap carregados pelo navegador quando o mapa é inicializado | Cache do navegador/fornecedor externo, não histórico GPS do usuário no banco | Provedor de tiles recebe requisição de rede e IP do navegador | Transparência e carregamento por ação do usuário precisam ser revistos; não dizer que nenhum terceiro recebe dados |
| Ofertas, datas, horários, pedidos, agenda mensal, aceites, ausência e cancelamento | Fluxos motorista/passageiro; prestação do serviço e resolução de conflitos | `rides`, `routines`, `ride_requests`, `request_lines`, `subscriptions`, `subscription_schedule`, `ride_reservations` | Partes vinculadas/admin; buscas filtradas pela elegibilidade | Cotações nunca enviadas têm ciclo V44; prova dos contratos recebidos, agenda e narrativas remanescentes ainda exigem ciclo completo. Encerramento não pode romper obrigações e parcelas já constituídas |
| PIN de embarque | Reserva confirmada; autorizar registro operacional de embarque | Credencial cifrada e HMAC em `ride_reservations`; prazo original `pin_expires_at` e marcador de retirada `pin_erased_at` | Passageiro só recebe código disponível; motorista valida sem recebê-lo na projeção. Exportação inclui somente datas e estado | V46: retirada ao terminar a finalidade, limite no fim da data original do trecho/fuso, guardas e limpeza antes da prontidão. Prova de embarque/valores permanece; não é quitação ou prova absoluta de transporte. Recuperar evento posterior ao backup continua pendente |
| Avaliações e comentários | Avaliação de reserva | `ratings` | API/partes autorizadas e administração conforme fluxo; não há rede social pública implementada | Comentário pode identificar pessoas; falta política de moderação e supressão após encerramento |
| Denúncias, bloqueios, motivos e decisões | Segurança/contestação; prevenção de abuso e defesa | `occurrences`, `occurrence_parcels`, `user_blocks`, `audit_logs` | Relator/admin; mensagens de decisão às pessoas pertinentes. Relato pode conter dados sensíveis espontâneos | Sem prazo, revisão periódica ou hold delimitado. Não tratar relatos de saúde/violência como marketing ou interesse legítimo genérico |
| Valores, comissões, crédito, saldos, dívida, devoluções, repasses e IDs de transação | Checkout, cancelamento, conciliação e carteira | `payments`, `parcels`, ledger, `transfers`, tabelas Mercado Pago e de dívida | Titular/admin; Mercado Pago recebe dados necessários à operação | Ledger imutável e obrigações separadas devem ser preservados. Falta retenção de dados identificadores e de defesa por categoria |
| Cartão, CPF de pagador e identificador antifraude | Brick oficial no checkout; tokenização e prevenção de fraude | PAN/CVV não são aceitos pelo backend; token, identificação e device ID transitam em comando cifrado, removido quando terminal | SDK/serviço Mercado Pago; backend envia token/identificação necessária e recebe estados | Não afirmar ausência total de dados financeiros/documentais no backend. Rever expiração dos comandos pendentes e payloads de segurança |
| Pix e URLs de pagamento | Cobrança/reconciliação | `mercado_pago_pix_commands.payer_email`, QR/código/URL em orders e dívida | Titular/admin e Mercado Pago | Dados do pagador/QR/URLs não devem persistir indefinidamente após finalidade |
| Webhooks e idempotência | PSP/ações repetidas; integridade financeira | `webhook_events` contém payload JSON; `idempotency_keys` pode conter respostas com dados pessoais | Backend/admin; não são URLs públicas | Hash/idempotência não tornam o conteúdo anônimo. Expiração de chave não garante limpeza de sua resposta |
| Avisos internos e e-mail | Eventos essenciais de conta, segurança, carona e pagamento | `notifications`; SMTP Gmail atual, Resend opcional, outbox | Titular e provedor de e-mail | `notices` é preferência legada; serviço examinado não a usa para suprimir mensagens essenciais. Não há campanha de marketing implementada |
| IP, dispositivo e eventos técnicos | Rede/limitação de tentativas; segurança | Buckets HMAC de rate limit; auditoria sem IP bruto; métricas HTTP agregadas em memória; device ID transitório do Mercado Pago | Servidor/PSP; provedores de rede observam IP | Não existe trilha de acesso por IP que possa ser declarada como cumprimento já implementado do art. 15 MCI. Aplicabilidade ao controlador pessoa física precisa ser distinguida |
| Cookies e armazenamento local | Sessão/CSRF e preferências de interface | `uc_access`, `uc_refresh`, `uc_csrf`; sessionStorage do cooldown, sem token/e-mail; demais preferências devem constar no inventário de UI | Mesma origem; SDK de pagamento pode usar tecnologias próprias no checkout | Não foi encontrado Google Analytics/pixel de marketing. Não instalar banner genérico; informar os necessários e controlar funções opcionais reais |
| Backups, uploads e arquivos de teste | Continuidade/recuperação e ensaios | `.local` e storage privado; restauração de backup testada em sandbox | Operador autorizado do ambiente | Sem rotação/expurgo de backups de produção. Restore deve reaplicar exclusões/tombstones antes de reabrir acesso; não prometer eliminação instantânea de todas as cópias |

## Bases legais propostas por finalidade — sujeitas à validação

- Conta, perfil mínimo, combinação e execução da carona: execução de contrato/procedimentos preliminares, LGPD art. 7º V; informações acadêmicas apenas na medida necessária à elegibilidade/compatibilidade.
- Segurança, rate limits e combate a fraude comum: avaliar legítimo interesse, art. 7º IX, com teste de balanceamento, minimização e revisão. Fraude em dados sensíveis exige hipótese própria do art. 11; não transplantar automaticamente essa base.
- Pagamentos, saldos, reembolsos, dívida e prova do que foi contratado: execução de contrato enquanto abertos; exercício regular de direitos para evidência estritamente necessária. Obrigação legal somente quando identificada a norma e o enquadramento real.
- Segurança física em emergência: proteção da vida/incolumidade quando concretamente aplicável; não basear todos os relatos nessa hipótese.
- Marketing: consentimento separado, livre, revogável e inicialmente desligado. Não usar checkbox obrigatório de privacidade como autorização genérica.
- Aceite de termos é manifestação contratual; ciência da política é transparência. Os dois não convertem todo tratamento em consentimento.

## Riscos jurídicos iniciais

1. O modelo cobra comissão, usa parâmetros de preço e opera mensal recorrente. Chamá-lo de “carona” ou “intermediação” não define sozinho seu enquadramento. Avaliar transporte remunerado, regras do município/DF, trajetos GO–DF, habilitação/seguro e tributação. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**
2. Ausência mensal, “sem aula” posterior e retenção de valor para a plataforma podem gerar desequilíbrio contratual. Não consolidar perda automática sem demonstração de serviço/custo e alternativa de análise/reembolso exigível.
3. Crédito na carteira não equivale a reembolso ao meio de origem; não restringir direito legal a crédito compulsório. Fluxo real deve respeitar a distinção e não prometer saque habilitado quando o provedor não o permite.
4. Falta prova versionada de aceites; o texto genérico no cadastro não permite demonstrar o conteúdo efetivamente aceito. Não criar aceites retroativos para contas antigas.
5. Encerramento, desativação, suspensão e banimento não estão integralmente modelados; uma marca `deleted_at` isolada não limpa arquivos, caches, snapshots ou terceiros.
6. Política de retenção e execução automática ainda ausentes. Dados financeiros/segurança não podem ser guardados para sempre “por garantia”, nem destruídos com disputa aberta.
7. Provedores externos exigem análise de papéis, contratos e transferências internacionais. Gmail pessoal funcionar tecnicamente não comprova adequação contratual para operação comercial.
8. Não há isenção geral defensável por acidentes, conduta das partes ou falha do fornecedor. CDC e responsabilidade da própria plataforma permanecem; foro exclusivo distante, arbitragem obrigatória e renúncia a reembolso não serão adotados.

## Fontes oficiais consultadas

- [LGPD, arts. 6º–11, 15–20, 33 e 46–50](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
- [CDC, especialmente arts. 6º, 14, 25, 27, 46–51](https://www.planalto.gov.br/ccivil_03/leis/l8078compilado.htm).
- [Marco Civil, especialmente arts. 7º, 10–16](https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2014/lei/l12965.htm): a obrigação do art. 15 caput é condicionada à pessoa jurídica e atividade organizada/profissional/econômica; não foi presumida para o responsável pessoa física.
- [Código Civil](https://www.planalto.gov.br/ccivil_03/leis/2002/l10406compilada.htm) e [Decreto do comércio eletrônico](https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2013/decreto/d7962.htm).
- [Lei de Mobilidade Urbana](https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2012/lei/l12587.htm).
- [ANPD: direitos do titular](https://www.gov.br/anpd/pt-br/assuntos/titular-de-dados), [cookies](https://www.gov.br/anpd/pt-br/centrais-de-conteudo/materiais-educativos-e-publicacoes/guia_orientativo_cookies_e_protecao_de_dados_pessoais), [transferência internacional — Resolução 19/2024, com alterações indicadas na página](https://www.gov.br/anpd/pt-br/acesso-a-informacao/institucional/atos-normativos/regulamentacoes_anpd/resolucao-cd-anpd-no-19-de-23-de-agosto-de-2024), [incidentes](https://www.gov.br/anpd/pt-br/canais_atendimento/agente-de-tratamento/comunicado-de-incidente-de-seguranca-cis).
- [STF: parâmetros de responsabilidade por conteúdo de terceiros, decisão de 2025](https://noticias.stf.jus.br/postsnoticias/stf-define-parametros-para-responsabilizacao-de-plataformas-por-conteudos-de-terceiros/). Não presumir que qualquer remoção possa esperar sempre ordem judicial.

## Decisões para implementar, ainda não concluídas

- Centro de privacidade integrado; documentos públicos versionados e aceites separados; contas antigas sem aceite inventado.
- Exportação própria com autenticação adicional e sem URL pública; pedidos de correção/oposição/eliminação/revisão com protocolo e resposta.
- Encerramento com senha novamente, consequências e consulta de obrigações; janela de reversão proposta de sete dias, explicada antes de implementar. Benefício: recuperar pedido acidental/indevido; risco: retardar a limpeza, que deve ser informada e não impedir direitos urgentes sobre dados excessivos. A janela é política de segurança do produto, não prazo imposto pela LGPD.
- Perfil sai da circulação durante encerramento; obrigações financeiras seguem tratamento separado. Pseudonimização de registros vinculáveis não será chamada de anonimização; agregados só serão tratados como anônimos quando não permitirem reidentificação razoável.
- Retenção finita com base, prazo, ação final, holds justificados e revisão; limpeza física com confirmação/retentativa e restauração com supressões reaplicadas.
- Antifraude proporcional para retorno imediato de banidos, com hash com chave, prazo e recurso, sem coleta indiscriminada de documentos/dispositivo.

## Complemento implementado no servidor — V27 a V32

As seções iniciais registram o diagnóstico do sistema V26. Os controles abaixo foram acrescentados no código e exercitados em banco descartável; **a demonstração permanece V26 e a interface nova ainda não foi publicada**. O plano de implementação registra a evidência e as lacunas atuais.

| Dado novo | Coleta e finalidade | Armazenamento e acesso | Compartilhamento e retenção |
| --- | --- | --- | --- |
| Documento, versão, hash, origem e instante do aceite | Cadastro e manifestação autenticada; prova da contratação e transparência, sem aceite retroativo | Catálogo imutável, `legal_acceptances` e avisos deduplicados; titular consulta os próprios recibos | Sem envio adicional a fornecedor; versão pública não contém perfil. Prova por prazo finito de defesa e condições de retenção após exclusão; não há coleta de IP por padrão |
| Preferência promocional e sua manifestação | Centro de privacidade; marketing livre/separado e revogável | Preferência desligada inicialmente e eventos de consentimento; titular/atendimento autorizado | Marketing não interfere em mensagens essenciais; prova de manifestação/revogação tem prazo próprio. Nenhum serviço de campanha foi criado |
| Pedido, resposta e processamento de direito | Protocolo do titular/atendimento; execução do direito e prova da resposta | Texto cifrado, titular derivado da sessão, administração autorizada e acesso auditado | Sem URL pública de exportação; resposta/protocolo possuem retenção finita, observando impedimentos específicos |
| Pedido de exclusão e diário de supressão | Senha novamente, confirmação de conteúdo/prazo e processamento com possibilidade de reversão | Pedido, tarefas, comprovantes de remoção física e diário HMAC independente de restauração | Diário usa identificadores/datas/assinatura, sem documento ou e-mail; não é estatística anônima. Armazenamento privado, rotação e backup/restore físico foram implementados/testados, sem publicação; recuperação externa e implantação final continuam pendentes |
| Desativação voluntária | Ação autenticada com senha; preservar a escolha de não participar de novas combinações | `users.voluntarily_deactivated_at`, sem exposição entre participantes; exportável ao titular | Não enviado a terceiro; vigora até reativação e é removido com o perfil. Revisão de sanção não apaga essa escolha |
| Decisão sobre a conta | Administração reautenticada; motivo suficiente, revisão e exercício regular de direitos | Estado, ação, motivo público, código, justificativa cifrada, autor/instante e prazo; titular vê o motivo público, nota interna só em leitura administrativa auditada | Sem compartilhamento adicional. Retenção inicial proposta de cinco anos, com expiração e retenção específica quando fundamentada. Minimizar notas e não duplicar documentos/relatos sensíveis; hipótese específica do art. 11 quando aplicável |
| Identificador para restrição de recadastro | E-mail já fornecido, normalizado e convertido em HMAC com finalidade separada; impedir retorno imediato do endereço banido | HMAC, código de motivo, vínculo histórico, autor e vencimento escolhido; sem e-mail bruto ou fingerprint de dispositivo nessa tabela | Prazo individual de 1 a no máximo 365 dias configurados, não prazo legal universal; expiração/liberação encerra o uso e há limpeza. É pseudonimização, não anonimização nem garantia de detectar outro endereço |
| Identificação mínima sob retenção | Remoção de perfil com obrigação, saldo, disputa ou retenção específica | Cofre cifrado com motivo/base/prazo e arquivos privados quando retidos | Identificação não permanece no perfil público. UUIDs financeiros continuam relacionáveis sob acesso autorizado; expiração financeira completa e interface de retenções continuam pendentes. APIs administrativas V35 foram implementadas/testadas sem publicação |
| Gestão de retenção específica — V35 | Administrador reautenticado identifica recurso, base, motivo e referência de caso, revisão e vencimento; histórico sem extensão automática | Hold e eventos cifrados, leitura/extração administrativa auditada, base/datas mínimas ao próprio titular; vínculo composto entre evento e titular/hold | Nenhum novo fornecedor. Revisão até 90 dias e vigência até 365 por decisão são política operacional proposta; histórico inicial de cinco anos por decisão, com expurgo após fim da vigência e finalidade de defesa. Evidência `hold_only` perde a conservação quando o caso termina; base financeira autônoma é preservada. Prazos exigem validação profissional |
| Avisos essenciais de encerramento — V36 | Pedido, cancelamento e conclusão do processamento; comunicação do exercício do direito e segurança da conta, sem depender de marketing | Destinatário/URL/texto cifrados na fila; contato transitório cifrado para permitir aviso depois da remoção do perfil; estado/datas/resultado mínimos em auditoria, sem endereço nos logs | Provedor de e-mail já configurado recebe o necessário ao envio; nenhum fornecedor novo. Contato final tem janela operacional de 90 dias, sem extensão por falha; fila limpa ao concluir/expirar e há supressão em replay. Não é estatística anônima nem promessa de entrega exata uma vez. Ver `Avisos_Encerramento.md` |
| Correção sensível — V32 | Novo e-mail e confirmação por link/senha; nascimento corrigido após senha | `privacy_email_changes` guarda destino cifrado e identificadores HMAC, sem token bruto. Nascimento substitui o valor do perfil, sem data completa em logs; menoridade implica restrição de serviço | Envio essencial pelo provedor existente, com destino/link/conteúdo cifrados na outbox. Link dura 30 minutos; destino é eliminado ao concluir/cancelar ou na limpeza de expiração, salvo retenção específica; metadados expiram em 30 dias. Exportação própria inclui o que permanece pertinente. Após exclusão, evidência sob retenção específica vai ao cofre sem impressão de credencial/token |
| Uso e supressão de arquivo individual — V37 | Data do último envio permite concluir o formulário sem conservar indefinidamente uploads abandonados; diário registra instrução de remoção independente da conta | `stored_files.last_uploaded_at`, marcadores já existentes e entrada privada assinada `file-<UUID>` com titular/arquivo/datas/chave/assinatura; sem conteúdo, nome, e-mail ou caminho arbitrário | Sem fornecedor novo. Upload sem vínculo elegível após 24h do último envio, excluídos foto atual, documentos de candidatura e preservação ativa; quota liberada só após remoção física. Diário mínimo de backups +2 dias e barreira de cópias/marco de restauração, sem extensão automática por retentativa/rotação. São dados pseudonimizados, não estatística anônima; ver `Arquivos_Sem_Uso.md` |
| Recuperação independente de preservações — V38, fonte 25/10 | Decisão administrativa, remoção do perfil, expurgo e rotação capturam retenções, histórico, cofre pertinente e arquivos preservados para reaplicar decisões confirmadas posteriores ao backup | Controle cifrado/assinado por dataset/operação, marcador transacional mínimo no banco, blobs cifrados por titular/arquivo e hashes privados; somente coordenadores internos e acesso administrativo reautenticado à evidência, sem URL pública | Nenhum fornecedor novo. Datas originais preservadas; liberação/expiração retira a referência na captura seguinte, controles substituídos são retirados em lotes e blobs sem referência têm tolerância de um dia. Preparações incertas conservam evidência e bloqueiam prontidão. Não é anonimização nem proteção contra rollback privilegiado de todo o host; recuperação externa e publicação continuam pendentes. Ver `Recuperacao_Retencoes.md` |
| Bytes de envio sem metadados — V39 | Inventário privado identifica UUIDs sem linha/vínculo e faz duas observações estáveis separadas por pelo menos 24h; arquivo ocupado, conhecido ou preservado não é removido como órfão | `privacy_orphan_file_observations`: tamanho/hash/impressão digital/datas, sem titular inferido. Diário `orphan-<UUID>` contém dataset, conteúdo esperado, preparo/prazo e ausência confirmada; só coordenadores internos e contagens na ferramenta operacional | Sem fornecedor novo. Observação é retirada quando desnecessária; ausentes antigos ficam elegíveis a limpeza após sete dias. Supressão exige confirmação depois de commit e barreira de cópias antes da retirada; reintrodução invalida a ausência anterior sem renovar as datas originais. São dados pessoais/pseudônimos, não estatística anônima. Ver `Arquivos_Orfaos.md`; prazos propostos e operação real exigem validação |

As bases são delimitadas pela finalidade: execução de contrato para gestão da participação voluntária, transparência/prova contratual para recibos, consentimento para marketing, exercício de direitos para protocolos/evidência e análise documentada de necessidade/proporcionalidade para segurança e recadastro. Não usar consentimento nem legítimo interesse como justificativa genérica para todos os campos. Os prazos propostos e o enquadramento final continuam sujeitos à **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA**.

O objetivo de 44 itens permanece em andamento. Este inventário não substitui os documentos públicos, a implementação, os testes ou o relatório final.

## Complemento de metadados de candidatura — 26/10/2026

Sem nova coleta nem migration neste complemento. O motivo livre e o revisor
em `driver_applications` passam a ter janela de 90 dias da decisão/criação;
listas/exportação ocultam texto vencido e a manutenção remove ambos os campos.
Candidaturas encerradas, com anexos retirados e sem finalidade atual, ficam
elegíveis à remoção após 365 dias. Prova mínima de aprovação ainda usada por
veículo/caronas/rotinas é distinta da narrativa; o ciclo contratual/financeiro
continua sendo frente aberta. Veículos não aprovados, sem referências e sem
atualização por 365 dias ficam elegíveis à remoção.

A gestão de casos recebe a categoria `driver_application`: recurso/titular,
base/motivo/caso e datas são informados pela administração reautenticada;
status/datas/vínculos e motivo ainda vigente são copiados ao cofre cifrado.
Sem anexos, novos documentos de identidade ou fornecedor. Consulta privada é
auditada; revisão/vencimento/liberação seguem a regra já existente de casos.
Texto já vencido não é recuperado por uma nova preservação. A exportação do
titular inclui sua cópia pertinente com campos permitidos, sem identificador
do revisor. Preservação sem cópia impede a retirada destrutiva do registro que
ainda seja necessário ao caso. Ver `Metadados_Candidatura_Motorista.md` para
evidência, limites, testes de backend aprovados e conferência visual ainda pendente.

## Dados operacionais de checkout — V41, 26/10/2026

Complemento de cartão: o e-mail original do pagador passa a integrar o envelope
cifrado de recuperação, junto de cobrança, titular e valor. É cópia temporária
do contato já coletado no perfil; não é novo destinatário ou identificador
financeiro público. Sai com o restante do input quando a criação é conhecida
ou um envio nunca iniciado é encerrado com segurança. A troca de e-mail não
altera a solicitação financeira original. Comando antigo já enviado sem contato
congelado exige resultado verificável, sem reconstrução por suposição.
Ver [recuperação e limites](Comando_Cartao_Contato_Original.md).

Dados já existentes, sem nova coleta: e-mail copiado do perfil para criação Pix,
token/dados do comando de cartão cifrado, QR/imagem Pix e URL de autenticação.
Tabelas: `mercado_pago_pix_commands`, `mercado_pago_card_commands`,
`passenger_debt_payments` e `mercado_pago_orders`. Acesso apenas ao titular para
seu checkout e aos serviços autorizados; o provedor recebe o necessário à criação.
Não foi criado novo destinatário ou fornecedor.

Contato e inputs de criação saem da cópia operacional após resultado conhecido
ou encerramento seguro de envio nunca iniciado. Resposta incerta conserva o
necessário à recuperação idêntica, sem nova cobrança declarada por suposição.
QR/link de autenticação têm somente o prazo/passo utilizável; leitura e coleta
na interface respeitam esse fim. `ticket_url`, sem consumidor na interface,
deixa de ser armazenado/devolvido. Campos não são tratados como comprovante
financeiro nem estatística anônima. Valores, referência, prova de conciliação
e demais históricos continuam separados e exigem expiração própria.

Guarda do banco, manutenção e preparo de prontidão aplicam essa finalidade sem
alterar valor original, vínculo ou monotonicidade das reversões. A exportação
não inclui a cópia de contato já dispensável. Remoção de coluna não é promessa
de sobrescrita imediata de páginas/WAL: o ciclo de backup/armazenamento e a
recuperação independente têm regras próprias. Ver `Dados_Transitorios_Checkout.md`
para testes, versão de minuta, limites de resposta perdida e QA visual pendente.

## Eventos operacionais de segurança — V42, 26/10/2026

Dados existentes: UUID do ator/recurso/evento, ação, data e metadados operacionais
já escritos em audit_logs. Família explícita de e-mail/senha/configurações passa
a seis meses de calendário da criação original. Finalidade: segurança de conta
e investigação recente; validar base e prazo no caso concreto. Sem novo fornecedor
ou log de IP. Cadastro, elegibilidade e prova financeira/jurídica são distintos.

Caso específico gera cópia cifrada privada em privacy_retained_records, com
security_audit/audit_log, titular/recurso e contexto autenticados. Administração
ativa reautentica para preservar/acessar, com justificativa, revisão e vencimento;
não há URL pública. Titular recebe projeção própria no ZIP e pode pedir complemento.
O controle independente recupera a cópia por caso sem repovoar histórico comum.

A exportação deixa de apresentar eventos vencidos antes do expurgo. Worker e
startup conferem checkpoint/cópia e removem somente o elegível; data de restore
não renova finalidade. Cópia ausente/adulterada impede o ato destrutivo pertinente.
Privacidade r6 permanece draft. Ver [regra, fontes e evidência](Logs_Seguranca_Retencao.md).
As demais famílias de auditoria/logs e retenção financeira continuam em análise.
