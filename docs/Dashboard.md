# Painel do aluno conectado

Atualizado em 02/10/2026. `dist/dashboard.html` é a primeira tela após entrar. A identidade vem de `/api/v1/users/me`; acesso sem sessão vai para o login. Erro de conexão mostra uma nova tentativa. `dashboard.js` controla a busca e o shell; `backend-ui.js` executa as jornadas com DTOs da API.

## Organização e layout

O início reúne busca avulsa/mensal, ida/volta, data/horário, próxima reserva real, resumo acadêmico e acesso à oferta. A paleta usa fundo branco, campos cinza e ações pretas, com logo e detalhes locais. No computador, busca e mapa ficam lado a lado e a navegação está no cabeçalho. Até 1000 px há navegação inferior; até 700 px o mapa precede a busca, e data/horário ficam recolhidos. Erros de data abrem o agendamento e focam o campo.

O mapa do OpenStreetMap mostra a região fixa do Gama, com atribuição, aviso de indisponibilidade e link externo. Não solicita localização do aluno, calcula rota ou envia a origem digitada ao iframe. Sua disponibilidade externa não foi comprovada nas capturas locais.

## Jornadas

- Busca valida locais distintos e data futura; alternar sentido preserva a região. Filtros e páginas permanecem no endereço, no histórico e ao recarregar. Região informada na busca pública atravessa o login.
- Minhas caronas lista reservas, pedidos, mensais e ofertas com páginas reais. Pedido pendente/aceito sem pagamento não ocupa vaga. A confirmação depende do resultado do provedor e de nova conferência de capacidade pelo backend.
- Motorista envia documentos privados e veículo para análise. Após aprovação, publica e edita ofertas, recebe pedidos, registra PIN e conclui trechos. A conclusão e a confirmação final são separadas do embarque.
- Mensal exige compatibilidade de curso/período/turma e aceite específico. Mostra agenda, preço congelado, ausências, carência e renovação. Carência retém vagas sem permitir embarque não pago; renovação cria um novo contrato pago e encerra a renovação do anterior.
- Carteira separa futuro, análise, disponível, crédito, processamento, saque e devolução. Pedir uma transferência reserva o saldo; somente evento assinado do provedor encerra o movimento.
- Perfil edita informações e foto privada. Alteração acadêmica incompatível exige confirmação e pode encerrar trechos futuros. Segurança permite relatos e bloqueios inclusive durante revisão da conta; análise financeira é separada da interrupção do contato.
- A foto da outra pessoa aparece na reserva confirmada; bloqueio bilateral revoga foto e contato. Compartilhar detalhes abre texto revisável sem PIN/e-mail/telefone e não envia mensagens automaticamente. O PIN só é apresentado enquanto o trecho permite embarque.
- Administração permite documentos/aprovação, parâmetros versionados, calendário, vínculo acadêmico, acesso de contas, ocorrências, conciliação e fila de envios. Retomar tarefa exige justificativa e preserva sua identidade e tentativas.

Dados de domínio ficam no PostgreSQL. A página não tem troca arbitrária de identidade, relógio fictício ou aprovação administrativa de pagamento pelo cliente. O gateway local é explicitamente de testes; produção recusa esse adapter.

A ajuda informa os canais 190 e 180 e sua finalidade, sem abrir chamada ou
prometer monitoramento. Referência consultada em 02/10/2026:
[Ministério das Mulheres — Ligue 180](https://www.gov.br/mulheres/pt-br/ligue180).

## Verificação

Execute `npm run test:e2e` com banco isolado conforme o README. As jornadas cobrem cadastro, motorista/administração, avulsa até liquidação, mensal pago/ausência/Sem aula, transferências, renovação/carência, falhas e paginação. Capturas ficam em `docs/previews/connected`. [Backend_Verificacao.md](Backend_Verificacao.md) registra os resultados executados e os limites; testes históricos do protótipo não são evidência da versão conectada.
