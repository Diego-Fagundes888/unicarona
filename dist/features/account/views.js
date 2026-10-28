/* account: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({
    state: connectedState,
    UI,
    action,
    badge,
    check,
    esc,
    field,
    form,
    go,
    listMarkup,
    notice,
    pageQuery,
    pages,
    photo,
    request,
    select,
    shifts,
    titles,
  }) {
    async function profileView() {
      connectedState.me = await request("/users/me");
      connectedState.catalog ||= await request("/catalogs");
      if (connectedState.me.role === "admin")
        return (
          notice(
            "Acesso administrativo. O perfil acadêmico pertence às contas de alunos.",
          ) + go("Abrir administração", "admin")
        );
      const courses = connectedState.catalog.courses.filter(
        (c) => c.university_id === connectedState.me.universityId,
      );
      const personal = UI.group(
        "Dados pessoais",
        photo(connectedState.me.photo, connectedState.me.name) +
          field(
            "name",
            "Nome completo",
            connectedState.me.name,
            "text",
            'required minlength="2" maxlength="120" autocomplete="name"',
          ) +
          field(
            "phone",
            "Telefone com DDD (opcional)",
            connectedState.me.phone,
            "tel",
            'maxlength="25" autocomplete="tel"',
          ) +
          field(
            "email",
            "E-mail",
            connectedState.me.email,
            "email",
            'readonly autocomplete="email"',
            "O e-mail usado para acessar sua conta.",
          ) +
          field(
            "photo",
            "Foto de perfil (opcional)",
            "",
            "file",
            'accept="image/png,image/jpeg,image/webp"',
            "PNG, JPG ou WebP de até 2 MB.",
          ),
      );
      const academicFields = UI.group(
        "Sua vida acadêmica",
        select(
          "courseId",
          "Curso",
          courses.map((c) => [c.id, c.name]),
          connectedState.me.courseId,
        ) +
          select(
            "periodStatus",
            "Como você está cursando?",
            [
              ["numbered", "Estou em um período específico"],
              ["irregular", "Faço matérias de períodos diferentes"],
              ["undefined", "Ainda não tenho período definido"],
            ],
            connectedState.me.periodStatus,
          ) +
          field(
            "periodNumber",
            "Número do período",
            connectedState.me.periodNumber || "",
            "number",
            'min="1" max="100" step="1"' +
              (connectedState.me.periodStatus === "numbered"
                ? " required"
                : " disabled"),
            "Obrigatório quando você está em um período específico.",
          ) +
          select(
            "shift",
            "Horário das aulas",
            shifts,
            connectedState.me.shift,
          ) +
          select(
            "classLabel",
            "Turma (opcional)",
            [
              ["", "Não informada"],
              ["A", "Turma A"],
              ["B", "Turma B"],
              ["C", "Turma C"],
            ],
            connectedState.me.classLabel,
          ) +
          UI.disclosure(
            "Qual turma escolher?",
            "<p>Se seu curso não divide turmas, ou possui só uma, selecione A.</p>",
          ) +
          field(
            "region",
            "Região onde mora",
            connectedState.me.region,
            "text",
            'required maxlength="100"',
          ),
      );
      return (
        '<div class="m-grid m-form-layout"><div>' +
        form("profile", personal + academicFields, "Salvar perfil") +
        '</div><aside class="m-form-aside"><h2>Seu vínculo acadêmico</h2><p>' +
        esc(connectedState.me.university) +
        "</p>" +
        badge(connectedState.me.verificationStatus) +
        notice(
          "Curso, período e turma são autodeclarados. A validação administrativa não representa uma integração oficial com a faculdade.",
        ) +
        action("Solicitar análise acadêmica", "academicVerification") +
        '<div class="m-actions">' +
        go("Configurações", "settings", "", "quiet") +
        "</div>" +
        notice(
          "Mudar curso, período ou turma pode encerrar mensais incompatíveis e creditar os trechos futuros. Você confirma essa alteração antes de salvar.",
        ) +
        "</aside></div>"
      );
    }

    async function settings() {
      connectedState.me = await request("/auth/me");
      const blocks = await request("/blocks?" + pageQuery("settings_blocks"));
      return (
        '<div class="m-account-settings"><section><h2>Privacidade e conta</h2><p>Documentos, comunicações, seus dados e opções de desativação ou exclusão.</p>' +
        go("Abrir Privacidade e dados", "privacy", "", "secondary") +
        "</section><section><h2>Preferências</h2>" +
        form(
          "settings",
          check(
            "notices",
            "Receber avisos da lista de interesse",
            connectedState.me.settings.notices,
          ) +
            '<p class="m-help">Receba avisos relacionados ao seu interesse em rotinas mensais.</p>' +
            select(
              "withdrawal",
              "Preferência de saque",
              [
                ["daily", "Diário"],
                ["monthly", "Mensal"],
              ],
              connectedState.me.settings.withdrawal,
            ),
          "Salvar preferências",
        ) +
        notice(
          "A preferência acompanha cada solicitação de saque. Avisos necessários de segurança, pagamento e confirmação permanecem ativos.",
        ) +
        "</section>" +
        UI.disclosure(
          "Trocar senha",
          form(
            "password",
            field(
              "currentPassword",
              "Senha atual",
              "",
              "password",
              'required autocomplete="current-password"',
            ) +
              field(
                "password",
                "Nova senha",
                "",
                "password",
                'required minlength="12" maxlength="128" autocomplete="new-password"',
                "Use pelo menos 12 caracteres.",
              ) +
              field(
                "confirm",
                "Confirmar nova senha",
                "",
                "password",
                'required minlength="12" maxlength="128" autocomplete="new-password"',
              ),
            "Alterar senha",
          ),
        ) +
        UI.disclosure(
          "Pessoas bloqueadas",
          listMarkup(
            blocks.items,
            (b) =>
              '<article class="m-row"><div><h3>' +
              esc(b.name) +
              "</h3><p>Novas combinações impedidas.</p></div>" +
              action(
                "Desbloquear",
                "unblock",
                { id: b.id },
                "quiet",
                "Retirar o bloqueio? Vínculos encerrados não serão reativados.",
              ) +
              "</article>",
            "Você não bloqueou ninguém.",
          ) + pages(blocks, "settings_blocks"),
        ) +
        '<div class="m-actions">' +
        action("Sair da conta", "logout", {}, "quiet") +
        "</div></div>"
      );
    }

    async function notifications() {
      const data = await request("/notifications?" + pageQuery("notices"));
      // API event destinations use plural resource names. Confirmation events
      // reference the request, which owns the confirmed reservations.
      const destinations = {
        requests: "request",
        reservations: "request",
        payments: "checkout",
      };
      return (
        '<p class="m-muted">' +
        data.unread +
        " aviso(s) não lido(s).</p>" +
        listMarkup(
          data.items,
          (n) =>
            '<article class="m-row"><div><h3>' +
            esc(n.title) +
            "</h3><p>" +
            esc(n.body) +
            '</p><p class="m-help">' +
            esc(UI.dateTime(n.createdAt)) +
            '</p></div><div class="m-row-actions">' +
            (Object.hasOwn(titles, destinations[n.route] || n.route)
              ? go(
                  "Abrir aviso",
                  destinations[n.route] || n.route,
                  n.ref || "",
                  "secondary",
                )
              : "") +
            (!n.readAt
              ? action("Marcar como lido", "readNotice", { id: n.id }, "quiet")
              : '<span class="m-badge">Lido</span>') +
            "</div></article>",
          "Você está em dia. Novos avisos de pedidos, pagamentos e viagens aparecerão aqui.",
        ) +
        pages(data, "notices")
      );
    }

    async function help() {
      return (
        '<div class="m-help-page"><p>Encontre orientações para combinar, pagar e acompanhar suas caronas.</p>' +
        UI.disclosure(
          "Como reservar uma carona avulsa?",
          "<ol><li>Busque o trajeto e confira encontro, horário e motorista.</li><li>Solicite a vaga. Quando necessário, aguarde o aceite.</li><li>Conclua o pagamento e confira a confirmação da reserva.</li></ol>" +
            go("Buscar uma carona", "buscar", "", "secondary"),
        ) +
        UI.disclosure(
          "Como funciona o mensal?",
          "<p>O mensal reúne estudantes do mesmo curso, período numérico e turma. Confira a agenda completa e o valor antes de solicitar. Cada contratação exige o aceite específico do motorista.</p><p>A ausência avisada mantém a vaga e o valor. Desativar a renovação preserva os trechos já pagos.</p>",
        ) +
        UI.disclosure(
          "Qual é a função do código de embarque?",
          "<p>Apresente o PIN somente ao embarcar. Ele registra o embarque e não libera dinheiro. A confirmação final e a contestação são etapas separadas.</p>",
        ) +
        UI.disclosure(
          "Meu pagamento não confirmou a reserva",
          "<p>Confira o status no pagamento. A reserva depende da confirmação do provedor e da disponibilidade. Se o valor foi recebido e a vaga ficou indisponível, consulte o crédito na carteira.</p>" +
            go("Ver minhas caronas", "minhas", "", "secondary") +
            go("Ver carteira", "wallet", "", "quiet"),
        ) +
        UI.disclosure(
          "Como começar a oferecer caronas?",
          "<p>Cadastre veículo e documentos e aguarde a análise. Depois da aprovação, publique seu trajeto e acompanhe os pedidos recebidos.</p>" +
            go("Cadastro de motorista", "driver", "", "secondary"),
        ) +
        UI.disclosure(
          "Tive um problema durante a carona",
          "<p>Abra o relato pelos detalhes da reserva para vincular a pessoa e o trecho. Em risco imediato, procure os canais de emergência.</p>" +
            go("Segurança e ocorrências", "safety", "", "secondary"),
        ) +
        "</div>"
      );
    }
    return { profileView, settings, notifications, help };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.account = Object.freeze({ create });
})();
