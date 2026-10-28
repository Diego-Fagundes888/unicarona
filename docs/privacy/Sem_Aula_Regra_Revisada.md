# Sem aula posterior à contratação — regra revisada

Revisão de produto e risco contratual de 16/10/2026. **VALIDAÇÃO POR ADVOGADO BRASILEIRO RECOMENDADA.**

A implementação inicial destinava integralmente à conta da plataforma uma parcela mensal marcada como `no_class` após a confirmação do passageiro ou o término da janela de contestação. A retenção automática do preço de um trecho não prestado gera risco de desequilíbrio e cobrança sem correspondência com a prestação. A revisão considera especialmente a informação contratual e o controle de vantagem excessiva e de cláusulas abusivas do [CDC](https://www.planalto.gov.br/ccivil_03/leis/l8078compilado.htm). A análise do caso concreto continua necessária; esta escolha de produto não é apresentada como uma decisão judicial sobre todo modelo de carona.

## Comportamento das novas operações Java

1. A declaração posterior continua identificada por trecho, com aviso ao passageiro e janela de confirmação/contestação.
2. Na confirmação de uma parcela `no_class`, o valor integral é destinado ao crédito do passageiro (`credited`). O driver não recebe essa parcela e a plataforma não registra receita por ela.
3. Quando a janela termina sem contestação, o worker aplica o mesmo destino integral ao passageiro.
4. Uma contestação continua a bloquear o valor para análise. O novo destino automático não libera uma parcela já bloqueada nem decide uma disputa.
5. O crédito conserva a origem contábil e pode seguir o fluxo de solicitação de devolução, conforme o pagamento de origem e a conciliação do provedor. A existência de crédito não elimina o direito de restituição quando cabível.
6. As demais parcelas pagas do mensal permanecem com seus preços e direitos originais.

Feriados/dias excluídos conhecidos no cálculo inicial continuam fora da agenda contratada. Ausência do passageiro com disponibilidade efetivamente cumprida continua sendo uma situação distinta, sujeita à informação prévia e à revisão de proporcionalidade.

## Histórico e compatibilidade

O código conserva uma decisão financeira já efetivada e sua chave de idempotência. Repetir um evento antigo não efetua outra liquidação nem reescreve o ledger. A comparação com o backend TypeScript documenta essa diferença: uma liquidação antiga já retida permanece como registro histórico; uma decisão nova iniciada no Java gera crédito. Uma eventual correção financeira histórica requer operação compensatória e conferência de devoluções, nunca apagamento do recebimento original.

Uma consulta somente de leitura ao banco local de demonstração, em 16/10 às 13:21, encontrou **zero parcelas `no_class` retidas** e ledger equilibrado. A prova está em `.local/privacy-native-runtime-status.json`; ela se refere ao sandbox local, não a uma base de produção externa.

Os relatórios anteriores de migração e verificação conservam evidência do comportamento daquela versão. Esta revisão passa a reger a implementação nova; o runtime de demonstração ainda usa o artefato anterior até a publicação coordenada da camada de privacidade. Consultar `Plano_Implementacao.md` para o resultado dos testes e a situação de implantação.
