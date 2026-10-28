# Interface de privacidade — implementação em desenvolvimento

Esta etapa integra os controles já existentes no servidor à interface. A demonstração
protegida continua com V26 e frontend congelado. Os novos arquivos de `dist/` e o
backend em desenvolvimento ainda não foram publicados em conjunto. Este documento não declara o
objetivo completo dos 44 requisitos concluído.

## Percursos implementados no código

- Menu da conta e Configurações → Privacidade e dados. O acesso próprio continua
  disponível quando a conta possui restrição ou perdeu a verificação de e-mail.
- Documentos e meus aceites: cinco documentos de uso e um documento separado do
  motorista; versões explícitas, histórico paginado e controles desmarcados. Uma
  falha ao buscar o catálogo não oculta o histórico que a API consegue fornecer.
- Cadastro: busca o catálogo publicado antes de avançar, apresenta links das
  versões lidas e envia referências congeladas na primeira etapa. Mudança de
  versão pede nova confirmação, inclusive após indisponibilidade de rede.
- Solicitações: criação de protocolo próprio, lista, prazo informado pelo servidor,
  detalhes e respostas. Texto recebido é escapado antes da apresentação.
- Baixar meus dados: senha, POST autenticado, resposta ZIP e download por URL Blob
  local temporária; sem URL pública de exportação. Tipo, tamanho mínimo e assinatura
  ZIP são conferidos. A URL é revogada após 60 segundos ou destruição do fluxo.
- Desativação/reativação: ações distintas, senha e consequências compatíveis com o
  estado atual. Reativação não retoma automaticamente ofertas e renovação.
- Exclusão: consequências → resumo atualizado de operações/valores → senha e
  confirmação → protocolo. Prazo, início do processamento e possibilidade de
  reversão vêm da API. Encerramento da plataforma oferece revisão; cancelamento
  só aparece quando autorizado pelo servidor. O resumo não representa quitação.
- Correção de acesso: solicitação e cancelamento de troca de e-mail e correção de
  nascimento com senha. Abrir o link do novo e-mail apenas consulta a sessão;
  exige senha para confirmar, remove o token do fragmento visível e não o grava
  em armazenamento persistente do navegador.
- Preferência promocional separada das comunicações essenciais; estado inicial
  deriva da preferência efetiva e não de uma caixa previamente marcada.
- Explicação de localização/compartilhamento e lista própria de preservações por
  caso. A lista explica que não inclui todos os registros financeiros retidos.
- Leitor público `legal.html`: navegação dos seis documentos, versão, publicação,
  vigência, identificador do conteúdo e acesso ao arquivo de versões anteriores.
  HTML bruto e links executáveis do conteúdo não são renderizados. O contato
  público confirmado é privacy@example.invalid
- Rodapé da homepage e autenticação apontam para o leitor. O endereço de privacidade
  padrão dos e-mails passa a ser o leitor; uma URL explicitamente configurada
  continua prevalecendo e será conferida antes da publicação coordenada.

## Proteções no cliente

O cliente mantém idempotência nas tentativas após erro de rede/servidor. A chave
interna do mapa usa SHA-256 do corpo em vez de reter a senha no identificador da
operação. Isso não substitui as verificações, limites e evidências do servidor.

Senha incorreta retorna erro sem renovar sessão ou repetir o envio da senha. Apenas
falhas de autenticação/sessão expirada permitem uma renovação compartilhada entre
requisições simultâneas. Erros JSON continuam legíveis ao solicitar exportação Blob.

Respostas de comandos que terminaram após sair da página não iniciam navegação,
download ou avisos nessa nova tela. Falha secundária ao atualizar o perfil após um
comando bem-sucedido não transforma a operação concluída em uma nova tentativa.

## Evidência desta etapa

- `node --test tests/privacy-ui.test.mjs`: **19 testes**, zero falhas ou ignorados.
  Executam o cliente, o renderizador e controladores em contextos JS isolados,
  com dados sintéticos. Dois testes exercitam o carregador real de documentos do
  cadastro; outro verifica a preparação real da confirmação de e-mail.
- `EmailProviderTest`: **7 unitários**. `LegalHttpIT`: **14 integrações**.
  `PrivacyRightsHttpIT`: **17 integrações**. PostgreSQL real descartável, sem
  importar credenciais SMTP/Mercado Pago. Resultado final: `BUILD SUCCESS` em
  `Private/logs/privacy-ui-http-final.log`.
- A primeira rodada encontrou uma asserção excessivamente ampla sobre CSP: o
  teste agora inspeciona `script-src`, mantendo a exigência de ausência de
  `unsafe-inline` para scripts; a política de estilos preexistente não foi alterada.
- As integrações verificam leitor/arquivos públicos sem login, ordem dos scripts,
  tipo das respostas, CSP de scripts, ausência de aceite por leitura, proteção do
  endpoint próprio e ausência de cache no dashboard com nonce.
- Sintaxe dos cinco arquivos JS alterados conferida. Varredura de valores dos
  segredos configurados: **596 arquivos examinados, zero ocorrências**, dentro do
  escopo documentado pela ferramenta; não equivale a auditoria universal.
- Demonstração conferida somente por leitura: V26, ledger equilibrado, prontidão
  HTTP 200; `api.js` servido corresponde à cópia privada congelada e difere do
  desenvolvimento. Nenhuma nova mensagem real ou cobrança foi enviada nesta etapa.

## Administração de privacidade — verificação de 22–23/10

`admin-privacy.js` integra ao shell administrativo a fila de protocolos,
atendimento/respostas, situação identificada da conta, decisões administrativas e
preservações por caso. As decisões mostram o titular retornado pelo servidor;
perfis excluídos não reapresentam nome/e-mail antigos mesmo quando o banco contém
uma restauração incoerente. Respostas e motivos são escapados na apresentação.

Preservações têm fundamento, recurso, justificativa, revisão e vencimento
explícitos. Motivo/histórico e download exigem senha novamente. O cliente limpa a
evidência ao sair, confere versão esperada nas revisões, revoga URLs Blob locais e
não persiste motivos em URL ou armazenamento do navegador. Categorias de perfil e
identificação contratual usam o próprio identificador do titular como recurso;
outras categorias exigem o identificador específico.

- **36 testes JS** passaram: 19 próprios e 17 administrativos. São contextos JS
  isolados, distintos da inspeção renderizada.
- **55 integrações HTTP** passaram: AccountControls 11, Legal 14, Holds 13 e
  Rights 17, no log privado `privacy-admin-ui-http-final.log`.
- O navegador voltou a funcionar. Capturas desktop e celular usam exclusivamente
  contas sintéticas, banco descartável e transportes de desenvolvimento em
  `localhost:4190`. A demonstração V26 permanece separada.
- A segunda rodada identificou erro real ao voltar à lista após criar uma
  preservação. A causa era o envio de `status=` para “Todas”, recusado pelo
  contrato HTTP estrito. As duas filas passaram a omitir o parâmetro nesse caso;
  o contrato não foi enfraquecido. Uma regressão JS cobre filtros, entrada pela
  conta e volta após criação; um novo teste HTTP cobre ambas as filas, filtros
  nomeados e recusa do valor vazio. A rodada final passou com **14 integrações de
  preservações e três unitários de configuração**, no log privado
  `privacy-admin-ui-all-status-final.log`. Não somar essas integrações à rodada
  anterior como se todos fossem casos distintos.
- No navegador, em 23/10, foram confirmados “Todas” nos protocolos, entrada nas
  preservações pela conta, criação de um único caso sintético, volta à lista com
  esse registro, filtro “Ativa” e acesso à sua tela de evidência. As capturas
  finais substituem o erro por resultados reais da prévia descartável.
- O parecer independente `admin-finish-verdict.md` tem disposição **ship**,
  limitada ao achado **AP-01 resolvido**. Não é uma nova auditoria integral de
  segurança, nem aprovação de produção ou dos 44 requisitos. O documenter
  comparou a extensão ao sistema existente em `admin-design-documentation.md`,
  preservando DESIGN.md, sua sidecar e PRODUCT.md.
- O detector estático encontrou zero problemas principais e três alertas
  consultivos de escala tipográfica (17/24/28px). A comparação independente
  identificou o 17px já contextual no sistema e os 24/28px no leitor público,
  fora deste escopo administrativo; não se alterou DESIGN.md para silenciá-los.

Índice atual das capturas: `.impeccable/review/privacy/ui/final/capture-index.json`.
O manifesto inicial é histórico; `admin-fix-proof.json` registra a nova prova das filas.
Pacote de revisão: `.impeccable/review/privacy/admin-review-packet.md`.

## Direitos próprios — verificação de 23/10

A primeira inspeção cobriu as dez rotas próprias, em computador e celular:
centro de privacidade, documentos/aceites, lista de solicitações, protocolo,
exportação, correção de acesso, conta, exclusão, preservações e localização.
Protocolos recebidos e concluídos, troca de e-mail pendente, conta ativa/pausada
e exclusão agendada/cancelada foram observados separadamente. As capturas usam
somente contas sintéticas em PostgreSQL descartável, sem transporte externo.

Foram exercidos no navegador: preferência promocional ligada/desligada com
recarga, protocolo criado e respondido pela administração, leitura da resposta
concluída pelo titular, ZIP próprio baixado, senha incorreta sem perda de sessão,
pedido/cancelamento de troca de e-mail, correção/restauração do nascimento,
desativação/reativação e solicitação/cancelamento da exclusão. Uma correção de
nascimento inicialmente recusada só foi considerada salva depois do intervalo
real de segurança e da conferência por recarga.

O ZIP sintético baixado tinha um `dados.json`, identidade do próprio titular e
nenhuma das chaves proibidas de credenciais/controle inspecionadas. O motivo
administrativo da preservação não constava nele. É uma amostra da jornada, não
uma auditoria completa de todos os domínios exportáveis.

Cinco achados foram corrigidos em um lote:

1. Preservações usam vocabulário próprio; `active` deixou de aparecer como
   “Conta ativa” nessa lista.
2. Compartilhamento explica aceite pelo motorista antes do pagamento e limite
   operacional de 48 horas, sujeito às interrupções anteriores do servidor.
3. Confirmações sensíveis informam a espera restante, com `retryAfterSeconds`
   e `Retry-After`. Permanecem cinco tentativas por 900 segundos para o titular
   e trinta por 900 segundos nas preservações administrativas. Demais limites
   de autenticação não foram alterados.
4. O aviso compartilhado de restrição empilha conteúdo e ações abaixo de 700px,
   evitando palavras quebradas em colunas estreitas.
5. O último cancelamento de exclusão mantém comprovante e protocolo na consulta;
   um novo pedido exige senha e confirmação novamente desmarcada.

A confirmação visual final gerou quinze imagens em `ui/own-final`, incluindo
as cinco correções em desktop/mobile e suplementos móveis. Capturas de rotas
intactas e estados anteriores permanecem em `ui/own-first`. Uma captura de conta
pausada com viewport incorreto foi excluída da evidência e substituída; não usar
arquivos de `ui/invalid`. O índice final registra dimensões, título, URL e data.

Passaram **38 testes JavaScript**, **6 unitários Java** e **60 integrações HTTP**
(Auth 17, AccountControls 11, Holds 14 e Rights 18), sem falha/erro/ignorado.
O novo caso HTTP verifica a persistência das cinco senhas incorretas, recusa da
sexta tentativa válida, espera/cabeçalho, vencimento não prorrogado pela sétima
recusa e liberação depois da expiração de seu próprio bucket descartável. Não
se apagou um contador ativo da prévia para fazer a interface passar. Log:
`Private/logs/privacy-own-five-fixes.log`. Não somar às rodadas sobrepostas.

O detector rodou uma vez após a confirmação: três avisos consultivos 17/24/28px,
com o mesmo contexto já registrado no fechamento administrativo. A comparação
documental própria preservou o DS. A revisão visual independente completa abriu
as 54 capturas obrigatórias das dez rotas e seus estados e deu parecer **ship**
para esse escopo próprio, sem achado material restante. Conferiu os cinco itens,
o código amostrado e a evidência delimitada; não refez a auditoria de segurança,
os testes ou a análise jurídica. Evidências em `.impeccable/review/privacy/`:
`own-review-packet.md`, `own-finish-review.md`, `own-design-documentation.md`, `own-detector.json`,
`ui/own-first/progress.json`, `ui/own-first/export-proof.json`,
`ui/own-final/capture-index.json` e `ui/own-final/test-proof.json`.

## O que a evidência ainda não comprova

O parecer próprio não se estende à marca do shell alterada paralelamente neste
workspace: as capturas posteriores mostram o novo U verde, enquanto as primeiras
mostram a marca anterior. Este lote não editou esses assets nem o DS, e os três
hashes próprios permanecem iguais aos examinados pelo revisor.

Cadastro/novo aceite material, leitor público e confirmação pelo link recebido
no novo e-mail ainda exigem sua própria jornada renderizada. A inspeção própria
não certifica esses percursos. Testes JS/HTTP e leitura do código não substituem
essa etapa, nem demonstram funcionamento físico em todos os aparelhos.

O fechamento administrativo e a conferência própria também não certificam
suficiência jurídica, todos os campos da exportação ou operação pública. A
captura antiga de Configurações serve como referência de identidade, não como
prova da nova tela.

As telas administrativas estão integradas no código, com os limites de revisão
descritos acima. A supressão independente por arquivo foi implementada e testada
na V37; ver `Arquivos_Sem_Uso.md`. Também continuam em aberto as demais
frentes do objetivo: recuperação externa, fronteiras de conclusão de supressões,
volumes/categorias do replay posterior ao snapshot, retenção financeira e
de logs, estatísticas anônimas, minimização final, red teams, matriz integral e
publicação coordenada. Não executar migrations novas sobre a demonstração para
simular uma entrega concluída.

Complemento de fonte de 25/10: V39 acrescenta bytes sem metadados com observação
estável, coordenação de upload, supressão assinada e confirmação de ausência;
`Arquivos_Orfaos.md` contém provas e limites. A minuta de Privacidade passa à
revisão 2, ainda em `draft`. O parecer visual anterior não certifica o conteúdo
novo: recaptura e consistência documental final continuam necessárias. Não houve
alteração de CSS, componentes, DS ou assets neste complemento.
