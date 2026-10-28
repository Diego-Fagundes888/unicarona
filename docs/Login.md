# Acesso e cadastro conectados

Atualizado em 16/10/2026. `dist/login.html`, `login.css` e `login.js` usam a API da mesma origem. Execute o servidor conforme o README; uma prévia estática na porta 4173 não substitui a API.

## Fluxos

- Login envia e-mail e senha ao servidor e recebe sessão em cookies HttpOnly. Dados incorretos geram mensagem genérica; a senha do formulário é limpa após a tentativa. A página não guarda tokens em localStorage ou sessionStorage.
- Cadastro tem dados de acesso, perfil acadêmico e espera pela confirmação do e-mail. O POST de registro é persistente, retorna resposta genérica e não autentica antes da confirmação. Depois de confirmar pelo link recebido, o estudante entra e chega a `dashboard.html`.
- Recuperação e reenvio têm resposta genérica e limite de tentativas. Links contêm tokens imprevisíveis, expiram e são de uso único. O navegador remove o fragmento com o token do endereço. Redefinir ou trocar a senha revoga sessões anteriores.
- No desenvolvimento, o worker pode gravar mensagens na caixa local privada. Resend e SMTP permitem envio real configurado. O teste externo de confirmação, recebimento, link e login foi concluído com Resend; sem domínio próprio, o remetente de teste fica restrito ao endereço da conta do provedor. Configuração, testes e limites estão em [Verificacao_Email.md](Verificacao_Email.md).
- Falhas apresentam mensagem e nova tentativa. Controles do formulário ficam desabilitados enquanto a ação está pendente. Retorno após login aceita somente o painel da mesma origem e preserva filtros da busca pública.

## Perfil acadêmico

Curso e região vêm de `/api/v1/catalogs`, com IDs do PostgreSQL. Os comboboxes ignoram acentos e caixa, têm seleção obrigatória, navegação por teclado, anúncio de resultados e estado vazio. Texto livre não é tratado como uma opção válida. O backend valida novamente os IDs e o vínculo do curso com a faculdade.

Número do período é obrigatório quando o aluno escolhe um período específico; nas situações irregular ou indefinida, ele é apagado e não enviado. O backend aceita números de 1 a 100. Turno é sempre obrigatório: Manhã, Tarde, Noite, O dia todo ou Horários variados. Turma é opcional, com A/B/C; a dica recolhida orienta selecionar A quando houver só uma turma ou não houver divisão.

Voltar entre etapas preserva o rascunho em memória. Trocar ou limpar o curso limpa período, turno e turma; reselecionar o mesmo curso preserva as escolhas. Recarregar perde o rascunho. A senha do cadastro é descartada após o envio concluído. O formulário avisa que a foto poderá ser adicionada em Perfil depois de confirmar o e-mail e entrar.

Nascimento, informações acadêmicas e declarações são autodeclarados. Confirmar e-mail não verifica idade, identidade ou vínculo institucional. A validação acadêmica tem estado e origem separados, com análise administrativa no MVP.

## Verificação

O backend atual é Java. `AuthHttpIT` e `EmailVerificationHttpIT` verificam cadastro, confirmação, login, recuperação, concorrência e segurança por HTTP/PostgreSQL. Nesta etapa, o navegador foi operado para comprovar o link realmente recebido, a interface de sucesso e o login; o relatório está em [Verificacao_Email.md](Verificacao_Email.md). As jornadas atuais em `backend-java/e2e/` usam o backend Java real. A referência TypeScript anterior foi retirada.

Os arquivos antigos em `tests/` descrevem a apresentação em memória. Seus cenários úteis estão sendo adaptados para o sistema conectado; resultados antigos não comprovam o backend atual.
