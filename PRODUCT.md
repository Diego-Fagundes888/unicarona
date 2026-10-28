# UniCarona

Contexto registrado a partir dos pedidos confirmados do usuário, da auditoria e dos fluxos existentes. Este arquivo descreve o produto; novas instruções do usuário prevalecem sobre ele.

## Product Purpose

Organizar caronas universitárias de ida e volta à UNICEPLAC. Passageiros encontram um trecho avulso ou combinam uma rotina mensal; motoristas oferecem lugares no trajeto que pretendem realizar. O produto é independente da instituição. O usuário pediu qualidade de interface comparável à Uber, preservando a identidade e as regras próprias do UniCarona.

## Users

O aluno é o usuário principal, com papéis de passageiro e motorista. O painel inicia após entrar ou criar a conta. A administração analisa cadastros de motoristas, vínculo acadêmico, contas, ocorrências, parâmetros, calendário, conciliação e tarefas de envio. O MVP existente restringe cadastro a maiores de 18 anos.

## Operating Context

- Criar acesso, informar perfil acadêmico, confirmar e-mail, entrar e recuperar senha.
- Buscar carona por região, sentido, modalidade e data; conferir motorista, encontro e contribuição; solicitar, pagar e acompanhar a reserva.
- No mensal, conferir agenda e valor, obter aceite específico do motorista, contratar e renovar. Avisos de ausência, mudanças de horário, sem aula, cancelamento e contestação possuem regras próprias que o frontend deve preservar.
- Solicitar aprovação como motorista, enviar documentos privados, publicar ofertas avulsas ou rotinas, analisar pedidos e acompanhar passageiros/embarque.
- Consultar crédito/recebimentos, histórico, notificações, perfil, configurações, ajuda e segurança.
- Registrar ocorrência e conservar protocolo; decisões administrativas seguem as regras e os estados financeiros da API.

## Capabilities and Constraints

O cadastro pode usar e-mail comum. Confirmação de e-mail não comprova identidade, idade ou matrícula. O usuário confirmou número de período obrigatório quando o perfil tem período numérico, turno obrigatório e turma opcional predefinida A/B/C, com orientação para selecionar A quando existe apenas uma turma. Os estados sem período definido preservam as exceções da validação existente.

Os dados acadêmicos e a compatibilidade do mensal permanecem sujeitos às regras do backend. PIN indica embarque; ele não substitui a confirmação final ou a destinação financeira. Uma solicitação, um pagamento, uma reserva e um contrato são entidades diferentes. Não inventar disponibilidade, tarifas ou estados de sucesso.

O usuário informou que ainda não escolheu o provedor de pagamentos. A demonstração atual usa dados sintéticos e ambiente de testes, sem movimentação de dinheiro real. Não apresentar o sandbox como integração comercial concluída. Não há promessa de GPS ao vivo, atendimento em tempo real ou vínculo institucional.

## Platform

web

Responsivo: desktop, notebook, tablet e celular. Interface em português do Brasil, valores em reais e datas exibidas no contexto de São Paulo. Entradas nativas de data/hora seguem a localização do navegador. Testes em resoluções simuladas não comprovam funcionamento em aparelhos físicos.

## Accessibility & Inclusion

Manter labels, nomes acessíveis, foco por teclado, ordem lógica, feedback de validação e estados legíveis sem depender apenas de cor. Preservar controles nativos, disclosures e diálogo com foco restaurado. Teclado e resoluções até 320 px são testados; validação com leitor de tela e dispositivos físicos não foi realizada.

## Evidence on Hand

O escopo de implementação foi autorizado pelo objetivo de 05/10/2026. A pesquisa Mobbin pelo navegador e a auditoria das 53 telas estão em `<LOCAL_USER_HOME>`. A especificação do MVP é fonte de contexto e não prevalece sobre pedidos posteriores do usuário. A evidência renderizada e a cobertura final estão em `.impeccable/review/` e `docs/frontend-coverage.json`.

## Política financeira confirmada em 15/10/2026

Quando o pagamento de origem for estornado ou a contestação resultar em perda depois do uso de crédito recebido por cancelamento, registrar o valor já consumido como dívida do passageiro e bloquear novas compras até regularizar. Crédito, dívida e saldo do motorista são registros distintos. A quitação exige confirmação financeira do backend; notificações repetidas não podem duplicar a dívida ou sua baixa. Contestação ainda em análise não é comprovação de perda definitiva.

Para contestação definitiva de carona já realizada, sem crédito de cancelamento envolvido, o usuário escolheu manter o caso em análise para decisão administrativa. Não descontar automaticamente do motorista, criar dívida do passageiro ou assumir a perda pela plataforma. A conciliação de crédito de cancelamento exige valores verificados, motivo administrativo e histórico imutável; concluir uma análise não quita dívida existente.
