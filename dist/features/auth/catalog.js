/* authCatalog: explicit dependencies and session-owned state. */
(() => {
  "use strict";
  function create({ state: authState, api, error, host }) {
    function loadCatalogs() {
      if (!authState.catalogPromise)
        authState.catalogPromise = api
          .request("/catalogs")
          .then((catalog) => {
            const university = catalog.universities.find(
              (item) => item.slug === "uniceplac",
            );
            if (!university)
              throw Error("A faculdade está temporariamente indisponível.");
            authState.selectionData = {
              universityId: university.id,
              courses: catalog.courses
                .filter((item) => item.university_id === university.id)
                .map((item) => ({ id: item.id, label: item.name })),
              regions: catalog.regions.map((item) => ({
                id: item.id,
                label: item.name,
              })),
            };
            authState.catalogReady = true;
          })
          .catch((error) => {
            authState.catalogPromise = null;
            throw error;
          });
      return authState.catalogPromise;
    }

    function normalizeSearch(value) {
      return value
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/[’']/g, "")
        .replace(/[^\p{L}\p{N}\s]/gu, " ")
        .toLocaleLowerCase("pt-BR")
        .replace(/\s+/g, " ")
        .trim();
    }

    function clearFieldFeedback(field) {
      field.removeAttribute("aria-invalid");
      const errEl = document.querySelector(`#${field.id}-error`);
      if (errEl) errEl.textContent = "";
      const fb = document.querySelector("#feedback");
      if (fb) fb.textContent = "";
    }

    function bindCatalogCombobox(
      input,
      listbox,
      status,
      items,
      onSelect,
      onClear,
      selectedSummary,
    ) {
      let matches = [];
      let activeIndex = -1;
      let keepOpenUntilSubmit = false;

      const close = () => {
        listbox.hidden = true;
        input.setAttribute("aria-expanded", "false");
        input.removeAttribute("aria-activedescendant");
        activeIndex = -1;
      };

      const setActive = (index) => {
        activeIndex = index;
        const options = listbox.querySelectorAll('[role="option"]');
        options.forEach((option, optionIndex) =>
          option.setAttribute(
            "aria-selected",
            String(optionIndex === activeIndex),
          ),
        );
        const activeOption = options[activeIndex];
        if (activeOption) {
          input.setAttribute("aria-activedescendant", activeOption.id);
          activeOption.scrollIntoView({ block: "nearest" });
        } else input.removeAttribute("aria-activedescendant");
      };

      const show = () => {
        const query = normalizeSearch(input.value);
        matches = items.filter((item) =>
          normalizeSearch(
            `${item.label} ${item.name || ""} ${item.state || ""}`,
          ).includes(query),
        );
        listbox.replaceChildren();
        activeIndex = -1;

        if (status) {
          status.textContent = matches.length
            ? `${matches.length} ${matches.length === 1 ? "opção encontrada" : "opções encontradas"}. Use as setas e Enter para selecionar.`
            : "Nenhuma opção encontrada.";
        }

        if (matches.length) {
          for (const item of matches) {
            const option = document.createElement("div");
            option.id = `${listbox.id}-option-${item.id}`;
            option.className = "combobox-option";
            option.setAttribute("role", "option");
            option.setAttribute("aria-selected", "false");
            option.textContent = item.label;
            option.addEventListener("pointerdown", (event) =>
              event.preventDefault(),
            );
            option.addEventListener("click", () => select(item));
            listbox.append(option);
          }
        } else {
          const empty = document.createElement("div");
          empty.className = "combobox-empty";
          empty.setAttribute("role", "presentation");
          empty.setAttribute("aria-hidden", "true");
          empty.textContent = "Nenhuma opção encontrada.";
          listbox.append(empty);
        }

        listbox.hidden = false;
        input.setAttribute("aria-expanded", "true");
      };

      const setSelectedId = (id, notify = false) => {
        const item = items.find((candidate) => candidate.id === id);
        input.dataset.selectedId = item ? item.id : "";
        input.value = item ? item.label : "";
        if (selectedSummary) {
          selectedSummary.hidden = !item;
          selectedSummary.textContent = item
            ? `Selecionado: ${item.label}`
            : "";
        }
        close();
        clearFieldFeedback(input);
        if (item && notify) {
          if (status) status.textContent = `${item.label} selecionado.`;
          onSelect(item);
        }
        return item || null;
      };

      const select = (item) => setSelectedId(item.id, true);

      input.addEventListener("focus", show);
      input.addEventListener("click", show);
      input.addEventListener("input", () => {
        input.dataset.selectedId = "";
        if (selectedSummary) {
          selectedSummary.hidden = true;
          selectedSummary.textContent = "";
        }
        if (onClear) onClear();
        clearFieldFeedback(input);
        show();
      });
      input.form?.addEventListener("pointerdown", (event) => {
        if (!listbox.hidden && event.target.closest("button[type=submit]"))
          keepOpenUntilSubmit = true;
      });
      input.addEventListener("blur", () => {
        if (!keepOpenUntilSubmit) close();
      });
      input.form?.addEventListener(
        "submit",
        () => {
          keepOpenUntilSubmit = false;
          close();
        },
        { capture: true },
      );
      input.addEventListener("keydown", (event) => {
        if (event.key === "ArrowDown") {
          if (listbox.hidden) show();
          if (matches.length) {
            event.preventDefault();
            setActive((activeIndex + 1) % matches.length);
          }
        } else if (event.key === "ArrowUp") {
          if (listbox.hidden) show();
          if (matches.length) {
            event.preventDefault();
            setActive(activeIndex <= 0 ? matches.length - 1 : activeIndex - 1);
          }
        } else if (event.key === "Enter" && !listbox.hidden) {
          event.preventDefault();
          if (activeIndex >= 0 && matches[activeIndex]) {
            select(matches[activeIndex]);
          } else if (matches.length === 1) {
            select(matches[0]);
          } else if (matches.length > 1) {
            setActive(0);
          }
        } else if (event.key === "Escape" && !listbox.hidden) {
          event.preventDefault();
          close();
        } else if (event.key === "Tab") close();
      });

      return { setSelectedId };
    }

    function readAcademicDraft() {
      return {
        periodStatus: host.querySelector("#signup-period-status").value,
        periodNumber: host.querySelector("#signup-period").value,
        shift: host.querySelector("#signup-shift").value,
        classLabel: host.querySelector("#signup-class").value,
      };
    }

    function updatePeriodNumberField() {
      const numbered =
        host.querySelector("#signup-period-status").value === "numbered";
      const period = host.querySelector("#signup-period");
      host.querySelector("#signup-period-number-field").hidden = !numbered;
      period.disabled =
        !numbered || !host.querySelector("#signup-course").dataset.selectedId;
      period.required = numbered;
      if (!numbered) {
        period.value = "";
        clearFieldFeedback(period);
      }
    }

    function restoreAcademicFields(courseId, draft = {}) {
      host.querySelector("#signup-period-status").value =
        draft.periodStatus || "numbered";
      host.querySelector("#signup-period").value = draft.periodNumber ?? "";
      host.querySelector("#signup-shift").value = draft.shift || "";
      host.querySelector("#signup-class").value = draft.classLabel || "";
      host
        .querySelectorAll(
          ".academic-selection input, .academic-selection select",
        )
        .forEach((field) => {
          field.disabled = !courseId;
          clearFieldFeedback(field);
        });
      updatePeriodNumberField();
    }

    function validateAcademicPlacement() {
      const draft = readAcademicDraft();
      if (
        !["numbered", "irregular", "undefined"].includes(draft.periodStatus)
      ) {
        error(
          "signup-period-status",
          "Escolha uma situação de período válida.",
        );
        return { valid: false };
      }
      const numberText =
        draft.periodStatus === "numbered" ? draft.periodNumber.trim() : "";
      const periodNumber = numberText ? Number(numberText) : null;
      if (draft.periodStatus === "numbered" && !numberText) {
        error(
          "signup-period",
          "Informe o número do seu período. Ex.: 3 para o 3º período.",
        );
        return { valid: false };
      }
      if (
        numberText &&
        (!/^\d+$/.test(numberText) ||
          !Number.isSafeInteger(periodNumber) ||
          periodNumber <= 0)
      ) {
        error(
          "signup-period",
          "Informe um número inteiro positivo para o período.",
        );
        return { valid: false };
      }
      if (periodNumber > 100) {
        error("signup-period", "Informe um número de período de 1 a 100.");
        return { valid: false };
      }
      if (
        !["morning", "afternoon", "night", "full-time", "variable"].includes(
          draft.shift,
        )
      ) {
        error("signup-shift", "Selecione o horário das suas aulas.");
        return { valid: false };
      }
      if (!["", "A", "B", "C"].includes(draft.classLabel)) {
        error("signup-class", "Escolha a turma A, B ou C da lista.");
        return { valid: false };
      }
      const periodStatus = draft.periodStatus;
      const shift = draft.shift;
      const classLabel = draft.classLabel || null;
      return {
        valid: true,
        placement: {
          source: "self-reported",
          periodStatus,
          periodNumber,
          shift,
          classLabel,
        },
      };
    }

    function saveSignupProfileDraft() {
      const courseInput = document.querySelector("#signup-course");
      const regionInput = document.querySelector("#signup-neighborhood");
      if (!courseInput || !regionInput) return;

      const selectedCourse = authState.selectionData.courses.find(
        (item) =>
          item.id === courseInput.dataset.selectedId &&
          item.label === courseInput.value,
      );
      const selectedRegion = authState.selectionData.regions.find(
        (item) =>
          item.id === regionInput.dataset.selectedId &&
          item.label === regionInput.value,
      );
      const courseId = selectedCourse?.id || "";
      const photoInput = document.querySelector("#signup-photo");

      authState.signupProfileDraft = {
        courseId,
        courseQuery: courseInput.value,
        regionId: selectedRegion?.id || "",
        regionQuery: regionInput.value,
        academicDraft: courseId ? readAcademicDraft() : null,
        phone: document.querySelector("#signup-phone")?.value || "",
        photo: photoInput?.files?.[0] || null,
      };
    }
    return {
      loadCatalogs,
      normalizeSearch,
      clearFieldFeedback,
      bindCatalogCombobox,
      readAcademicDraft,
      updatePeriodNumberField,
      restoreAcademicFields,
      validateAcademicPlacement,
      saveSignupProfileDraft,
    };
  }
  window.UniCaronaFeatures ||= {};
  window.UniCaronaFeatures.authCatalog = Object.freeze({ create });
})();
