# Leitor público e cadastro — verificação delimitada

Checkpoint de fonte em 23/10/2026. Responsável confirmado: Diego Alves Fagundes,
pessoa física, Novo Gama/GO; contato de privacidade
privacy@example.invalid Os quatro valores conferem com a configuração
privada. A prévia usa deliberadamente nome/cidade/e-mail sintéticos, para conferir
que a interface respeita a configuração em vez de depender do contato fixo.

## Correções

1. O HTML de documentos, login e painel recebe apenas o contato público
   configurado, com escape. Leitor, recuperação de erro, contato da própria conta
   e alternativa sem JavaScript passam a usar esse contexto. O nonce permanece
   exclusivo do painel. CSP, autorização e proteção de requisições continuam
   aplicadas. Respostas GET dessas páginas são `no-store`, ignoram validadores
   condicionais e suprimem ETag/Last-Modified, inclusive quando adicionados como
   cabeçalhos de data pelo servidor de arquivos estáticos.
2. Tabelas jurídicas mantêm semântica, cabeçalhos e região focável. Uma orientação
   associada aparece somente quando a tabela realmente exige rolagem horizontal.
3. Uma versão inexistente continua identificada como indisponível. Para um
   documento conhecido, a tela oferece consultar a versão atual; não substitui
   silenciosamente o conteúdo solicitado. A aba identifica o estado de erro.
4. Telefone/WhatsApp passa a ser explicitamente opcional no cadastro, de acordo
   com o contrato que o backend já aceitava. Há instrução para editar depois em
   Perfil; número fornecido continua sujeito à validação. Requisitos acadêmicos,
   idade, senha, veracidade e aceites versionados não foram removidos.

## Evidência

A prévia Java foi executada somente em localhost:4190, com banco descartável
`unicarona_java_test`, sem credenciais de provedores externos. Cadastro sintético
concluído pelo navegador com telefone vazio: banco confirmou `phone IS NULL`,
cinco recibos jurídicos e e-mail ainda não verificado. O valor inválido `123`
foi rejeitado antes da conclusão com campo vazio. Isso comprova criação da conta,
não entrega ou confirmação de e-mail.

No celular emulado, a tabela tinha região de 343px e conteúdo de 470px; o aviso
ficou visível e a tecla para a direita deslocou a região em 40px. A página
permaneceu em 375px sem transbordamento horizontal. Desktop e tablet apresentaram
região/conteúdo de 634px e aviso oculto. A recuperação de versão inexistente
abriu o documento atual somente após clicar no link correspondente. O contato
sintético configurado apareceu no erro e na área própria em desktop e celular.

Índices, imagens e provas estão em
`.impeccable/review/privacy/ui/legal-first` e `ui/legal-final`. As seis capturas
desktop iniciais dos documentos são recortes do primeiro viewport. Seis imagens
suplementares `reader-*-full-desktop.jpg` mostram páginas completas a partir do
topo, solicitadas pela revisão independente. Há 53 arquivos únicos válidos:
34 iniciais e 19 finais. O artefato descartado em `ui/invalid` não é prova.
Metadados repetidos são histórico: considerar a última entrada de cada caminho.

Os 40 contratos JavaScript passaram. A rodada Java inicial teve sete unitários
aprovados e 111 de 112 casos HTTP aprovados: Auth18, PrivacyRights18 e
MercadoPago61 passaram; um dos quinze Legal falhou porque Last-Modified ainda
era servido. Após a correção na filtragem dos cabeçalhos, a repetição delimitada
teve sete unitários e quinze LegalHTTP aprovados, com BUILD SUCCESS. Não somar
rodadas sobrepostas nem afirmar que os 112 casos foram repetidos após a correção.
Os nomes dos logs privados constam em `ui/legal-final/test-proof.json`.

Uma execução do detector registrou dois avisos da fonte Arial herdada e três
orientações sobre tamanhos locais existentes de 17/24/28px. Não foi criada outra
identidade para eliminar avisos mecânicos. O parecer independente e a comparação
documental ficam em `legal-registration-finish-review.md` e
`legal-registration-design-documentation.md`, no diretório de revisão.

## Limites e trabalho restante

O parecer independente completo terminou com disposição **ship**, limitada ao
leitor público, cadastro e suplemento de contato próprio, após abrir as 53
capturas válidas e os seis documentos completos. Não apontou correções materiais
adicionais nesse escopo. A comparação documental final preservou os arquivos do
Design System e conferiu os hashes da fonte. Não há teste
de aparelho físico ou leitor de tela. Novo aceite após alteração material,
leitura da versão antiga após publicar outra, confirmação pelo link de mudança
de e-mail e aparência da mensagem recebida exigem suas próprias evidências.
Esta etapa não certifica essas jornadas nem a entrega real por Gmail/Resend.

A demonstração nativa protegida permanece em V26 com frontend congelado.
Não houve migração, implantação ou publicação comercial. As minutas não são
convertidas em aceites retroativos. O objetivo completo de 44 requisitos e os
dezesseis entregáveis finais continuam abertos na matriz de acompanhamento.

**VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**
