# CaronaUni UNICEPLAC

## Documento de consolidação do MVP

Versão 1.0 • 22 de setembro de 2026

> Atualização de implementação em 16/10/2026: a destinação integral ao aplicativo em “Sem aula” descrita na proposta inicial foi revista. Nas novas liquidações Java, o valor volta integralmente ao crédito do passageiro, com acesso ao fluxo de devolução. Ver [justificativa, histórico e limites da regra revisada](privacy/Sem_Aula_Regra_Revisada.md). O PDF original permanece como referência da proposta inicial.

Destinatários: equipe do projeto e avaliadores da apresentação acadêmica.

O CaronaUni é uma proposta de plataforma para organizar caronas de ida e volta à UNICEPLAC, com reservas avulsas e mensais, controle de vagas, recursos de segurança e pagamentos intermediados pelo aplicativo. A primeira entrega será um protótipo acadêmico com dados fictícios e operações financeiras simuladas. Existe intenção de comercialização posterior.

Este documento reúne as decisões de produto, os fluxos e as regras de cálculo. O núcleo aprovado é a reserva de um trecho avulso ou de uma rotina mensal, acompanhada de identificação da viagem, confirmação, cancelamento, tratamento de incidentes e destinação do pagamento. Os valores econômicos de referência não representam tarifas comerciais validadas.

As regras mais recentes prevalecem sobre propostas anteriores. O documento separa decisões consolidadas, parâmetros de teste e propostas de implementação que ainda exigem definição. A intenção de atender à comunidade da UNICEPLAC não significa vínculo institucional, aprovação da faculdade ou comprovação automática de matrícula.

## Como usar este documento

As seções 1 a 5 definem o produto e as reservas. As seções 6 a 9 tratam da execução, das exceções e da segurança. As seções 10 a 13 apresentam preços, mensalidades, carteira e viabilidade. As seções 14 a 18 organizam as telas, os dados, a demonstração e as pendências.

- **Consolidado:** regra aceita na discussão e incorporada ao escopo.
- **Parâmetro de teste:** valor usado para tornar o protótipo demonstrável, sujeito a calibração antes da operação real.
- **Proposta de implementação:** detalhe necessário para construir o fluxo, ainda não aprovado como regra definitiva.
- **Pendente:** escolha que a conversa não resolveu; não deve ser tratada como decisão tomada.

O documento especifica comportamento de produto. Não é contrato de transporte, política jurídica final ou comprovação de viabilidade financeira.

## 1 Visão do produto

### 1.1 Problema e proposta

Alunos que fazem trajetos semelhantes precisam encontrar uma carona compatível com seus horários e saber se uma vaga continuará disponível. O motorista publica um deslocamento que pretende fazer, informa as vagas e recebe contribuições dos passageiros. O passageiro busca uma viagem para uma data ou combina uma rotina mensal.

O diferencial discutido é a recorrência entre pessoas da mesma turma. Na realidade acadêmica descrita pela equipe, esses alunos compartilham disciplinas e horários. Isso facilita a volta quando a aula termina antes ou depois do horário habitual. A agenda contratada continua sendo registrada para que o sistema saiba quais trechos e vagas estão comprometidos.

### 1.2 Objetivo do protótipo

Demonstrar que uma pessoa pode oferecer uma carona, outra pode reservar uma vaga compatível, e o sistema consegue acompanhar o trecho até sua confirmação, cancelamento ou contestação. No mensal, o protótipo também demonstra reserva recorrente, cobrança, liberação de saldo e encerramento do vínculo.

Sucesso acadêmico significa apresentar fluxos completos e cálculos consistentes. Sucesso comercial dependerá de testes posteriores de demanda, confiança, disponibilidade de motoristas, aceitação de preços e custos operacionais.

### 1.3 Público e limites

- Pessoas com 18 anos completos ou mais, tanto motoristas quanto passageiros.
- Foco em uma única faculdade, a UNICEPLAC, sem seleção de várias instituições.
- Caronas de ida para a faculdade e de volta, com planos de um sentido ou dos dois.
- Cadastro com e-mail comum, sem exigir e-mail institucional nem validar matrícula.
- Uma pessoa pode oferecer caronas e também viajar como passageira em outros momentos.

O nome CaronaUni UNICEPLAC identifica o recorte do projeto. Não deve ser acompanhado de afirmação de parceria oficial inexistente.

## 2 Escopo da primeira versão

### 2.1 Funcionalidades incluídas

- Cadastro, login, perfil, declaração de maioridade e recuperação de acesso.
- Solicitação de aprovação de motorista e veículo pelo administrador.
- Publicação de viagens avulsas e rotinas mensais com origem, destino, agenda, preço e vagas.
- Busca por região, sentido, data e horário; elegibilidade de turma no mensal.
- Reserva automática ou manual conforme configuração do motorista, respeitando capacidade e bloqueios.
- Minhas caronas, solicitações, reservas mensais e avisos no aplicativo.
- Código de embarque individual por trecho, confirmação final e contestação.
- Cancelamentos, faltas, encerramentos e créditos ou devoluções simulados.
- Compartilhamento de dados da viagem, denúncia, bloqueio e acesso à ajuda.
- Painel administrativo para aprovações, incidentes, contestações e parâmetros do protótipo.
- Cobrança, comissão, carteira, retenção e saque com dinheiro fictício.

### 2.2 Fora do escopo inicial

Não entram GPS ao vivo, acompanhamento automático de rota, chat próprio, inteligência artificial, precificação dinâmica, otimização de trajetos, aplicativo Android ou iOS nativo e expansão para várias faculdades. Também não entram pagamentos reais, verificação documental real, socorro próprio ou monitoramento de emergência.

A opção de viajar com mulheres foi levantada para exploração, mas não foi transformada em requisito fechado. Avaliações por estrelas e programas de assinatura do aplicativo para motoristas também não integram o núcleo aprovado.

### 2.3 Forma de entrega

A direção discutida é uma aplicação web responsiva, utilizável no celular. Java com Spring Boot, uma interface web como React e PostgreSQL foram sugeridos como possibilidades técnicas. A stack, o provedor de autenticação e a implantação ainda não são decisões fechadas. O protótipo pode demonstrar a experiência sem integrações externas reais.

## 3 Pessoas e responsabilidades

| Papel | Ações principais | Limites |
|---|---|---|
| Usuário | Criar perfil, informar turma e região, alternar entre oferecer e buscar | Não recebe selo de aluno verificado apenas por declarar turma |
| Passageiro | Solicitar vaga, contratar plano, conferir veículo, confirmar ou contestar, denunciar | Não excede vagas nem ignora bloqueios |
| Motorista aprovado | Oferecer trajetos, aceitar pedidos, registrar execução ou cancelamento, consultar saldo | Não pode alterar retroativamente o preço de uma reserva |
| Administrador | Analisar cadastro, incidentes e disputas, manter parâmetros | Acessa dados sensíveis apenas quando necessário à tarefa |

O administrador simula a análise na apresentação. Uma operação real precisará definir pessoas responsáveis, horários de atendimento e critérios de revisão. O aplicativo não deve prometer atendimento contínuo sem uma equipe capaz de entregá-lo.

## 4 Cadastro e acesso

### 4.1 Perfil básico

O perfil reúne nome, e-mail comum, data de nascimento, foto, telefone ou WhatsApp, curso, período/turma e bairro ou região. Informações adicionais devem ter finalidade clara. Sala física não define elegibilidade: a comparação usa curso e período/turma.

Atualização solicitada pelo usuário: o número do período é obrigatório quando o aluno informa estar em um período específico; o turno é obrigatório. Turma permanece opcional, com seleção A/B/C e uma dica recolhida: se não houver divisão de turmas ou houver só uma, selecionar A. Situações irregular e sem período definido continuam disponíveis e não permitem contratar mensal. Esses dados são autodeclarados. O edital de ingresso não determina esses valores; os rascunhos ficam somente em memória e nenhuma declaração equivale a validação pela faculdade.

O cadastro bloqueia datas que indiquem idade inferior a 18 anos e solicita declaração de veracidade. Essa declaração não equivale a verificação de idade. No protótipo, todas as identidades e documentos são fictícios. O método de verificar passageiros na operação real está pendente.

Confirmação de e-mail e recuperação de acesso são previstas. Confirmação de telefone pode ser representada no protótipo; seu provedor e implementação real não foram escolhidos. Cada indicador deve descrever exatamente a verificação realizada.

### 4.2 Aprovação de motorista

Criar conta não libera automaticamente a publicação de caronas. O usuário solicita a habilitação como motorista e fornece os dados necessários para identificação, análise de habilitação e vinculação do veículo. O administrador aprova ou recusa a solicitação simulada.

O perfil do veículo inclui modelo, cor, placa e capacidade de passageiros. Mudanças relevantes de motorista ou veículo exigem nova análise. A quantidade anunciada corresponde a vagas de passageiros; o assento do motorista não é uma vaga vendável.

Receber a fotografia de um documento não comprova sua autenticidade. Para comercialização, é necessário definir o processo efetivo de conferência, os critérios e o tratamento dos documentos. Um selo de aprovação não deve ser chamado de garantia de segurança.

### 4.3 Visibilidade dos dados

A busca apresenta informações necessárias para avaliar a carona. Depois da reserva, o passageiro pode conferir foto do motorista, modelo, cor e placa completa. Documentos não são exibidos a outros usuários. Telefone, endereço residencial e rotina acadêmica não compõem um diretório público.

O WhatsApp serve para combinar detalhes após a confirmação da reserva. Bloquear alguém no CaronaUni impede novas combinações no aplicativo, mas não apaga informações já compartilhadas nem bloqueia automaticamente contatos externos.

## 5 Oferta e reserva de caronas

### 5.1 Carona avulsa

Uma oferta avulsa contém motorista e veículo, origem, destino, sentido, data, horário obrigatório, ponto de encontro, vagas e preço por passageiro. Observações podem indicar regiões de passagem. O sistema não promete desvio para qualquer endereço nem compatibilidade automática de rota.

O passageiro busca uma oferta, verifica os detalhes e solicita uma vaga. A aceitação pode ser automática ou manual, conforme configuração do motorista. Não foram aprovados filtros adicionais de preferência para aceitação automática, mas idade, bloqueios, capacidade e condições gerais de acesso continuam obrigatórios.

Na aceitação manual, o pedido aguarda decisão. O prazo de resposta, eventual retenção temporária de vaga e tratamento de pagamento enquanto o pedido está pendente precisam ser definidos. Uma solicitação pendente não deve aparecer como reserva confirmada.

### 5.2 Carona mensal

O mensal reserva vagas recorrentes nos trechos contratados. Motorista e passageiro precisam pertencer à mesma combinação de curso e período/turma. Pessoas de outra turma podem usar o avulso, mas não contratar esse plano mensal.

O plano cobre somente ida, somente volta ou ida e volta. O sistema utiliza a agenda da turma e registra as datas e os sentidos contratados. Pertencer à turma permite contratar; a agenda determina quais vagas serão ocupadas.

A exigência de mesma turma não comprova matrícula. Ela é uma regra aplicada sobre dados declarados. Correções de turma durante um contrato precisam deixar histórico.

Dados ausentes, período irregular ou indefinido não permitem concluir compatibilidade para o mensal. A igualdade do texto da turma não representa validação institucional. O cadastro demonstrativo aceita essas situações sem calcular compatibilidade ou liberar contratação; permanece a regra de mesmo curso e período/turma para o produto futuro.

### 5.3 Capacidade por trecho

Cada trecho mantém sua própria capacidade e suas reservas. Uma ida confirmada não consome automaticamente vaga em uma volta que não foi contratada.

Exemplo: veículo com três vagas de passageiros, sendo duas reservadas por mensalistas na volta de terça-feira, permite no máximo um avulso confirmado nesse trecho. Na quarta-feira, a disponibilidade depende das reservas daquela data.

A confirmação de duas pessoas simultaneamente não pode ultrapassar a capacidade. Uma reserva mensal só pode ser confirmada após verificar todos os trechos incluídos. O tratamento de um conflito em parte das datas ainda precisa ser escolhido; o sistema não deve vender silenciosamente um plano incompleto.

### 5.4 Duração da reserva mensal

A vaga permanece comprometida durante o período contratado, até encerramento aplicável ou perda da reserva por falta de pagamento. O cancelamento comum pelo passageiro impede a próxima renovação e preserva seu direito de uso até o fim do período pago.

Após o vencimento, há três dias de carência e avisos no aplicativo. Encerrada a carência sem pagamento, a vaga pode ser liberada. O instante exato dessa liberação e a possibilidade de viajar durante a carência estão pendentes.

Uma falta isolada não encerra o contrato mensal. A possibilidade de revender apenas a vaga daquele dia quando o mensalista avisa que não irá foi sugerida anteriormente, mas não consolidada. Não deve ser ativada sem regra expressa.

## 6 Horários e realização dos trechos

### 6.1 Saída após a aula

Para mensalistas da mesma turma, a volta pode acompanhar o término real da aula. Uma mudança coletiva desse horário não é atraso do mensalista. Isso não elimina a necessidade de horário e ponto de encontro na ida ou de combinar quando o embarque acontecerá.

Não foi definido um prazo universal de espera individual. Valores como 15 minutos apareceram como sugestões anteriores e não são regra final.

### 6.2 Convivência com avulsos

O avulso tem horário anunciado. Se a turma terminar antes e o motorista quiser sair, ele pode propor novo horário. Passageiros que concordarem mantêm a reserva. Para quem não puder acompanhar, registra-se cancelamento pelo motorista, com restituição ou crédito do trecho.

O aplicativo não obriga o motorista a permanecer no local. Ele registra que o compromisso original deixou de ser cumprido para os passageiros afetados. A viagem não pode ser concluída como realizada para quem ficou para trás. Cancelar uma ida não cancela automaticamente a volta.

### 6.3 Código de embarque

Cada passageiro recebe um código específico para cada trecho. Após conferir o motorista e o veículo e decidir embarcar, informa o código para registrar o início da participação. O código não deve ser reutilizado em outros trechos.

Esse registro confirma o embarque associado à reserva; não comprova que a viagem terminou nem que ocorreu sem incidentes. A confirmação final permanece separada. Divergência de carro ou motorista permite recusa de embarque e registro sem penalidade ao passageiro.

### 6.4 Confirmação e contestação

Quando o passageiro confirma a realização, a parcela correspondente pode ser liberada. Se o motorista declara ter cumprido o trajeto e o passageiro não responde, o sistema avisa o passageiro e abre uma janela de 24 horas para contestação. Sem contestação, a parcela pode ser liberada automaticamente.

Havendo contestação, bloqueia-se a parcela relacionada àquela reserva para análise do administrador. A falta de resposta não é prova física da viagem; é a regra operacional escolhida para evitar que o esquecimento retenha saldo indefinidamente.

Se o mensalista faltou, o motorista pode receber pela disponibilidade cumprida, mesmo sem código de embarque. O sistema registra ausência e disponibilidade em separado; nunca inventa um embarque para justificar o repasse. A declaração de falta sem aviso fica sujeita à mesma contestação.

Definir o marco exato de início das 24 horas, a entrega da notificação e o procedimento diante de falha de aviso é uma pendência de implementação. A janela financeira não deve impedir denúncia de segurança posterior.

## 7 Faltas cancelamentos e encerramentos

| Evento | Reserva e continuidade | Tratamento financeiro |
|---|---|---|
| Mensalista falta; motorista cumpre disponibilidade | Contrato continua | Sem desconto; parcela devida ao motorista |
| Motorista cancela um trecho | Cancela apenas o trecho afetado | Valor integral da parcela volta ao passageiro; sem comissão |
| Motorista remove mensalista | Encerra reservas futuras | Devolução das parcelas futuras já pagas |
| Passageiro cancela mensalidade por motivo comum | Usa até o fim do período; sem renovação | Não devolve disponibilidade futura ainda mantida no período vigente |
| Mudança de turma | Encerra vínculo por perda de elegibilidade | Devolução das parcelas futuras já pagas |
| Relato de segurança | Interrupção imediata e bloqueio de novas viagens | Acerto em análise, sem exigir continuação das viagens |
| Aula cancelada após contratação | Marca ocorrência específica | Parcela retida pelo aplicativo, conforme seção 8 |
| Falta de pagamento após carência | Pode liberar vagas futuras | Não cria saldo sacável sem dinheiro recebido |

### 7.1 Créditos e devoluções

O crédito de cancelamento acompanha o passageiro, inclusive em uma troca de motorista. Pode ser utilizado posteriormente; se não houver nova contratação ou o passageiro deixar o aplicativo, existe a possibilidade de solicitar devolução do saldo disponível.

Somente parcelas não consumidas ou canceladas conforme as regras geram devolução. Valores de viagens realizadas ou disponibilidade efetivamente cumprida não são devolvidos por um cancelamento comum posterior. Um mesmo trecho não pode gerar crédito e devolução duplicados.

Crédito originado em uma mensalidade usa o valor efetivamente atribuído ao trecho com desconto, não o preço avulso cheio. Utilizar um crédito antigo para pagar o mês seguinte não reduz novamente a base econômica das viagens desse novo mês: o crédito é forma de pagamento, e não um segundo desconto.

### 7.2 Três faltas e substituição

Foi escolhido o limite de três faltas no mês, mas ainda é necessário precisar o tipo de falta, quem recebe a contagem e qual consequência é automática ou depende de decisão. Até essa definição, o protótipo pode mostrar um alerta e encaminhamento para análise, identificados como proposta.

O mensalista que perde a carona tem prioridade proposta na lista de espera de outra carona compatível. Não há retirada de reservas de terceiros nem garantia de substituição. A ordenação entre vários passageiros prioritários ainda precisa ser definida.

## 8 Calendário e dias sem aula

### 8.1 Regra consolidada mais recente

A mensalidade é calculada e fechada antes de cada período. Feriados e recessos conhecidos antes da contratação ficam fora dos trechos cobrados. Essa decisão mais recente substitui a proposta anterior de cobrar indiscriminadamente todos os dias do calendário.

Se uma aula é cancelada depois da contratação e isso elimina a necessidade do trecho, a cobrança mensal permanece fixa. O motorista não recebe aquela parcela; o aplicativo retém integralmente seu valor. Essa retenção é separada da comissão normal e precisa ser informada claramente na contratação.

Uma aula final cancelada que apenas antecipa a saída não elimina necessariamente a viagem. Se a volta acontece, ela é tratada como trecho realizado, com o ajuste de horário cabível. O registro é por trecho para não cancelar uma ida já realizada ao marcar ausência de aula à noite.

### 8.2 Registro proposto para o protótipo

O motorista marca “Sem aula” no trecho ou dia afetado, os passageiros recebem aviso e têm prazo proposto de 24 horas para contestar. Nas novas operações Java, na confirmação ou após o prazo sem contestação, a parcela volta integralmente ao crédito do passageiro, sem repasse ao motorista nem receita da plataforma. Uma contestação mantém o valor em análise. A [regra revisada em 16/10/2026](privacy/Sem_Aula_Regra_Revisada.md) preserva decisões financeiras históricas já efetivadas.

Esse fluxo de registro foi sugerido, mas não recebeu aprovação específica tão explícita quanto a regra econômica. Por isso, fica como proposta de implementação. Precisam ser definidos os responsáveis pelo calendário e a forma de resolver relatos divergentes.

### 8.3 Limites da retenção

“Sem aula” não pode substituir “Motorista cancelou”. No segundo caso, o dinheiro pertence ao passageiro conforme as regras de restituição. Toda alteração de motivo deve deixar histórico.

A retenção não gera uma segunda comissão sobre a mesma parcela. Trata-se de receita distinta, cujo tratamento comercial e contábil real precisa de análise antes do lançamento. A viabilidade deve ser demonstrada também sem contar com essa receita, pois sua aceitação pelos alunos ainda não foi validada.

Manter uma cobrança fixa reduz a necessidade de recalcular a fatura, mas não elimina o trabalho de registrar os eventos que determinam quem recebe cada parcela.

## 9 Segurança e privacidade

### 9.1 Aprovação de motorista e veículo

Motoristas precisam de aprovação adicional antes de publicar caronas. Identidade, habilitação e veículo são os objetos da análise prevista. Alterações relevantes exigem nova análise. O protótipo apresenta o processo usando documentos fictícios e indicação clara de simulação.

### 9.2 Conferência e embarque

O passageiro confere foto, modelo, cor e placa completa depois da reserva. O código de embarque vincula sua entrada ao trecho. Incompatibilidade permite recusar o embarque, registrar o problema e interromper o fluxo sem penalidade.

### 9.3 Compartilhamento e encontro

O passageiro pode compartilhar motorista, veículo, origem, destino, horário previsto e identificador da viagem com um contato de confiança. O envio é iniciado pelo próprio passageiro. O recurso não anuncia posição ao vivo nem monitoramento contínuo.

Pontos de encontro conhecidos e movimentados são a direção proposta. A plataforma evita exposição pública de endereço residencial, telefone e rotina acadêmica. Uma pessoa não deve conseguir pesquisar um usuário para acompanhar seus deslocamentos pessoais.

### 9.4 Denúncia e bloqueio

É possível denunciar assédio, ameaça, condução perigosa, pressão para mudar o encontro ou outras ocorrências antes, durante ou depois da viagem. Uma reserva cancelada ou sem embarque não impede o relato.

Bloquear uma conta impede novas combinações entre as duas pessoas, inclusive na aceitação automática. O aplicativo não informa quem a bloqueou. O painel administrativo guarda relato, vínculo com a reserva quando houver, histórico necessário e decisão. Pode haver suspensão preventiva em relatos graves com revisão posterior.

Incidentes graves não aguardam três ocorrências. A regra de faltas operacionais é independente. O administrador precisa proteger o relato e limitar a exposição de dados, sem prometer anonimato absoluto em um contexto em que os envolvidos podem inferir quem denunciou.

### 9.5 Saída imediata por segurança

Um relato de segurança permite interromper imediatamente o vínculo e as novas viagens. A pessoa não precisa seguir viajando, negociar com quem denunciou ou esperar o encerramento financeiro para interromper o contato. O acerto dos trechos futuros é analisado em separado.

As regras de convivência vedam assédio, insistência após recusa, compartilhamento de conta, passageiros extras não combinados e alteração de destino sem concordância. A liberdade de cancelar uma carona não elimina o registro do ocorrido ou suas consequências no aplicativo.

### 9.6 Ajuda e emergência

A tela da viagem oferece contato de confiança, denúncia e acesso aos canais de ajuda. O 190 é o canal de emergência policial; o 180 oferece orientação e atendimento relacionados à violência contra a mulher. As funções precisam ser apresentadas corretamente, conforme a referência oficial listada ao final.

Abrir o discador não equivale a enviar localização, despachar socorro ou acionar uma equipe própria. Na apresentação, os botões demonstrativos não fazem chamadas reais acidentais. O protótipo não tem monitoramento emergencial.

### 9.7 Proteção dos registros

Como diretrizes de implementação, senhas não devem ser armazenadas em texto aberto, acesso administrativo deve ser restrito e eventos de decisão precisam deixar histórico. Documentos de identidade, telefone e relatos não ficam disponíveis em busca pública.

Antes de operar com pessoas reais, será necessário definir verificação de idade para passageiros, guarda e exclusão de documentos, acesso a relatos, prazos de retenção e resposta a incidentes. Esses detalhes não foram fechados na discussão.

## 10 Cálculo do preço avulso

### 10.1 Direção universal

O preço deve partir do trajeto e de parâmetros de custo. O ônibus é uma referência de acessibilidade e comparação, não a fórmula principal. O relato de R$ 6 na ida e R$ 6 na volta entre Lago Azul e a faculdade é histórico; não comprova tarifa atual nem estabelece preço universal.

A fórmula proposta pressupõe contribuição para uma viagem que o motorista já faria. Não foi aprovada remuneração adicional pelo tempo de dirigir. Essa distinção permanece pendente para a futura operação comercial.

### 10.2 Variáveis e fórmula

| Símbolo | Significado | Unidade |
|---|---|---|
| D | Distância do percurso combinado, por sentido | km |
| G | Preço de referência do combustível | R$/litro |
| E | Consumo padronizado | km/litro |
| W | Reserva de manutenção, pneus e desgaste | R$/km |
| T | Pedágios atribuídos ao percurso | R$ |
| N | Quantidade de passageiros de referência | pessoas |
| c | Comissão sobre o preço final | fração entre 0 e 1 |

**Custo estimado C = D × (G ÷ E + W) + T**

**Cota por passageiro Q = C ÷ (1 + N)**

**Preço final avulso P = Q ÷ (1 − c)**

O termo 1 + N inclui o motorista na divisão de custo. Dividir a cota por 1 − c preserva o valor líquido de referência após descontar a comissão. Somar apenas 10% à cota não produz o mesmo resultado quando a comissão incide sobre o total final.

### 10.3 Exemplo ilustrativo

Com D = 20 km, G = R$ 6/litro, E = 10 km/litro, W = R$ 0,20/km e T = zero, o custo estimado é R$ 16. Considerando N = 2 e c = 10%, a cota é R$ 5,3333 e o preço calculado é R$ 5,9259, arredondado para R$ 5,93.

Aplicando comissão arredondada de R$ 0,59 sobre a reserva de R$ 5,93, o repasse fica em R$ 5,34. São números de demonstração; 20 km não foi medido como distância real do trajeto do usuário. Combustível, consumo e desgaste também não foram pesquisados como parâmetros vigentes.

### 10.4 Estabilidade e ocupação

O preço fica fixado antes da reserva e não aumenta para passageiros já confirmados se outra pessoa cancelar. N é uma hipótese de ocupação, não promessa de passageiros. Se houver menos pessoas, o motorista recebe menos contribuições; se houver mais, recebe mais.

O valor de N = 2 foi recomendado como parâmetro inicial, mas precisa ser validado. Capacidade real, trajetos curtos, ocupação alta e risco de contribuição superar o objetivo de divisão de custos exigem avaliação. Não foi definido um teto de arrecadação por veículo.

O cálculo usa o percurso combinado, incluindo desvios aceitos quando existirem. O mecanismo de medição da distância, eventual uso de mapas, arredondamentos comerciais e preço mínimo ainda não estão fechados. O protótipo pode receber a distância manualmente em cenário controlado, como proposta de implementação.

## 11 Mensalidades e proporcionalidade

### 11.1 Regra aprovada para teste

A mensalidade recebe desconto de 10% sobre a soma dos avulsos previstos. Ela é fechada antes do mês e não muda por simples flutuação posterior de parâmetros. Um mês pode ter preço diferente do seguinte, pois a agenda varia. Créditos e devoluções previstos são registrados separadamente.

**Mensalidade M = soma dos preços avulsos dos trechos elegíveis × (1 − d)**

Neste protótipo, d = 10%. Se todos os trechos tiverem o mesmo preço: **M = preço avulso × dias previstos × sentidos contratados × 0,90**. Havendo preços distintos de ida e volta, calcula-se a soma de cada sentido em vez de multiplicar um único preço por dois.

Feriados e recessos conhecidos antes da contratação são excluídos. O desconto mensal oferece contrapartida ao compromisso de pagar pela disponibilidade mesmo quando o aluno falta. O desconto reduz também a base de repasse do motorista e precisa ser validado com ele.

### 11.2 Exemplos com avulso de seis reais

| Dias previstos | Trechos em um sentido | Mensal em um sentido | Mensal ida e volta |
|---|---|---|---|
| 16 | 16 | R$ 86,40 | R$ 172,80 |
| 20 | 20 | R$ 108,00 | R$ 216,00 |
| 22 | 22 | R$ 118,80 | R$ 237,60 |

Esses exemplos pressupõem R$ 6 em cada sentido, desconto de 10% e nenhuma exclusão adicional além da agenda apresentada. Não são tabela comercial de Lago Azul.

Com 40 trechos e mensalidade de R$ 216, cada trecho vale R$ 5,40 no plano. Com comissão de 10%, cada parcela devida gera R$ 0,54 para o aplicativo e R$ 4,86 para o motorista. Se os 40 trechos forem devidos, o total é R$ 21,60 de comissão e R$ 194,40 de repasse.

### 11.3 Entrada no meio do mês

Foi aprovada cobrança proporcional. A proposta de cálculo é somar somente os trechos futuros incluídos a partir do início efetivo e aplicar o mesmo desconto mensal. Exemplo: 18 trechos restantes a R$ 6 geram R$ 97,20, calculados por 18 × 6 × 0,90.

A existência de quantidade mínima de trechos para obter desconto não foi definida. Até decidir isso, a regra proporcional é uma proposta operacional para o protótipo, não uma política comercial completa.

### 11.4 Parcela de cada trecho

Para preços iguais, a mensalidade é dividida pelo número de trechos contratados. Para preços diferentes, a proposta é atribuir a cada trecho seu preço avulso com o desconto contratado. Os centavos devem ser conciliados para que a soma das parcelas seja exatamente igual ao valor da mensalidade.

O denominador e os valores das parcelas ficam registrados na contratação. Um cancelamento posterior não encarece os trechos restantes. Um mês sem trechos elegíveis não deve gerar divisão por zero; a proposta é não emitir mensalidade naquele período.

### 11.5 Integração com a fórmula universal

Se o preço avulso resultante da fórmula for R$ 5,93, e houver 40 trechos iguais, a mensalidade com desconto de 10% será R$ 213,48. Nesse caso, a distribuição de centavos entre parcelas precisa ser determinística. O exemplo de R$ 216 usa avulso de R$ 6, não o valor exato de R$ 5,93.

Para a implementação, usar valores monetários decimais ou centavos inteiros e uma política explícita de arredondamento. A regra de centavos e o nível de cálculo da comissão ainda precisam ser formalizados para evitar divergência entre parcela, saldo e total.

## 12 Pagamento carteira e destinação do dinheiro

### 12.1 Fluxo financeiro

O passageiro paga ao aplicativo. O recebimento fica associado às reservas e, no mensal, às parcelas dos trechos. Valores futuros não são imediatamente sacáveis. À medida que os trechos ficam devidos e confirmados, as parcelas são destinadas ao motorista e à comissão, ou recebem outra destinação conforme o evento.

O motorista pode acumular saldo e escolher saques diários ou mensais. A regra das 24 horas significa que nem toda viagem do dia ficará disponível no mesmo dia. Taxas, valores mínimos, prazos bancários e provedor de pagamento não foram definidos.

No protótipo, recebimentos e saques são simulados. O antigo status manual de pagamento foi substituído pela direção de pagamento intermediado. Uma operação real não pode permitir saque somente porque alguém marcou “Pago” manualmente.

### 12.2 Categorias de saldo

| Categoria | Significado |
|---|---|
| Recebido e ainda não destinado | Valor pago para trechos futuros ou em apuração |
| Bloqueado por contestação | Parcela que aguarda análise |
| Disponível do motorista | Parcela já devida, líquida da comissão, ainda não sacada |
| Crédito do passageiro | Valor restituível ou utilizável em contratação posterior |
| Comissão do aplicativo | Receita sobre parcelas devidas ao motorista |
| Retenção por ausência de aula | Receita separada conforme a regra comercial escolhida |
| Sacado ou devolvido | Valor que já saiu da carteira e não pode ser movimentado novamente |

Essas categorias são uma proposta de organização dos registros. Saldo futuro não deve ser somado ao saldo disponível como se ambos pudessem ser sacados.

### 12.3 Exemplo completo de fechamento

Considere uma mensalidade de R$ 216 para 40 trechos a R$ 5,40, com comissão de 10%. No fim da apuração, 36 trechos são devidos ao motorista, dois foram cancelados pelo motorista e dois não ocorreram por cancelamento posterior de aula. Não há disputa pendente.

| Destino | Cálculo | Valor |
|---|---|---|
| Motorista | 36 × R$ 4,86 | R$ 174,96 |
| Comissão do aplicativo | 36 × R$ 0,54 | R$ 19,44 |
| Retenção por ausência de aula | 2 × R$ 5,40 | R$ 10,80 |
| Crédito ou devolução ao passageiro | 2 × R$ 5,40 | R$ 10,80 |
| Total conciliado | Soma das quatro destinações | R$ 216,00 |

A receita bruta do aplicativo nesse cenário é R$ 30,24, composta por comissão e retenção. O crédito do passageiro não é receita. Custos de processamento, tributos e demais despesas não estão incluídos.

### 12.4 Integridade financeira

Cada evento deve ter identificador e histórico para impedir crédito, comissão ou saque duplicado. Uma confirmação repetida não pode liberar saldo duas vezes. Alterar o preço de uma nova oferta não muda uma mensalidade já contratada.

As situações de dinheiro já sacado seguido de contestação tardia, reversão de pagamento, reembolso real e indisponibilidade do provedor ainda exigem política operacional. No protótipo, usar cenários sem saída real de recursos e sinalizar as limitações.

## 13 Receita e viabilidade

### 13.1 Modelo escolhido para testar

A comissão percentual é o modelo inicial proposto. O parâmetro de 10% incide sobre o valor final pago e devido pelo trecho, já considerando o desconto mensal quando existir. Não se cobra uma segunda assinatura do motorista no escopo inicial. Tarifas de saque não foram aprovadas.

A comissão não é lucro líquido. Custos de pagamento, saques quando houver, verificação, suporte, infraestrutura, tributos e perdas precisam ser descontados. Patrocínios e parcerias apareceram como possibilidades futuras, sem receita garantida.

### 13.2 Cenário mensal ilustrativo

| Mensalistas no cenário | Total pago a R$ 216 por pessoa | Comissão bruta de 10% | Repasse agregado antes dos custos do motorista |
|---|---|---|---|
| 50 | R$ 10.800 | R$ 1.080 | R$ 9.720 |
| 100 | R$ 21.600 | R$ 2.160 | R$ 19.440 |
| 200 | R$ 43.200 | R$ 4.320 | R$ 38.880 |

O cenário pressupõe pagamento integral e todos os trechos devidos. Não inclui retenção por ausência de aula. A estimativa anterior de R$ 2.400 para 100 usuários usava R$ 240 sem desconto mensal; com a regra posterior de 10% de desconto, a comissão comparável é R$ 2.160.

### 13.3 Fórmulas de resultado

**Margem por reserva = comissão da reserva − custos variáveis associados.**

**Resultado mensal = comissões + outras receitas reconhecidas − custos variáveis − custos fixos.**

**Reservas necessárias para equilíbrio = custos fixos ÷ margem média positiva por reserva.**

Não é possível calcular lucro líquido ou ponto de equilíbrio numérico sem custos e volume observados. Caso a margem por reserva seja zero ou negativa, aumentar o número de reservas não resolve o problema por si só.

O preço precisa ser testado com passageiros e motoristas. Alunos com gratuidade estudantil podem valorizar tempo e conveniência em vez de economia de tarifa. Motoristas avaliam a contribuição líquida, desvios e compromisso de agenda. A retenção de dias sem aula deve ser avaliada separadamente pela equipe e pelos usuários.

## 14 Fluxos de uso e telas

### 14.1 Fluxo do passageiro avulso

Cadastro elegível → busca por trajeto e horário → detalhes e regras → solicitação → aceitação automática ou manual → pagamento simulado e reserva confirmada → compartilhamento opcional → conferência do veículo e código de embarque → confirmação final ou contestação → histórico.

A ordem exata entre autorização de pagamento, aceitação e expiração da reserva ainda precisa ser definida. O protótipo deve deixar claro quando a vaga está apenas solicitada e quando está efetivamente confirmada.

### 14.2 Fluxo do mensalista

Perfil e turma → rotina compatível → escolha de ida, volta ou ambos → agenda e preço detalhado → validação de elegibilidade e capacidade → contratação e pagamento simulado → reservas por trecho → execução e apuração → renovação, cancelamento ou encerramento.

O aceite específico do mensal e sua relação com a preferência de aceitação automática do perfil ainda precisam ser explicitados. Não se deve concluir que toda assinatura mensal está automaticamente aprovada só porque o avulso permite isso.

### 14.3 Fluxo do motorista

Cadastro → envio fictício para aprovação → aprovação de motorista e veículo → oferta de trajeto ou rotina → análise de solicitações quando aplicável → lista de passageiros → embarques e execução → informação de cancelamento ou disponibilidade → apuração → saldo disponível → saque simulado.

### 14.4 Conjunto de telas

| Tela ou área | Conteúdo principal |
|---|---|
| Cadastro e login | Identidade declarada, idade, e-mail e acesso |
| Perfil | Foto, contato, curso, turma e região |
| Aprovação de motorista | Identificação, habilitação fictícia, veículo e status |
| Início | Buscar ou oferecer carona |
| Publicar oferta | Percurso, agenda, vagas, preço e aceitação |
| Busca e detalhes | Compatibilidade, regras, horário e disponibilidade |
| Contratação mensal | Trechos, desconto, valor fixado e política de cancelamento |
| Minhas caronas | Ofertas, solicitações e reservas por data |
| Viagem | Veículo, ponto de encontro, PIN, confirmação, ajuda e denúncia |
| Carteira | Futuro, bloqueado, disponível, crédito e movimentações |
| Notificações | Aceites, mudanças, vencimento, carência e contestações |
| Administração | Aprovações, calendário, denúncias, disputas e parâmetros |

## 15 Organização conceitual dos dados

O modelo abaixo organiza o domínio para o futuro desenvolvimento. É uma proposta conceitual, sem impor banco, tabelas físicas ou APIs.

| Registro | Responsabilidade |
|---|---|
| Usuário | Perfil, maioridade declarada, contato e situação da conta |
| Turma e agenda | Identificação acadêmica e datas previstas |
| Motorista e aprovação | Habilitação para oferecer caronas e histórico da análise |
| Veículo | Identificação, capacidade e vínculo ao motorista |
| Rotina | Modelo recorrente de percurso, dias e sentidos |
| Trecho | Ocorrência concreta com data, sentido, capacidade e execução |
| Solicitação e reserva | Pedido, aceite, passageiro e vaga naquele trecho |
| Contrato mensal | Período, turma, trechos, preço e situação do vínculo |
| Parcela | Valor atribuído a um passageiro em um trecho |
| Confirmação e contestação | Embarque, realização, ausência e apuração |
| Movimentação financeira | Recebimento, repasse, comissão, retenção e devolução |
| Denúncia e bloqueio | Incidente, análise e impedimento de novas combinações |
| Notificação | Aviso enviado e evento relacionado |

Os estados devem ser separados por finalidade. Uma reserva pode estar confirmada enquanto o pagamento de uma parcela está bloqueado; um embarque registrado não significa que o trecho já foi liquidado. Propostas de estados incluem solicitação pendente/aceita/recusada/expirada; trecho previsto/em andamento/concluído/cancelado/sem aula; parcela futura/em apuração/bloqueada/liberada/creditada/retida.

As transições definitivas e suas permissões devem ser desenhadas antes da implementação. Separar esses registros evita tratar um único campo “status” como se representasse toda a viagem, o contrato e o dinheiro.

## 16 Regras verificáveis e demonstração

### 16.1 Critérios essenciais

- Uma pessoa de 17 anos não conclui cadastro; uma pessoa com 18 anos completos pode prosseguir.
- Um usuário não aprovado como motorista não publica carona.
- Um passageiro de outra turma pode buscar avulso, mas não contratar mensal com aquele motorista.
- Confirmações simultâneas não ultrapassam a capacidade do trecho.
- O plano mensal reserva somente datas e sentidos contratados.
- Alterar oferta ou custo de referência não muda o preço de reserva já confirmada.
- O código de embarque de uma pessoa não conclui a viagem de outra nem libera automaticamente o pagamento final.
- Uma falta do mensalista não gera desconto se a disponibilidade foi cumprida.
- Uma contestação bloqueia a parcela envolvida e uma repetição do evento não duplica o saldo.
- Cancelamento do motorista devolve a parcela efetivamente paga, sem comissão.
- Feriado conhecido antes da contratação não entra no cálculo; cancelamento posterior de aula segue retenção separada.
- Bloqueio impede novas combinações automáticas e denúncia funciona sem embarque.
- Um incidente de segurança interrompe o vínculo sem esperar o encerramento financeiro.
- A soma dos destinos de cada pagamento corresponde ao recebido, incluindo valores ainda pendentes.
- Nenhum botão demonstrativo movimenta dinheiro real, envia documento real ou faz chamada de emergência inadvertida.

### 16.2 Roteiro sugerido para apresentar

Primeiro, apresentar um motorista fictício aprovado e um passageiro da mesma turma. Mostrar a oferta, a fórmula de preço e a contratação mensal com agenda. Em seguida, demonstrar um avulso usando uma vaga restante e a confirmação por PIN.

Depois, mostrar quatro desfechos independentes: viagem realizada, falta do mensalista com disponibilidade, cancelamento pelo motorista e ausência de aula. Exibir a destinação distinta de cada parcela e o saldo conciliado.

Por último, demonstrar uma denúncia antes do embarque, o bloqueio entre contas e a análise administrativa. Encerrar com saque simulado e com a distinção entre receita bruta e lucro líquido.

### 16.3 Testes com usuários posteriores

A equipe pode testar se há combinações suficientes de turma, trajeto e agenda; se passageiros aceitam o preço; se motoristas aceitam o repasse; se as pessoas entendem a retenção por ausência de aula; e se o fluxo de segurança é compreensível.

Métricas propostas incluem buscas com resultado, solicitações aceitas, trechos realizados, repetição de uso, cancelamentos, contestações e compreensão das regras. Não foram estabelecidas metas numéricas de adoção ou receita.

## 17 Pendências e parâmetros de teste

### 17.1 Parâmetros econômicos

| Item | Situação |
|---|---|
| Comissão de 10% | Referência de teste; depende de custos e aceitação |
| Desconto mensal de 10% | Aprovado para a proposta inicial; validar com motoristas |
| Dois passageiros de referência | Proposta inicial de cálculo, não ocupação garantida |
| Combustível a R$ 6 e consumo de 10 km/l | Valores ilustrativos, não pesquisa vigente |
| Desgaste de R$ 0,20/km | Exemplo a calibrar |
| Distância de 20 km | Exemplo, não medida de Lago Azul à faculdade |
| R$ 6 por trecho em Lago Azul | Referência histórica relatada, não preço comercial fechado |
| Remuneração do tempo de dirigir | Não definida; fórmula atual considera contribuição de custos |
| Taxa e mínimo de saque | Não definidos |

### 17.2 Decisões funcionais ainda necessárias

- Definir consequência e contagem exata das três faltas no mês.
- Definir prazo de aceitação manual, expiração de pedidos e retenção temporária de vagas.
- Definir o fluxo de aceite mensal e a relação com a configuração de aceitação automática.
- Decidir se o motorista pode manter várias rotinas e como impedir conflitos de horário.
- Definir uso da vaga durante a carência e o instante de liberação após três dias.
- Definir se uma ausência isolada permite revender aquela vaga sem encerrar o mensal.
- Definir registro de “Sem aula”, responsável pelo calendário e tratamento de divergências.
- Definir marco da janela de 24 horas, falhas de notificação e apuração tardia.
- Definir política do passageiro avulso que cancela, chega atrasado ou não comparece.
- Definir preço mínimo, medição de distância, arredondamento, ocupação de referência e eventual limite de contribuições.
- Definir mínimo de trechos para desconto proporcional, se houver.
- Definir prioridades em lista de espera e tratamento de indisponibilidade parcial de um plano.

### 17.3 Antes de comercializar

A etapa comercial exige escolher e validar processamento de pagamentos, repasses, reembolsos e saques; definir verificação de idade e identidade; estruturar atendimento e análise de incidentes; e revisar a operação concreta com profissionais responsáveis por seus aspectos jurídicos, contábeis e de proteção de dados.

Nenhuma conformidade, licença, seguro, parceria institucional ou prazo de atendimento foi estabelecido nesta discussão. A demonstração acadêmica não autoriza apresentar esses elementos como já existentes.

Também será necessário validar a aceitação da retenção de parcelas em dias sem aula. Essa regra é decisão de produto, mas sua implementação real e seus efeitos comerciais ainda precisam ser examinados.

## 18 Registro das decisões e referências

### 18.1 Evolução consolidada

| Tema | Direção anterior | Direção atual |
|---|---|---|
| Instituições | Cadastro genérico de faculdade | Somente comunidade da UNICEPLAC |
| Acesso | Possível e-mail institucional ou matrícula | E-mail comum, sem comprovação institucional |
| Turma no mensal | Preferência | Obrigatória, por curso e período/turma |
| Pagamentos | Registro manual com Pix externo | Intermediação pelo app; simulada no protótipo |
| Segurança | Contato e aprovação de reserva | Aprovação de motorista, PIN, compartilhamento, denúncia, bloqueio e ajuda |
| Idade | Sem limite explicitado inicialmente | 18 anos completos ou mais |
| Preço avulso | Exemplo local de R$ 6 | Fórmula por percurso e custos, com parâmetros a validar |
| Mensalidade | Soma de avulsos sem desconto | Desconto inicial de 10% e fechamento por período |
| Feriados conhecidos | Mensalidade sem exclusão prévia | Excluídos antes da contratação |
| Aula cancelada depois | Destinação em discussão | Retenção pelo aplicativo, sem repasse ao motorista |
| Cancelamento por segurança | Regra mensal comum | Interrupção imediata; acerto financeiro separado |

### 18.2 Referências da discussão

As decisões foram consolidadas a partir desta conversa de planejamento e da conversa compartilhada intitulada “Planejar MVP de caronas”. As fontes abaixo servem para contexto dos recursos e da comparação de preços; não representam aprovação do CaronaUni por essas organizações.

- Conversa compartilhada de planejamento: https://chatgpt.com/share/6aab4528-a428-83e9-97d0-022e5a974b11
- Uber, explicação do recurso de verificação por código: https://help.uber.com/pt-BR/riders/article/hva-er-%C2%ABbekreft-turen%C2%BB?nodeId=2ddbb5e8-0dd3-4048-b9ee-f6b5e5311e25
- Ministério das Mulheres, Ligue 180 e distinção do canal de emergência 190: https://www.gov.br/mulheres/pt-br/ligue180
- UTB, tabela de tarifas usada como comparação regional na discussão: https://www.utb.com.br/tarifas

Tarifas externas devem ser consultadas novamente quando forem usadas para uma decisão comercial. Os cálculos deste documento são exemplos explícitos e não dependem de assumir que uma tarifa histórica permanece vigente.

### 18.3 Próxima entrega recomendada

Com este escopo, a próxima etapa é desenhar o protótipo navegável e os cenários fictícios de demonstração. As pendências devem permanecer visíveis como decisões de produto, sem serem resolvidas silenciosamente na implementação. O avanço para dinheiro e usuários reais exige uma etapa própria de validação operacional.
