/* Public campus context only. No geolocation, trip tracking or fare calculation. */
(() => {
  "use strict";
  const campus = [-16.003049875168237, -48.051869232920424];
  function mount(section, disclosure) {
    const canvas = section.querySelector("[data-map-canvas]");
    const status = section.querySelector("[data-map-status]");
    const title = section.querySelector("[data-map-title]");
    const copy = section.querySelector("[data-map-copy]");
    const retry = section.querySelector("[data-map-retry]");
    let map,
      tiles,
      timer,
      failed = false,
      disposed = false;
    function state(value) {
      if (disposed) return;
      section.dataset.mapState = value;
      canvas.setAttribute("aria-busy", String(value === "loading"));
      status.hidden = value === "ready";
      retry.hidden = value === "loading" || value === "ready";
      const content = {
        loading: [
          "Carregando a região do campus",
          "A busca de caronas continua disponível enquanto o mapa carrega.",
        ],
        slow: [
          "O mapa está demorando",
          "Você pode continuar a busca ou abrir a região do campus no OpenStreetMap.",
        ],
        error: [
          "Mapa indisponível",
          "Não foi possível carregar as imagens do mapa. Continue a busca ou abra a região do campus no OpenStreetMap.",
        ],
      }[value];
      if (content) {
        title.textContent = content[0];
        copy.textContent = content[1];
      }
    }
    function loading() {
      failed = false;
      clearTimeout(timer);
      state("loading");
      timer = setTimeout(() => state("slow"), 12000);
    }
    function initialize() {
      const route = new URL(location.href).searchParams.get("view") || "inicio";
      if (!["inicio", "buscar"].includes(route)) return;
      if (
        disposed ||
        !disclosure.open ||
        !canvas.clientWidth ||
        !canvas.clientHeight
      )
        return;
      if (map) {
        map.invalidateSize({ pan: false });
        return;
      }
      if (!window.L) {
        state("error");
        return;
      }
      const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
      map = window.L.map(canvas, {
        center: campus,
        zoom: 14,
        minZoom: 12,
        maxZoom: 17,
        zoomControl: false,
        attributionControl: false,
        scrollWheelZoom: false,
        zoomAnimation: !reduced,
        fadeAnimation: !reduced,
        markerZoomAnimation: !reduced,
      });
      window.L.control
        .zoom({
          position: "topright",
          zoomInTitle: "Aproximar mapa",
          zoomOutTitle: "Afastar mapa",
          zoomInText: window.UniCaronaUI.icon("plus"),
          zoomOutText: window.UniCaronaUI.icon("minus"),
        })
        .addTo(map);
      window.L.marker(campus, {
        title: "UNICEPLAC — Gama, DF",
        keyboard: true,
        icon: window.L.divIcon({
          className: "d-campus-marker",
          html: window.UniCaronaUI.icon("pin"),
          iconSize: [40, 40],
          iconAnchor: [20, 20],
        }),
      })
        .addTo(map)
        .bindTooltip("UNICEPLAC · Gama, DF", {
          direction: "top",
          offset: [0, -16],
        });
      tiles = window.L.tileLayer(
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        {
          maxZoom: 19,
          keepBuffer: 0,
          updateWhenIdle: true,
          referrerPolicy: "strict-origin-when-cross-origin",
        },
      );
      tiles.on("loading", loading);
      tiles.on("tileerror", (event) => {
        failed = true;
        event.tile.style.visibility = "hidden";
      });
      tiles.on("load", () => {
        clearTimeout(timer);
        state(failed ? "error" : "ready");
      });
      loading();
      tiles.addTo(map);
    }
    function refresh() {
      if (!map) initialize();
      else {
        loading();
        tiles.redraw();
      }
    }
    retry.addEventListener("click", refresh);
    disclosure.addEventListener("toggle", initialize);
    const observer = new ResizeObserver(initialize);
    observer.observe(canvas);
    initialize();
    return {
      destroy() {
        disposed = true;
        clearTimeout(timer);
        observer.disconnect();
        retry.removeEventListener("click", refresh);
        disclosure.removeEventListener("toggle", initialize);
        map?.remove();
      },
    };
  }
  window.UniCaronaCampusMap = Object.freeze({ mount });
})();
