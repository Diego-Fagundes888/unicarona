const UI = window.UniCaronaUI;
const svg = UI.icon;
const publicLinks = [
  ["#como-funciona", "Como funciona"],
  ["#mensal", "Mensal"],
  ["#seguranca", "Segurança"],
  ["#oferecer", "Oferecer carona"],
];
for (const nav of document.querySelectorAll(".desktop-nav,#public-menu"))
  nav.innerHTML = publicLinks
    .map(([href, label]) => `<a class="nav-item" href="${href}">${label}</a>`)
    .join("");
const publicMenu = document.querySelector("#public-menu");
publicMenu.addEventListener("click", (event) => {
  if (event.target.closest("a") && publicMenu.matches(":popover-open"))
    publicMenu.hidePopover();
});
document
  .querySelectorAll("[data-icon]")
  .forEach((el) => (el.innerHTML = svg(el.dataset.icon)));
const modal = document.querySelector("#modal");
let modalReturnFocus = null;
modal.addEventListener("close", () => {
  const previous = modalReturnFocus;
  modalReturnFocus = null;
  if (previous?.isConnected) previous.focus();
});
const content = document.querySelector("#modal-content");
const views = {
  login: {
    title: "Acesso à conta",
    icon: "user",
    text: "Entre na sua conta para consultar pedidos, reservas, pagamentos e histórico.",
    action: ["help", "Conhecer o UniCarona"],
  },
  buscar: {
    title: "Encontre uma carona",
    icon: "search",
    text: "No painel, compare trajeto, data, horário, vagas e contribuição de custos nas ofertas da sua faculdade.",
    note: "Entre ou crie sua conta para consultar as ofertas. A vaga só é confirmada após pagamento e verificação de disponibilidade.",
    action: ["login", "Entrar para continuar"],
  },
  avulsa: {
    title: "Como funciona a carona avulsa",
    icon: "car",
    text: "Procure uma vaga para uma data e um trajeto específicos, de ida ou de volta da faculdade. Confira horário, ponto de encontro, valor e confirmação antes de contar com a carona.",
    note: "Solicite uma vaga e aguarde o aceite. O PIN registra o embarque; a confirmação final é uma etapa separada.",
    action: ["login", "Entrar e buscar caronas"],
  },
  monthly: {
    title: "Entenda o plano mensal",
    icon: "calendar",
    text: "O mensal organiza caronas recorrentes entre motorista e passageiro do mesmo curso, período numérico e turma. O plano pode incluir ida, volta ou os dois trajetos.",
    list: [
      "Combine os dias e horários antes do período contratado.",
      "Confira quais trajetos fazem parte do plano.",
      "Acompanhe as reservas recorrentes em Minhas caronas.",
    ],
    note: "Se uma aula for suspensa após a contratação, a parcela do trecho volta integralmente ao crédito do passageiro na confirmação ou após o prazo sem contestação. Uma contestação mantém o valor em análise.",
    action: ["monthlyRules", "Ler as regras do mensal"],
  },
  monthlyRules: {
    title: "Regras do mensal",
    icon: "calendar",
    text: "Motorista e passageiro precisam pertencer ao mesmo curso, período numérico e turma. As informações declaradas não comprovam matrícula.",
    list: [
      "A prévia mostra os trechos e o total proporcional antes do pedido.",
      "Feriados e recessos já conhecidos ficam fora do cálculo.",
      "Se uma aula for cancelada depois da contratação, a mensalidade não diminuirá: a parcela do trecho ficará com o aplicativo, sem repasse ao motorista.",
      "Pode haver cancelamentos. O projeto não garante um motorista substituto.",
    ],
    note: "Cancelar a renovação preserva os trechos do período pago. Relatos de segurança interrompem o vínculo para análise. O ambiente atual usa pagamentos de teste.",
  },
  oferecer: {
    title: "Como oferecer carona",
    icon: "car",
    text: "No painel, envie seus documentos e veículo para análise administrativa. Após a aprovação, publique trajeto, saída e vagas. Você pode oferecer avulsas ou uma rotina mensal da sua turma.",
    note: "Compartilhe um percurso que você já pretende fazer. Confira os combinados e as condições de cancelamento antes da confirmação.",
    action: ["signup", "Conhecer o cadastro"],
  },
  minhas: {
    title: "Acompanhe suas caronas",
    icon: "calendar",
    text: "No painel, acompanhe seus pedidos, reservas, ofertas, planos mensais e histórico. Cada situação vem do registro da sua conta.",
    note: "A oferta depende de disponibilidade. Confira a reserva antes de contar com a vaga.",
    action: ["login", "Entrar para acompanhar"],
  },
  perfil: {
    title: "Seu perfil",
    icon: "user",
    text: "Edite seus dados acadêmicos, foto e contatos no painel. Documentos de motorista ficam disponíveis somente para seu titular e para a administração.",
    action: ["login", "Entrar no meu perfil"],
  },
  notifications: {
    title: "Seus avisos",
    icon: "bell",
    text: "Pedidos, confirmações, pagamentos e cancelamentos geram avisos na sua conta. O ambiente local guarda os e-mails de confirmação para testes.",
    action: ["login", "Ver meus avisos"],
  },
  safety: {
    title: "Cuidados antes de embarcar",
    icon: "shield",
    text: "Confira os dados do motorista e do carro antes de embarcar. A reserva inclui código de embarque, compartilhamento dos detalhes e opções de ocorrência e bloqueio.",
    note: "Use o PIN somente ao embarcar. Você pode contestar parcelas, relatar ocorrências e bloquear novas combinações. O UniCarona não acompanha localização em tempo real nem aciona atendimento de emergência.",
  },
  terms: {
    title: "Regras de participação",
    icon: "cap",
    text: "Participação para maiores de 18 anos. Quem oferece a carona deve informar o trajeto e os horários; quem solicita deve conferir a confirmação antes de contar com a vaga.",
    note: "As condições completas serão apresentadas antes de uma operação real.",
    action: ["monthlyRules", "Ler as regras do mensal"],
  },
  privacy: {
    title: "Como seus dados são usados",
    icon: "shield",
    text: "Conta, perfil, pedidos e histórico são guardados pelo UniCarona. A busca usa a região e a faculdade para encontrar trajetos. Placa e contato ficam disponíveis conforme a reserva; documentos e relatos possuem acesso restrito.",
    note: "O trajeto da página inicial é levado ao painel pelo endereço da página. Senhas ficam protegidas no servidor; o navegador usa cookies de sessão. O ambiente atual não movimenta dinheiro real.",
  },
  help: {
    title: "Como usar o UniCarona",
    icon: "cap",
    text: "Crie sua conta, confirme o e-mail e entre. Busque um trajeto ou solicite aprovação como motorista para oferecer caronas. No mensal, confira a agenda inteira e aguarde um aceite específico. O ambiente atual usa pagamentos de teste.",
    action: ["login", "Abrir acesso ao painel"],
  },
};
function showView(key) {
  if (key === "login") {
    window.location.href = "login.html";
    return;
  }
  if (key === "signup") {
    window.location.href = "login.html#cadastro";
    return;
  }
  if (key === "search") {
    if (modal.open) modal.close();
    document.querySelector("#origin").focus();
    document.querySelector("#ride-search").scrollIntoView({ block: "center" });
    return;
  }
  const view = views[key];
  if (!view) return;
  content.innerHTML = `<div class="dialog-icon">${svg(view.icon)}</div><h2 id="modal-title" tabindex="-1">${view.title}</h2>${view.text ? `<p>${view.text}</p>` : ""}${view.list ? `<ul>${view.list.map((x) => `<li>${x}</li>`).join("")}</ul>` : ""}${view.note ? `<p class="dialog-note">${view.note}</p>` : ""}${view.action ? `<button class="button primary m-button" data-modal="${view.action[0]}">${view.action[1]} ${svg("arrow")}</button>` : ""}`;
  if (!modal.open) {
    modalReturnFocus = document.activeElement;
    modal.showModal();
  }
  document.querySelector("#modal-title").focus();
}
document.addEventListener("click", (event) => {
  if (event.target.closest("[data-search-focus]")) showView("search");
  const trigger = event.target.closest("[data-modal]");
  if (trigger) showView(trigger.dataset.modal);
});
document
  .querySelector(".close-modal")
  .addEventListener("click", () => modal.close());
modal.addEventListener("click", (event) => {
  if (event.target === modal) {
    const r = modal.getBoundingClientRect();
    if (
      event.clientX < r.left ||
      event.clientX > r.right ||
      event.clientY < r.top ||
      event.clientY > r.bottom
    )
      modal.close();
  }
});
let selectedRideType = "avulsa";
document.querySelectorAll("[data-type]").forEach((button) =>
  button.addEventListener("click", () => {
    selectedRideType = button.dataset.type;
    document.querySelectorAll("[data-type]").forEach((other) => {
      const selected = other === button;
      other.classList.toggle("selected", selected);
      other.setAttribute("aria-pressed", String(selected));
    });
  }),
);
document.querySelector("#swap-route").addEventListener("click", () => {
  const origin = document.querySelector("#origin");
  const destination = document.querySelector("#destination");
  [origin.value, destination.value] = [destination.value, origin.value];
  origin.setCustomValidity("");
  destination.setCustomValidity("");
  (origin.value ? destination : origin).focus();
});
const originInput = document.querySelector("#origin");
const destinationInput = document.querySelector("#destination");
for (const input of [originInput, destinationInput]) {
  input.addEventListener("input", () => {
    originInput.setCustomValidity("");
    destinationInput.setCustomValidity("");
  });
}
document.querySelector("#ride-search").addEventListener("submit", (event) => {
  event.preventDefault();
  originInput.setCustomValidity("");
  destinationInput.setCustomValidity("");
  for (const [input, message] of [
    [originInput, "Informe de onde você vai sair."],
    [destinationInput, "Informe para onde você vai."],
  ]) {
    if (!input.value.trim()) {
      input.setCustomValidity(message);
      input.reportValidity();
      return;
    }
  }
  if (
    originInput.value.trim().toLocaleLowerCase("pt-BR") ===
    destinationInput.value.trim().toLocaleLowerCase("pt-BR")
  ) {
    destinationInput.setCustomValidity(
      "Informe locais diferentes para buscar o trajeto.",
    );
    destinationInput.reportValidity();
    return;
  }
  const normalize = (value) => value.trim().toLocaleUpperCase("pt-BR");
  const departureIsCampus = normalize(originInput.value) === "UNICEPLAC";
  if (!departureIsCampus && normalize(destinationInput.value) !== "UNICEPLAC") {
    destinationInput.setCustomValidity(
      "Use UNICEPLAC em um dos locais para buscar ida ou volta da faculdade.",
    );
    destinationInput.reportValidity();
    return;
  }
  const params = new URLSearchParams({
    view: "buscar",
    type: selectedRideType,
    direction: departureIsCampus ? "volta" : "ida",
    region: (departureIsCampus ? destinationInput : originInput).value.trim(),
  });
  location.assign("dashboard.html?" + params);
});

function openPublicRules() {
  const key = { regras: "terms", privacidade: "privacy" }[
    location.hash.slice(1)
  ];
  if (key) showView(key);
}
window.addEventListener("hashchange", openPublicRules);
openPublicRules();
