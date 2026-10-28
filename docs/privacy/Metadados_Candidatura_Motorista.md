# Metadados de candidatura de motorista — implementação delimitada

26/10/2026 (iniciado em 25/10). Complemento à V40, na fonte; sem alteração da demonstração privada
V26, publicação ou aceite real. Backend validado no escopo abaixo; conferência
visual do complemento pendente por política de acesso do navegador. Não conclui
os 44 itens.

## Diagnóstico e escolha

Retirar os anexos não retirava o motivo livre da análise, a identificação do
revisor, a linha da candidatura ou veículos abandonados. A linha aprovada pode
continuar necessária enquanto sustenta a habilitação atual ou contratos ainda
retidos. O motivo detalhado e o identificador do revisor não precisam acompanhar
indefinidamente essa autorização mínima.

| Dado/finalidade | Regra implementada na fonte | Ação |
| --- | --- | --- |
| Motivo livre e identificador de revisor da candidatura | 90 dias da decisão; sem decisão, da criação | Remover esses campos. Listas/exportação já ocultam o motivo vencido antes do job |
| Candidatura encerrada sem finalidade atual | 365 dias da decisão/criação, anexos já desvinculados e solicitação não pendente | Remover a linha; não fabricar outra aprovação |
| Aprovação que ainda sustenta veículo aprovado de conta não excluída | Enquanto essa finalidade atual persistir | Conservar apenas status, datas e vínculos mínimos após os 90 dias |
| Aprovação ligada a carona/rotina retida ou veículo recém-retirado | Dependência contratual; no mínimo 365 dias desde atualização do veículo retirado | Não apagar a prova mínima enquanto esses registros ainda a exigem; ciclo financeiro/contratual é frente separada e ainda aberta |
| Veículo sem candidatura, carona ou rotina e sem aprovação atual | 365 dias de criação/última atualização | Remover a linha sem quebrar referências |
| Caso específico de candidatura | Recurso, fundamento, revisão e vencimento próprios | Cópia cifrada, sem anexos, no cofre auditado. Não reabre consulta ordinária nem recupera texto já vencido |

90/365 dias são políticas operacionais propostas para resposta, revisão e
minimização, **não prazos universais determinados por lei**. Guardar toda narrativa
até o fim da conta ampliaria a exposição; apagar todo registro em 90 dias
destruiria a prova da aprovação ainda utilizada. A solução separa as finalidades.
As regras seguem necessidade/finalidade e término do tratamento, com hipóteses
de conservação pertinentes nos arts. 6º, 7º, 15 e 16 da
[LGPD](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709compilado.htm).
Base da análise pedida: procedimentos preliminares/contrato. Preservação por caso
exige fundamento específico, não consentimento genérico. Prazos, contexto de
mobilidade e prova de contratação exigem **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**
Não se afirma inexistência de dever setorial.

## Arquitetura e recuperação

`PrivacyDriverApplicationRetention` usa o checkpoint de preservação independente,
trava do titular, nova conferência do recurso e exclusão/redação transacional.
O cursor periódico avança mesmo quando um titular está ocupado; a preparação de
prontidão não ignora dependência não confirmada. Datas vêm dos eventos originais.
Restaurar backup não cria novo prazo ou upload. Para conta já excluída, o fim da
finalidade do veículo usa a preparação original da remoção, quando registrada
nas tarefas/eventos; na sua ausência, a data de exclusão. Uma atualização técnica
de `vehicles.updated_at` não reinicia o prazo. A guarda de restauração aplica
as mesmas regras depois de recuperar preservações e executar as supressões.

O cofre passa a reconhecer `driver_application`. A criação confere titular e
recurso, exige senha administrativa e motivo/caso/datas; a cópia inclui somente
status, vínculos e dados da análise ainda vigentes, sem arquivos ou perfil
inteiro. O motivo já vencido não entra numa nova preservação. O snapshot tipado
conserva essa cópia sem recriar a candidatura na consulta comum. Acesso ao cofre
continua autenticado e auditado; liberação/vencimento encerra sua autorização.
API, worker e ferramentas operacionais devem ser publicados juntos, porque
versões anteriores recusam a categoria nova em vez de ignorá-la.

Uma preservação que ainda dependa do registro ordinário por ausência da cópia
privada impede sua limpeza destrutiva. Os dados da candidatura preservados por
caso entram na exportação própria com lista de campos permitidos; o identificador
do revisor não é incluído nessa entrega automática. Pedido complementar continua
disponível para tratamento apropriado de informação de terceiros.

Não há migration nova neste complemento: as datas/status e as tabelas de cofre
já existem. Privacidade r4 e Motorista r3 continuam minutas `draft`; fontes r3/r2
anteriores foram preservadas em `legal/history/`, sem sobrescrever recibos.

O evento de limpeza guarda contagem/tipo/política, sem copiar novamente ID da
candidatura ou do titular. Auditoria preexistente e contratos não são chamados
de anônimos e seguem o trabalho de retenção que ainda falta.

## Escopo de evidência e pendências

Testes em `unicarona_java_test`, PostgreSQL local55432, identidades sintéticas e
provedores de desenvolvimento. Nenhuma movimentação financeira ou envio real de
e-mail faz parte desta validação. As rodadas se sobrepõem e não devem ser somadas:

- Preflight de 25/10: seletor de classe inexistente recusado pelo launcher;
  Maven não iniciou. O nome foi corrigido; proteção do seletor mantida.
- `privacy-driver-metadata-targeted-25oct.log`:49 unitários aprovados;40
  integrações executadas com uma falha e um erro de fixtures. Uma criação estava
  exatamente no limite de365 dias; outro cenário tentou uma atualização direta
  que o controle do banco corretamente recusou. Corrigidos o dado e a preparação
  incerta real pelo journal, sem desligar restrições.
- `privacy-driver-metadata-corrected-26oct.log`:49 unitários aprovados;cinco
  classes de integração falharam na inicialização por conexão recusada. Não são
  cinco cenários funcionais executados. O cluster existente estava parado após
  interrupção do computador. Retomado pelo launcher isolado, recuperou WAL e
  respondeu em55432; não houve inicialização ou substituição do banco nem mudança
  no serviço Windows separado de5432.
- `privacy-driver-metadata-final-26oct.log`:49 unitários e103 integrações
  aprovados. Essa rodada inclui14 casos novos, anexos15, jurídico15, holds14,
  recuperação pós-backup11, supressão13, cadastro11 e arquivos10. Antecede o
  reforço da data original da exclusão e seu cenário adicional.
- `privacy-driver-metadata-stable-dates-26oct.log`:fonte final com49 unitários
  e43 integrações aprovados, sem falhas/erros/skips:metadados15, jurídico15 e
  supressão13. Inclui os marcos originais para não renovar prazo por replay.
- `privacy-driver-metadata-js-26oct.log`:46 JS aprovados, incluindo alternância
  de categoria sem copiar indevidamente o ID do titular para o ID da candidatura.
  Detector Impeccable executado uma vez nos dois JS: `[]`, sem certificar UX.

O [registro de evidência](evidence/driver-application-retention-26oct.json)
identifica logs/hashes/fontes. A [revisão de interface](../../.impeccable/review/privacy/driver-application-retention-review.md)
expõe a limitação: Chrome recusou a abertura da prévia4190 com “Host access is
restricted by policy.” Não houve captura nova nem aprovação visual fictícia.
A prévia usa base descartável, sem raiz independente habilitada; os testes
integrais de recuperação são a evidência de banco/arquivo/cofre, não a prévia.

Continuam retenção financeira/contratual e de logs, agregação anônima,
minimização restante, recuperação externa, consistência das seis minutas,
red teams, jornadas/aceites e os dezesseis entregáveis finais. Aprovação manual
e conferência de bytes não comprovam autenticidade de pessoa/documentos.
