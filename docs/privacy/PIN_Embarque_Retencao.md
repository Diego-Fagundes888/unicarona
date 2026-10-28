# Credencial de embarque: finalidade e expiração — V46

26/10/2026. Fonte de desenvolvimento, sem publicação. Demonstração protegida V26
e frontend congelado preservados. Esta entrega não conclui a retenção financeira
ou o objetivo integral dos 44 requisitos.

Complemento posterior de 26/10: [ensaio de recuperação transacional](Recuperacao_Transacional_PITR.md)
recupera o próprio embarque/cancelamento posterior à base por WAL nativo, sem
inventar eventos a partir do PIN. A limitação abaixo permanece para dump antigo
isolado e para qualquer operação sem a sequência necessária; continuidade,
cópia externa e limite de perda operacional ainda precisam ser concluídos.

## Diagnóstico

O pagamento confirmado cria um PIN de quatro dígitos por reserva. O banco guardava
o PIN cifrado e seu verificador HMAC em `ride_reservations` permanentemente. A
imutabilidade do contrato também impedia retirar esses dois campos. A leitura
permitia mostrar o código ao passageiro quando a reserva já estava embarcada.

A finalidade da credencial é autorizar o registro operacional de embarque. O
registro de que o embarque foi informado, sua data, o responsável pela ação e os
efeitos financeiros são provas diferentes do segredo utilizado para validar a
ação. O PIN não é quitação nem prova absoluta da realização física do transporte.

## Regra implementada

| Dado/etapa | Comportamento |
| --- | --- |
| Reserva confirmada, trecho previsto/ativo e prazo ainda aberto | Somente o passageiro da reserva recebe o PIN. Motorista informa o código para validação, sem receber o conteúdo cifrado ou o HMAC na projeção de consulta |
| Prazo máximo | `pin_expires_at`: fim da data original do trecho, no fuso da instituição. É calculado no banco na criação e não pode ser movido por atualização da reserva |
| Embarque registrado | Credencial cifrada e HMAC são removidos no mesmo comando que altera o estado. O registro `boarded_at` já existente não pode ser apagado ou reescrito depois |
| Cancelamento, confirmação final, análise ou outro estado que encerra a possibilidade de embarque | Credencial deixa a consulta e segue retirada; encerrar/cancelar a carona invalida também os PINs de suas reservas |
| Reserva abandonada em estado previsto, mas com data/prazo vencidos | Leitura e validação já negam uso; a manutenção remove os bytes sem depender de alguém encerrar o trecho manualmente |
| Conta de uma das partes já excluída | Credencial não volta à consulta ou à autorização; a limpeza retira somente a credencial, preservando obrigações |
| Retirada física | `pin_erased_at` registra a retirada desta cópia. Não é prazo novo de guarda, não altera a criação ou os eventos financeiros e não pode ser editado para reabrir o código |

Nos legados, a migração congela o prazo a partir da data/fuso existentes na cópia.
Não inventa uma data de criação histórica que o sistema anterior não conservava
separadamente. A migração não elimina em massa os PINs existentes; a manutenção
e o preparo de prontidão fazem a seleção. Novos comandos inválidos já são
reduzidos pelo guarda do banco.

O código fica disponível antes da data para o passageiro de uma reserva futura;
isso não permite embarcar antecipadamente. O comando de embarque continua
exigindo a data do trecho e os estados adequados, além de conferir a disponibilidade
atual após os bloqueios. Deslocar data ou fuso não estende o prazo congelado.

## Prova e segurança

Não são retirados pedido, pagamento, reserva, preço, parcela, diário contábil,
comissão, crédito, dívida, disputa, cancelamento ou registro de embarque. Os
guardas monetários permanecem; somente o material de autenticação deixa a antiga
regra de conservação permanente. Atualizar preço, participantes, vínculos ou o
prazo congelado continua proibido. Um código retirado não pode ser recolocado
na mesma linha, nem uma reserva embarcada retornar ao estado confirmado.

O método de consulta seleciona somente os campos utilizados e condiciona o
próprio conteúdo cifrado à identidade do passageiro e à disponibilidade. O HMAC
não integra essa consulta. A operação de embarque consulta o verificador dentro
da transação; registros de domínio e DTOs já possuem representação textual que
omite o PIN. A exportação própria acrescenta as datas de expiração/retirada,
sem incluir PIN, conteúdo cifrado ou verificador.

Uma repetição com a mesma chave idempotente e o mesmo corpo pode receber o
resultado de embarque já confirmado, sem validar novamente o código ou repetir
efeitos. Chave nova em reserva embarcada é recusada. O cache conserva um resumo
de requisição derivado do HMAC, não o PIN nem seu verificador reutilizável, com
o prazo técnico já existente de 30 dias e limpeza própria. O resultado armazenado
contém ID/estado, sem o segredo.

Hold financeiro, valor remanescente ou ocorrência não tornam o PIN uma prova
financeira permanente. Os registros de valores, ação e apuração seguem suas
finalidades e controles existentes. Eventual ordem de preservação de material
específico precisa ser avaliada operacionalmente; esta versão não cria uma nova
categoria de cofre para credenciais de embarque.

## Recuperação e limites

Após replay dos controles de privacidade, o preparo de inicialização remove
credenciais com finalidade já encerrada antes de abrir API e worker. Um dump
anterior à limpeza, mas com prazo já vencido, é novamente reduzido sem renovar
`pin_expires_at`. Uma cópia que já registra retirada conserva o marcador original.
O processamento normal pula titular ocupado; o preparo aguarda a trava.

**Este controle não recupera fatos de viagem perdidos após um backup.** Se a cópia
antecede o próprio embarque/cancelamento e o prazo original ainda está aberto,
ela pode conter uma reserva anteriormente confirmada e seu PIN. Não há nesta
etapa um diário independente de consumo da credencial que comprove esse evento
posterior. Essa recuperação de eventos, junto à recuperação financeira completa,
continua pendente e impede declarar o sistema pronto para publicação. Não se
inventa embarque, quitação ou lançamento contábil para preencher essa ausência.

Os ensaios existentes do helper `RealPostgres` utilizam `pg_dump`/`pg_restore`.
Isso comprova a reaplicação das regras sobre uma cópia, mas não recupera os
eventos de negócio posteriores a ela. A documentação do
[PostgreSQL 18 sobre arquivamento contínuo](https://www.postgresql.org/docs/18/continuous-archiving.html)
distingue dumps lógicos de backup base com WAL e alerta para a necessidade da
sequência completa e do controle de atraso. A próxima frente operacional deve
validar a recuperação transacional e seu limite de perda, em cluster separado
com dados sintéticos, junto aos controles independentes de privacidade. Não
houve alteração de configuração, reinício ou cópia do cluster privado ativo.

O expurgo de pagamentos recebidos, contratos realizados, demais dados acadêmicos,
encontro/notas e histórico contábil continua aberto. Retirar PIN não é anonimizar
o contrato ou eliminar os demais dados pessoais que ainda o vinculam.

## Fundamento e documentação

O encerramento do uso da credencial segue uma decisão de segurança e necessidade
do produto. Não existe aqui uma afirmação de que a lei prescreve um prazo de
“um dia” para PINs. Os princípios de necessidade, segurança e prevenção e as
regras de término/conservação exigem avaliação da finalidade concreta:
[LGPD, arts. 6º, 15, 16 e 46](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm).

Privacidade r10 é minuta; r9 foi preservada integralmente. A decisão sobre prova,
ordem de preservação, procedimento de recuperação e operação real deve ser
validada antes de publicar. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

## Verificação

As rodadas, falhas de preparação e hashes ficam em
[boarding-pin-retention-26oct.json](evidence/boarding-pin-retention-26oct.json).
Os casos usam pessoas/valores sintéticos e o banco `unicarona_java_test`, porta
55432. Não são teste de cobrança real, entrega real de e-mail ou recuperação em
infraestrutura externa. Não houve nova inspeção visual nem contorno do bloqueio
do navegador. As contagens de rodadas sobrepostas não devem ser somadas.


Resultado final desta etapa: **176 unitários e 132 integrações aprovados na rodada dirigida final**, incluindo 15 casos próprios, 16 de migração, 38 de contratos mensais, 30 de ciclo de viagem, 18 de direitos e 15 de documentos legais. A rodada ampla da mesma fonte teve 291 integrações, com um erro na preparação/expectativa antiga do mensal; a rodada dirigida corrigiu e repetiu essa classe. Considerando apenas o resultado mais recente de cada classe nessas duas rodadas, são **176 unitários e 291 integrações distintos aprovados em cobertura complementar**, não uma única execução verde de 291 integrações. Inclui pagamentos, Mercado Pago, arquivos, ofertas, identificação financeira, restore físico, schema e confirmação de supressões. As falhas anteriores de fixture/fuso/seleção ficam registradas na evidência. Não somar contagens de rodadas.
