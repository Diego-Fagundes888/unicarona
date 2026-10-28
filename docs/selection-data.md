# Catálogos de seleção do cadastro

Atualizado em 02/10/2026: cadastro consome `/api/v1/catalogs`; os IDs enviados são UUIDs do banco. O seed usa `dist/selection-data.js` como fonte inicial de nomes e slugs, cuja pesquisa institucional foi registrada em 24 de setembro de 2026. Os IDs são chaves internas do UniCarona; não são códigos acadêmicos oficiais. Período, turno e turma são declarações do aluno, independentes desse catálogo; as regras de preenchimento estão descritas abaixo.

## Curso

A página oficial de Graduação apresenta 21 cursos e seus nomes. O cadastro usa esses títulos sem modalidade, porque fontes oficiais atuais divergem sobre a modalidade de Biomedicina: a página de Graduação indica EaD, enquanto o processo seletivo 2027.1 a exibe como presencial. A página de Graduação também chama o curso de “Marketing Digital e Inteligência de Dados”, enquanto a página de Coordenações ainda usa “Marketing Digital e Ciência de Dados”; foi adotado o título da listagem de Graduação. Para Estética, foi usado o nome do título do curso (“Estética e Cosmética”), não a inversão que aparece no texto descritivo.

O campo é um combobox de busca com seleção obrigatória. A busca ignora acentos e diferenças entre maiúsculas e minúsculas, anuncia a quantidade de resultados e comunica buscas vazias em uma região de status separada das opções. O valor submetido precisa corresponder ao ID e ao título canônico do catálogo. Não há opção para digitar um curso novo.

## Período, turno e turma

A consulta institucional registrada em 24 de setembro de 2026 não forneceu uma relação pública completa de curso → período → turno → turma dos alunos atuais. Os campos são habilitados após selecionar um curso válido e permanecem autodeclarados, sem verificação da faculdade. As regras de preenchimento foram atualizadas em 1 de outubro de 2026 conforme o pedido do usuário.

- **Como você cursa as matérias? (opcional):** “Um período por vez” (padrão), “Matérias de períodos diferentes” ou “Ainda sem período definido”. Essas opções substituem os termos menos claros “Informar número”, “Irregular” e “Sem período definido”.
- **Número do período (obrigatório):** inteiro entre 1 e 100, limite técnico compartilhado com o backend, sem inferir a duração de um curso. Exemplo visível: 3 para o 3º período. O número é exigido quando a situação selecionada é “Um período por vez”. Nas situações não numéricas, o campo é ocultado e desabilitado, deixa de ser obrigatório e o número anterior é apagado.
- **Horário das aulas (turno), obrigatório:** Manhã, Tarde, Noite, O dia todo ou Horários variados. A opção inicial “Selecione o horário” não permite avançar. As chaves internas continuam `morning`, `afternoon`, `night`, `full-time` e `variable`.
- **Turma (opcional):** seleção entre Turma A, B e C, com valores internos `A`, `B` e `C`. Não há texto livre nem seleção automática de A. A dica “Não sabe qual turma escolher?” fica recolhida abaixo do campo, com destaque verde; ao abrir, explica que, se não houver divisão de turmas ou existir só uma, o aluno deve selecionar Turma A. Essas letras são opções do produto solicitadas pelo usuário, não um catálogo institucional verificado.

O envio bloqueia número ausente/inválido na situação numérica, turno ausente/inválido e turma fora da lista. Uma turma vazia é aceita e vira `null`. Trocar ou limpar o curso limpa os campos acadêmicos. Reselecionar o mesmo curso sem editar a busca preserva os valores. Rascunhos parciais e números inválidos são preservados ao voltar entre etapas.

Depois de um envio válido, o objeto em memória tem este formato:

```js
{
  source: "self-reported",
  periodStatus: "numbered", // "numbered" | "irregular" | "undefined"
  periodNumber: 3,          // inteiro positivo; null nas situações não numéricas
  shift: "night",          // "morning" | "afternoon" | "night" | "full-time" | "variable"
  classLabel: "A"           // "A" | "B" | "C" | null
}
```

Antes do envio, `signupData.academicPlacement` permanece `null`. O rascunho bruto fica em `signupProfileDraft.academicDraft`, na memória da página. Recarregar ou sair descarta o rascunho; o envio concluído grava o perfil no PostgreSQL pela API. IDs e relações acadêmicas são validados no servidor.

Para o mensal, permanece a regra de mesmo curso e período/turma. Dados ausentes, período irregular ou indefinido não permitem concluir compatibilidade. Igualdade da letra da turma não representa validação institucional, e confirmar o e-mail não verifica vínculo acadêmico. Após confirmar e-mail e entrar, o backend calcula a compatibilidade e permite contratar caronas de acordo com o perfil.

Os testes históricos em `tests/academic-profile.cjs`, também executados por `tests/login.cjs`, cobriam a obrigatoriedade do número e do turno, as situações não numéricas, todos os turnos, turmas A/B/C e turma vazia, rejeição de opções adulteradas, dica recolhida e acesso por teclado, rascunhos, troca de curso, ausência de envio/armazenamento no protótipo e cinco larguras de tela. Esse resultado antigo não comprova a API conectada; a cobertura atual está em `Backend_Verificacao.md`.

## Região de embarque

O serviço geográfico oficial do DF publica as 35 Regiões Administrativas, e a SUDECO lista os 33 municípios que compõem a RIDE-DF além do DF. Usar essas unidades como locais de embarque é uma escolha de produto baseada nas divisões administrativas disponíveis; não representa um catálogo de pontos de embarque fornecido pela faculdade. O campo se chama “Região ou cidade” porque não encontrei uma lista institucional verificável de bairros e não seria correto inventá-la. As opções incluem a UF e, no caso do DF, o número da RA, para diferenciar nomes como Planaltina.

O campo de região também é um combobox pesquisável e só aceita um item do catálogo. Se o produto precisar registrar o bairro além da RA/cidade, será necessário cadastrar uma fonte confiável e definir como bairros se relacionam às regiões.

## Atualização do catálogo e limites

- Atualize nomes e slugs da fonte inicial em `dist/selection-data.js` e execute o seed versionado. A interface consulta os IDs persistidos no catálogo da API; não replique listas em `login.js` ou nos testes.
- Uma futura integração institucional deverá distinguir informações oficiais das autodeclarações; nunca apresentar uma declaração do aluno como verificada automaticamente.
- Não acrescente modalidade aos cursos enquanto a divergência de fontes não for resolvida.
- API e PostgreSQL já validam e persistem os IDs e suas relações. Os nomes pesquisados em 24/09 são uma fonte inicial; essa pesquisa histórica não comprova integração oficial ou atualização automática do catálogo institucional.

## Fontes consultadas

- [UNICEPLAC — Cursos de Graduação](https://www.uniceplac.edu.br/graduacao/)
- [UNICEPLAC — Coordenações](https://www.uniceplac.edu.br/coordenacoes/)
- [UNICEPLAC — Processo Seletivo Graduação Presencial 2027.1](https://www.uniceplac.edu.br/processo-seletivo-graduacao-presencial/)
- [UNICEPLAC — Calendário Acadêmico e Manuais (Ensalamento 2026.2)](https://www.uniceplac.edu.br/calendario-academico-2/)
- [UNICEPLAC — Ensalamento Institucional](https://ensalamentouniceplac.netlify.app/)
- [UNICEPLAC — Edital de ingresso 2026.2, demais cursos (PDF)](https://www.uniceplac.edu.br/app/uploads/2026/06/DEMAIS-CURSOS-2026.2.pdf)
- [IDE-DF — Serviço geográfico das Regiões Administrativas](https://www.geoservicos.ide.df.gov.br/arcgis/rest/services/Publico/IDEDF/MapServer/123)
- [GDF — Regiões Administrativas do DF](https://www.agenciabrasilia.df.gov.br/web/guest/topicos?delta=40&sort=createDate-&tag=regi%C3%B5es+administrativas)
- [SUDECO — RIDE-DF e relação dos municípios](https://www.gov.br/sudeco/pt-br/assuntos/ride-df)
