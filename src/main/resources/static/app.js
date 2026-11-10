"use strict";

/**
 * Vanilla JS-frontend (geen build-stap nodig, build-instruction.md §6). Kaart + zoekfunctie zijn
 * altijd actief; bewerkingsknoppen verschijnen enkel na een succesvolle GET /api/me met
 * authenticated=true.
 */

const state = {
  me: { authenticated: false, name: null, email: null },
  stopPlaceTypes: [], // [{code, label}], zie loadStopPlaceTypes()
  stopsById: new Map(), // id -> { name, stopType, publicCode, parentId, isSynthetic }
  markersById: new Map(),
  map: null,
  markerLayer: null,
  selectedStopId: null,
  lastDetail: null, // laatst gerenderde StopDetail/-history, voor herrenderen bij taalwissel
  lastHistory: null,
  pickCallback: null, // actief tijdens "kies een halte via zoeken/kaart"-modus
  merge: { active: false, sourceId: null, targetId: null },
  group: { active: false, pickedIds: [] },
  showOnlyUnreviewed: false, // "Kaart: enkel nog te bekijken"-toggle
  showDisabled: false, // "Toon uitgeschakelde haltes"-toggle
  reviewCounts: { modeMismatches: 0, duplicateQuays: 0 }, // tellers op de rapportenknop
};

function getCookie(name) {
  const match = document.cookie.match("(^|;)\\s*" + name + "\\s*=\\s*([^;]+)");
  return match ? decodeURIComponent(match.pop()) : null;
}

async function apiFetch(url, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  // UI-taal: de server kiest er de haltenaam mee (chb.quay_names/stop_names, issue #2).
  const headers = Object.assign({ "Accept-Language": getLang() }, options.headers);
  if (method !== "GET" && method !== "HEAD") {
    const token = getCookie("XSRF-TOKEN");
    if (token) headers["X-XSRF-TOKEN"] = token;
    if (options.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  }
  let response;
  try {
    response = await fetch(url, Object.assign({}, options, { headers, credentials: "same-origin" }));
  } catch (e) {
    throw new Error(t("error.network")); // "Failed to fetch" is onvertaald en zegt de curator niets
  }
  if (response.ok && options.method && options.method !== "GET") refreshReviewCounts();
  if (response.status === 204) return null;
  if (!response.ok) {
    let body = null;
    try {
      body = await response.json();
    } catch (ignored) { /* geen JSON-body (bv. 401 van HttpStatusEntryPoint) */ }
    if (response.status === 401) sessionExpired();
    // statusText is leeg op HTTP/2, dus bij een ontbrekende servermelding een vertaalde per status.
    const message = response.status === 401 || response.status === 403 || !(body && body.message)
      ? httpStatusMessage(response.status)
      : body.message;
    const error = new Error(message);
    error.status = response.status;
    // Door de database geweigerde curator-actie: SQLSTATE (UC001 = dubbele naam, ...), betrokken
    // id's en het id waar de curator wel moet zijn (bv. de quay achter een wrapper).
    error.code = body && body.code || null;
    error.detail = body && body.detail || null;
    error.targetId = body && body.targetId != null ? body.targetId : null;
    throw error;
  }
  const contentType = response.headers.get("content-type") || "";
  return contentType.includes("application/json") ? response.json() : null;
}

/**
 * Toont een geweigerde actie in een foutveld. Heeft de weigering een {@code targetId} (bv. "wijzig de
 * quay" bij een wrapper), dan komt er een knop bij die er rechtstreeks naartoe navigeert i.p.v. enkel
 * de kale melding te tonen. De technische detail (betrokken id's) staat als tooltip.
 */
function showActionError(el, e) {
  el.textContent = e.message;
  el.title = e.detail || "";
  if (e.targetId != null) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary";
    button.textContent = t("error.goTo", { id: e.targetId });
    button.addEventListener("click", () => {
      closeDialog();
      selectStop(e.targetId);
    });
    el.append(" ", button);
  }
}

function httpStatusMessage(status) {
  if (status === 401 || status === 403 || status === 404) return t("error.http" + status);
  return t("error.httpStatus", { status });
}

// Sessie verlopen: terug naar de anonieme weergave, zodat de bewerkknoppen verdwijnen en de
// inloglink weer verschijnt.
function sessionExpired() {
  if (!state.me.authenticated) return;
  state.me = { authenticated: false, curator: false, name: null, email: null };
  renderAuthArea();
  document.getElementById("toolbar").hidden = true;
  if (!document.getElementById("panel").hidden && state.lastDetail) {
    state.lastHistory = null;
    renderPanel(state.lastDetail, null);
  }
}

/**
 * Melding voor acties zonder eigen foutveld. Een balk i.p.v. alert(): een mislukte kaartlading
 * bij elke pan zou anders een reeks blokkerende pop-ups geven.
 */
function showError(e) {
  const toast = document.getElementById("error-toast");
  toast.textContent = (e && e.message) || t("error.unexpected");
  toast.hidden = false;
  clearTimeout(showError.handle);
  showError.handle = setTimeout(() => { toast.hidden = true; }, 8000);
}

// Centrale vangnet: elke niet-opgevangen fout (selectStop, loadStops, zoeken, dialogen, ...) wordt zichtbaar.
window.addEventListener("unhandledrejection", (event) => showError(event.reason));

/** Voor acties zonder eigen foutveld: fout als melding i.p.v. een onafgehandelde promise-rejection. */
async function guarded(action) {
  try {
    await action();
  } catch (e) {
    showError(e);
  }
}

// --- Auth ---------------------------------------------------------------

async function loadMe() {
  state.me = await apiFetch("/api/me");
  renderAuthArea();
  document.getElementById("toolbar").hidden = !state.me.curator;
  if (state.me.curator) refreshReviewCounts();
}

// --- StopPlaceType (NeTEx StopTypeEnumeration, chb.stop_place_type) -----

async function loadStopPlaceTypes() {
  state.stopPlaceTypes = await apiFetch("/api/stop-place-types");
}

// Vertaling primair uit de vaste STOP_TYPE_LABELS-lijst (i18n.js); enkel een code die daar niet
// in staat (bv. een toekomstige DB-uitbreiding) valt terug op de rauwe, Nederlandse API-label
// (chb.stop_place_type.label_nl) — zie i18n-scopekeuze in Claude/CLAUDE.md.
function stopTypeLabel(code) {
  if (!code) return t("stopType.none");
  return translatedStopTypeLabel(code) || state.stopPlaceTypes.find((opt) => opt.code === code)?.label || code;
}

// allowEmpty: "leeg = groeperingsknoop" is een geldige keuze (synthetische koepelknopen zonder
// eigen instapfunctie, zie ontwerpbeslissing 1 in Claude/CLAUDE.md).
function stopTypeOptionsHtml(selectedCode, allowEmpty) {
  const emptyOption = allowEmpty
    ? `<option value=""${!selectedCode ? " selected" : ""}>${escapeHtml(t("stopType.emptyOption"))}</option>`
    : "";
  const options = state.stopPlaceTypes.map((opt) => {
    const label = translatedStopTypeLabel(opt.code) || opt.label;
    return `<option value="${escapeHtml(opt.code)}"${opt.code === selectedCode ? " selected" : ""}>${escapeHtml(label)}</option>`;
  }).join("");
  return emptyOption + options;
}

function renderAuthArea() {
  const userInfo = document.getElementById("user-info");
  const loginLink = document.getElementById("login-link");
  const logoutForm = document.getElementById("logout-form");
  if (state.me.authenticated) {
    userInfo.hidden = false;
    userInfo.textContent = state.me.name || state.me.email || t("auth.signedIn");
    loginLink.hidden = true;
    logoutForm.hidden = false;
  } else {
    userInfo.hidden = true;
    loginLink.hidden = false;
    logoutForm.hidden = true;
  }
}

// Via de header en niet als formulierveld: de SPA-preset verwacht in een veld een gemaskeerd token,
// de cookie bevat het ruwe token (gaf een 403).
function wireLogout() {
  document.getElementById("logout-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const response = await fetch("/logout", {
      method: "POST",
      headers: { "X-XSRF-TOKEN": getCookie("XSRF-TOKEN") || "" },
      credentials: "same-origin",
    });
    if (response.ok) location.assign("/");
    else alert(t("auth.signOutFailed"));
  });
}

// --- Taal -----------------------------------------------------------------

function applyLangSwitchUi() {
  document.querySelectorAll(".lang-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.lang === getLang());
  });
}

// Herrendert alles wat al zichtbaar staat met tekst in de vorige taal. Een open dialoog wordt
// gewoon gesloten (die is altijd een kortstondige actie-flow, niet de moeite om te heropbouwen);
// het detailpaneel wordt herrenderd vanuit de laatst opgehaalde data (state.lastDetail/-History),
// dus zonder extra netwerkverkeer.
function refreshDynamicUiForLangChange() {
  applyLangSwitchUi();
  applyUnreviewedToggleUi();
  applyDisabledToggleUi();
  renderReviewCounts();
  if (state.group.active) updateGroupStatus();
  if (state.merge.active) renderMergeStatus();
  // Haltenamen hangen van de taal af: kaart en paneel opnieuw ophalen.
  loadStops();
  if (!document.getElementById("panel").hidden && state.lastDetail) {
    selectStop(state.lastDetail.id);
  }
  closeDialog();
}

function wireLangSwitch() {
  document.querySelectorAll(".lang-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (btn.dataset.lang === getLang()) return;
      setLang(btn.dataset.lang);
      applyStaticTranslations(document);
      refreshDynamicUiForLangChange();
    });
  });
}

// --- Kaart & haltes -------------------------------------------------------

const DEFAULT_VIEW = { lat: 50.85, lon: 4.35, zoom: 8 }; // centrum België
const VIEW_STORAGE_KEY = "chb-map-view";
const MAX_ZOOM = 19;
// Zoomniveau dat de kaart minstens aanneemt zodra een specifieke halte geopend wordt (zie
// selectStop) — diep genoeg om individuele haltes te kunnen onderscheiden.
const DETAIL_ZOOM = MAX_ZOOM - 3;

function parseViewFromHash() {
  // Formaat #zoom/lat/lon — dezelfde conventie als bv. openstreetmap.org, zodat de URL een
  // gewone, deelbare permalink is (build-instruction.md-uitbreiding: positie delen via URL).
  const match = location.hash.match(/^#(\d+)\/(-?\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)$/);
  if (!match) return null;
  return { zoom: Number(match[1]), lat: Number(match[2]), lon: Number(match[3]) };
}

function loadStoredView() {
  try {
    const raw = localStorage.getItem(VIEW_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return null;
  }
}

function initialView() {
  // De URL-hash is de primaire, deelbare bron. localStorage is enkel het vangnet voor de
  // Entra ID-login-redirect: die is een volledige serverside-redirect naar "/" (geen fragment
  // wordt ooit meegestuurd naar de server, dus die kan een hash sowieso niet doorgeven), dus
  // zonder dit vangnet zou de kaart na inloggen terugvallen op de standaardpositie.
  return parseViewFromHash() || loadStoredView() || DEFAULT_VIEW;
}

function persistView(map) {
  const center = map.getCenter();
  const view = {
    zoom: map.getZoom(),
    lat: Math.round(center.lat * 100000) / 100000,
    lon: Math.round(center.lng * 100000) / 100000,
  };
  // Via het URL-object i.p.v. een kale hash-string, zodat een bestaande querystring (bv.
  // ?unreviewed=1) behouden blijft in plaats van overschreven te worden.
  const url = new URL(location.href);
  url.hash = `${view.zoom}/${view.lat}/${view.lon}`;
  history.replaceState(null, "", url);
  try {
    localStorage.setItem(VIEW_STORAGE_KEY, JSON.stringify(view));
  } catch (e) { /* bv. privénavigatie zonder opslag toegestaan — dan geen vangnet, verder onschadelijk */ }
}

// --- "Enkel nog te bekijken"-kaarttoggle, ook persistent (zelfde aanpak als de kaartpositie) ---

const UNREVIEWED_TOGGLE_STORAGE_KEY = "chb-map-unreviewed-only";

function initialUnreviewedToggle() {
  const url = new URL(location.href);
  if (url.searchParams.has("unreviewed")) {
    return url.searchParams.get("unreviewed") === "1";
  }
  try {
    return localStorage.getItem(UNREVIEWED_TOGGLE_STORAGE_KEY) === "1";
  } catch (e) {
    return false;
  }
}

function persistUnreviewedToggle(value) {
  const url = new URL(location.href);
  if (value) {
    url.searchParams.set("unreviewed", "1");
  } else {
    url.searchParams.delete("unreviewed");
  }
  history.replaceState(null, "", url);
  try {
    localStorage.setItem(UNREVIEWED_TOGGLE_STORAGE_KEY, value ? "1" : "0");
  } catch (e) { /* geen vangnet, verder onschadelijk */ }
}

// --- "Toon uitgeschakelde haltes"-toggle, zelfde persistentie-aanpak als hierboven ---
// disabled_at wordt volledig server-side (import-repo) bijgehouden, incl. groepscascade (een
// parent wordt pas disabled zodra al haar kinderen dat zijn) — deze frontend leest het enkel,
// er wordt hier geen cascade-logica herbouwd.

const DISABLED_TOGGLE_STORAGE_KEY = "chb-map-show-disabled";

function initialDisabledToggle() {
  const url = new URL(location.href);
  if (url.searchParams.has("showDisabled")) {
    return url.searchParams.get("showDisabled") === "1";
  }
  try {
    return localStorage.getItem(DISABLED_TOGGLE_STORAGE_KEY) === "1";
  } catch (e) {
    return false;
  }
}

function persistDisabledToggle(value) {
  const url = new URL(location.href);
  if (value) {
    url.searchParams.set("showDisabled", "1");
  } else {
    url.searchParams.delete("showDisabled");
  }
  history.replaceState(null, "", url);
  try {
    localStorage.setItem(DISABLED_TOGGLE_STORAGE_KEY, value ? "1" : "0");
  } catch (e) { /* geen vangnet, verder onschadelijk */ }
}

function initMap() {
  const view = initialView();
  state.map = L.map("map").setView([view.lat, view.lon], view.zoom);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: t("map.attribution"),
    maxZoom: MAX_ZOOM,
  }).addTo(state.map);
  // ~50K haltes: individuele markers groeperen tot bolletjes-met-aantal die opsplitsen naarmate
  // je inzoomt (chunkedLoading zodat het initieel toevoegen de UI niet blokkeert).
  //
  // Bewust GEEN disableClusteringAtZoom (vroeger MAX_ZOOM - 3): dat schakelde clustering op
  // dat niveau volledig uit, dus ook voor haltes die *exact* op dezelfde coördinaat liggen (bv.
  // twee gegroepeerde stopplaatsen, of gewoon twee bronfeeds die nog niet samengevoegd zijn).
  // Zulke markers vielen dan als losse Leaflet-iconen precies over elkaar — enkel de bovenste
  // (laatst toegevoegde) kon nog aangeklikt worden, de rest was onbereikbaar. In plaats daarvan
  // blijft de clustergroep overal actief, maar met een `maxClusterRadius` die vanaf DETAIL_ZOOM
  // instort tot een paar pixels: enkel haltes die (zo goed als) exact overlappen clusteren dan
  // nog samen, elke andere blijft los zichtbaar zoals voorheen. Een klik op zo'n overlap-cluster
  // zoomt niet oneindig door (de bounds zijn al minimaal) maar waaiert de markers uiteen
  // (spiderfy) zodat elke onderliggende halte weer apart aanklikbaar is.
  state.markerLayer = L.markerClusterGroup({
    chunkedLoading: true,
    maxClusterRadius: (zoom) => (zoom >= DETAIL_ZOOM ? 3 : 80),
    spiderfyOnMaxZoom: true,
  }).addTo(state.map);
  // Vlakken van gegroepeerde StopPlaces (onder de quay-stippen); aanklikbaar = de groep selecteren.
  state.areaLayer = L.layerGroup().addTo(state.map);
  state.map.on("moveend", () => persistView(state.map));
  // Marker-klikken stoppen hun eigen propagatie (Leaflet's standaardgedrag voor interactieve
  // layers), dus dit vuurt enkel bij een klik op de kaart zelf, niet bij het selecteren van een
  // halte — geen extra guard nodig.
  state.map.on("click", () => {
    if (!document.getElementById("panel").hidden) {
      closePanel();
    }
  });
}

// De haltes worden nu per viewport opgehaald (zie loadStops), dus na pannen/zoomen moet er
// opnieuw geladen worden. Apart van de persistView-listener in initMap zodat init() dit pas
// aankoppelt ná de eerste expliciete loadStops()-aanroep — anders vuurt setView() bij het
// aanmaken van de kaart zelf al een (overbodige) eerste moveend/reload vóór loadMe() klaar is.
// Debounce omdat een sleepbeweging of muiswiel-zoom meerdere moveend's kort na elkaar geeft.
function wireMapReloadOnMove() {
  let reloadHandle = null;
  state.map.on("moveend", () => {
    clearTimeout(reloadHandle);
    reloadHandle = setTimeout(loadStops, 200);
  });
}

// Categorische kleuren uit het gevalideerde standaardpalet (sloten 1 en 2, gegarandeerd
// onderscheidbaar bij kleurenblindheid) i.p.v. de vorige laagverzadigde blauw/oker-tint die
// wegviel tegen de OSM-ondergrond. Een witte rand (i.p.v. een donkerdere tint van dezelfde kleur)
// zorgt dat de stip blijft opvallen ongeacht wat eronder ligt (beige land, groen park, blauw water).
function stopName(props) {
  return props.name || t("map.stopFallbackName", { id: props.id });
}

// Twee quays op dezelfde plek (De Lijn bus + tram, ndov#76) hebben dezelfde naam: in de keuzetekst staat
// geen icoon, dus daar onderscheidt de modus ze. Niet in de tooltip: daar staat de echte naam, de marker toont het icoon.
function pickLabel(id) {
  const props = state.stopsById.get(id);
  if (!props) return id;
  return props.isQuay && props.transportMode ? `${stopName(props)} (${props.transportMode})` : stopName(props);
}

function stopIcon(props) {
  // Oranje = curated groepering; een wrapper is technisch ook een StopPlace maar visueel gewoon één halte (quay) = blauw.
  const color = props.isSynthetic && !props.isWrapper && !props.isQuay ? "#eb6834" : "#2a78d6";
  const className = props.disabledAt ? "stop-marker stop-marker-disabled" : "stop-marker";
  return L.divIcon({
    className,
    html: `<span style="background:${color}">${modeIcon(props.transportMode, 12, 2.5)}</span>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  });
}

// Een trage eerdere aanvraag (snel pannen) mag het nieuwere beeld niet overschrijven (issue #17).
let loadStopsGeneration = 0;

async function loadStops() {
  // curator-check hier ook: een gedeelde link met ?unreviewed=1 mag voor een niet-curator (of
  // nog niet ingelogde bezoeker) niet op een 403 van het curator-only endpoint stuiten — die
  // valt gewoon terug op de volledige kaart.
  const useUnreviewed = state.showOnlyUnreviewed && state.me.curator;
  const path = useUnreviewed ? "/api/review/unreviewed-map" : "/api/stops";
  // Enkel het huidige (licht opgevulde) viewport opvragen i.p.v. steeds de volledige ~50K-tabel
  // (zie StopViewerRepository) — de padding voorkomt dat een kleine pan meteen een nieuwe
  // bevraging vergt terwijl de rand nog net in beeld komt.
  const bounds = state.map.getBounds().pad(0.2);
  const params = new URLSearchParams({
    minLon: bounds.getWest(),
    minLat: bounds.getSouth(),
    maxLon: bounds.getEast(),
    maxLat: bounds.getNorth(),
  });
  // Server filtert disabled_at IS NOT NULL-haltes standaard al weg (StopViewerRepository) — de
  // toggle vraagt gewoon de ongefilterde set op i.p.v. hier client-side te filteren.
  if (state.showDisabled) params.set("showDisabled", "1");
  const mine = ++loadStopsGeneration;
  const collection = await apiFetch(path + "?" + params);
  if (mine !== loadStopsGeneration) return;
  document.getElementById("map-hint").hidden = !collection.truncated;
  state.stopsById.clear();
  state.markerLayer.clearLayers();
  state.areaLayer.clearLayers();
  state.markersById.clear();
  const markers = [];
  for (const feature of collection.features) {
    const [lon, lat] = feature.geometry.coordinates;
    const props = feature.properties;
    state.stopsById.set(props.id, props);
    if (feature.geometry.type === "Polygon") {
      const area = L.polygon(
        feature.geometry.coordinates[0].map(([x, y]) => [y, x]),
        { color: "#eb6834", weight: 2, fillOpacity: 0.15, bubblingMouseEvents: false },
      );
      area.bindTooltip(escapeHtml(stopName(props))); // Leaflet zet een string als innerHTML
      area.on("click", () => onMarkerClick(props.id));
      area.addTo(state.areaLayer);
      state.markersById.set(props.id, area);
      continue;
    }
    const marker = L.marker([lat, lon], { icon: stopIcon(props), draggable: false }); // pas versleepbaar na "Locatie verplaatsen" in het paneel
    if (state.me.curator) {
      marker.on("dragend", async () => {
        const { lat: newLat, lng: newLon } = marker.getLatLng();
        try {
          await apiFetch("/api/stops/" + props.id + "/location", {
            method: "POST",
            body: JSON.stringify({ lat: newLat, lon: newLon }),
          });
          if (state.lastDetail && state.lastDetail.id === props.id) await selectStop(props.id);
        } catch (e) {
          showError(e);
        }
        await loadStops(); // bij een fout springt de marker terug, bij succes staat hij op de nieuwe plek
      });
    }
    const label = props.publicCode ? `${stopName(props)} (${props.publicCode})` : stopName(props);
    const tooltip = props.disabledAt
      ? `${label} — ${t("map.disabledSince", { date: formatTimestamp(props.disabledAt) })}`
      : label;
    marker.bindTooltip(escapeHtml(tooltip));
    marker.on("click", () => onMarkerClick(props.id));
    markers.push(marker);
    state.markersById.set(props.id, marker);
  }
  state.markerLayer.addLayers(markers); // bulk toevoegen i.p.v. één voor één, veel sneller op 50K
}

function onMarkerClick(id) {
  if (state.pickCallback) {
    const callback = state.pickCallback;
    state.pickCallback = null;
    callback(id);
    return;
  }
  if (state.group.active) {
    handleGroupPick(id);
    return;
  }
  if (state.merge.active) {
    handleMergePick(id);
    return;
  }
  selectStop(id);
}

// Geeft terug of er een lokale marker was om naartoe te pannen — sinds haltes per viewport
// geladen worden (zie loadStops) staat een niet-zichtbare halte (bv. via zoeken, of een kind in
// het detailpaneel buiten beeld) hier niet meer per se in, zie de fallback in selectStop.
function panTo(id) {
  const marker = state.markersById.get(id);
  if (marker && marker.getBounds) { // vlak van een groep: er is geen marker om uit een cluster te halen
    state.map.fitBounds(marker.getBounds(), { maxZoom: MAX_ZOOM - 1 });
    return true;
  }
  if (marker) {
    // zoomToShowLayer zoomt/pant net ver genoeg in tot de marker niet meer in een cluster
    // opgeslorpt zit, i.p.v. enkel pannen naar een punt dat dan nog steeds onzichtbaar is.
    state.markerLayer.zoomToShowLayer(marker, () => {});
    return true;
  }
  return false;
}

// --- Zoeken ---------------------------------------------------------------

function wireSearch() {
  const input = document.getElementById("search");
  const results = document.getElementById("search-results");
  let debounceHandle = null;
  let generation = 0;

  input.addEventListener("input", () => {
    clearTimeout(debounceHandle);
    const mine = ++generation; // ook bij leeg veld: een lopende zoekopdracht mag de lijst niet meer vullen
    const query = input.value.trim();
    if (!query) {
      results.innerHTML = "";
      return;
    }
    debounceHandle = setTimeout(async () => {
      const matches = await apiFetch("/api/search?q=" + encodeURIComponent(query));
      if (mine !== generation) return;
      results.innerHTML = "";
      for (const match of matches) {
        const li = document.createElement("li");
        li.innerHTML = escapeHtml(match.path)
          + (match.sourceCodes ? ` <span class="search-result-code">${escapeHtml(match.sourceCodes)}</span>` : "");
        li.addEventListener("click", () => {
          results.innerHTML = "";
          input.value = "";
          if (state.pickCallback) {
            const callback = state.pickCallback;
            state.pickCallback = null;
            callback(match.id, match.path);
          } else if (state.group.active) {
            handleGroupPick(match.id);
          } else if (state.merge.active) {
            handleMergePick(match.id);
          } else {
            selectStop(match.id);
          }
        });
        results.appendChild(li);
      }
    }, 250);
  });

  document.addEventListener("click", (event) => {
    if (!document.getElementById("search-box").contains(event.target)) {
      results.innerHTML = "";
    }
  });
}

// --- Detailpaneel -----------------------------------------------------------

function closePanel() {
  document.getElementById("panel").hidden = true;
  state.selectedStopId = null;
}

async function selectStop(id) {
  state.selectedStopId = id;
  const wasVisible = panTo(id);
  // History parallel met detail starten (twee tunnel-round-trips die niet van elkaar afhangen).
  const historyPromise = state.me.authenticated
    ? apiFetch("/api/stops/" + id + "/history").catch(() => null)
    : Promise.resolve(null);
  const detail = await apiFetch("/api/stops/" + id);
  if (!wasVisible && detail.lat != null && detail.lon != null) {
    // Geen lokale marker om naartoe te zoomen (halte lag buiten het huidige viewport) — pan
    // rechtstreeks op de net opgehaalde coördinaten; de moveend-listener (wireMapReloadOnMove)
    // laadt vanzelf de haltes voor de nieuwe positie.
    state.map.setView([detail.lat, detail.lon], Math.max(state.map.getZoom(), DETAIL_ZOOM));
  }
  const history = await historyPromise;
  // Bewaard zodat een taalwissel het paneel kan herrenderen zonder opnieuw te bevragen — zie
  // refreshDynamicUiForLangChange().
  state.lastDetail = detail;
  state.lastHistory = history;
  renderPanel(detail, history);
  document.getElementById("panel").hidden = false;
}

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  // innerHTML laat aanhalingstekens staan, en de uitvoer komt ook in attributen terecht.
  return div.innerHTML.replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

// chb.mode_dominance_rank (ndov V98); Business Rules CHB §7: het type van een general volgt de dominante modus.
const MODE_DOMINANCE = ["rail", "metro", "tram", "trolleyBus", "bus", "coach", "water", "cableway", "funicular", "air"];
// chb.mode_to_poorest_stop_type; geen type voor coach/water/... (daar oordeelt de database ook niet).
const MODE_TO_STOP_TYPE = { rail: "railStation", metro: "metroStation", tram: "onstreetTram", trolleyBus: "onstreetBus", bus: "onstreetBus" };

function dominantMode(modes) {
  const rank = (m) => (MODE_DOMINANCE.includes(m) ? MODE_DOMINANCE.indexOf(m) : 99);
  return [...new Set(modes.filter(Boolean))].sort((a, b) => rank(a) - rank(b))[0] || null;
}

function modesText(d) {
  if (!d.transportMode) return "-";
  return d.otherTransportModes?.length ? `${d.transportMode} (+ ${d.otherTransportModes.join(", ")})` : d.transportMode;
}

const MODE_ICONS = {
  bus: '<rect x="5" y="4" width="14" height="14" rx="2"/><path d="M5 11h14M8 18v2M16 18v2"/><circle cx="8.5" cy="14.5" r=".5"/><circle cx="15.5" cy="14.5" r=".5"/>',
  tram: '<rect x="6" y="7" width="12" height="11" rx="2"/><path d="M6 12h12M8 21l2-3M16 21l-2-3M9 3h6M12 3v4"/>',
  metro: '<circle cx="12" cy="12" r="9"/><path d="M8 16V8l4 5 4-5v8"/>',
  rail: '<path d="M8 3v18M16 3v18M5 7h14M5 12h14M5 17h14"/>',
};

function modeIcon(mode, size, strokeWidth) {
  const icon = MODE_ICONS[mode];
  return icon ? `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icon}</svg>` : "";
}

function modeBadge(mode) {
  return `<span class="badge badge-mode${MODE_ICONS[mode] ? " badge-mode-" + mode : ""}">${modeIcon(mode, 14, 2)}${escapeHtml(mode)}</span>`;
}

function modeBadges(d) {
  if (!d.transportMode) return `<span class="badge badge-mode-none">${escapeHtml(t("group.noMode"))}</span>`;
  return [d.transportMode, ...(d.otherTransportModes || [])].map(modeBadge).join(" ");
}

function autoGroupBadge() {
  return `<span class="badge badge-auto" title="${escapeHtml(t("panel.autoGroupHint"))}">${escapeHtml(t("panel.autoGroupBadge"))}</span>`;
}

function kindBadge(kind) {
  return kind ? ` <span class="badge badge-synthetic">${escapeHtml(t("kind." + kind))}</span>` : "";
}

function renderPanel(detail, history) {
  const content = document.getElementById("panel-content");
  const parentLine = detail.path ? escapeHtml(detail.path) : t("panel.noPath");
  const sourcesLine = detail.sources.length
    ? detail.sources.map((s) => escapeHtml(`${s.sourceSchema}:${s.sourceId} (${s.sourceName})`)).join(", ")
    : t("panel.syntheticNoSource");

  const duplicates = duplicateQuaysAmong(detail.children);
  const childrenSection = detail.children.length ? `
    <section>
      <h3>${escapeHtml(t("panel.childrenHeading", { count: detail.children.length }))}</h3>
      <p class="hint-text">${escapeHtml(t("panel.childrenHint"))}</p>
      <table><tbody>
        ${detail.children.map((c) => `
          <tr class="child-row${c.disabledAt ? " child-row-disabled" : ""}" data-id="${c.id}" style="cursor:pointer"${c.disabledAt ? ` title="${escapeHtml(t("panel.disabledSince", { date: formatTimestamp(c.disabledAt) }))}"` : ""}>
            <td>${escapeHtml(c.displayName)}${c.isQuay ? ` <span class="badge badge-physical">${escapeHtml(t("panel.quayBadge"))}</span>` : kindBadge(c.kind)}${c.disabledAt ? ` <span class="badge badge-disabled">${escapeHtml(t("panel.disabledBadge"))}</span>` : ""}${duplicateBadge(c, duplicates.get(c.id))}</td>
            <td>${escapeHtml(c.publicCode || "")}</td>
            <td class="hint-text">${escapeHtml(modesText(c))}</td>
          </tr>`).join("")}
      </tbody></table>
    </section>` : "";

  const reviewSection = state.me.curator ? `
    <section>
      <h3>${escapeHtml(t("panel.reviewHeading"))}</h3>
      ${detail.reviewedAt
        ? `<p>${escapeHtml(t("panel.reviewedOn", { date: formatTimestamp(detail.reviewedAt) }))}</p>
           <button class="secondary" id="unreview-btn">${escapeHtml(t("panel.reopen"))}</button>`
        : `<p class="hint-text">${escapeHtml(t("panel.notReviewedYet"))}</p>
           <button id="review-btn">${escapeHtml(t("panel.markReviewed"))}</button>`}
    </section>` : "";

  const canDelete = detail.isSynthetic && !detail.isWrapper && detail.children.length === 0 && detail.sources.length === 0;
  const deleteSection = !state.me.curator ? "" : detail.deletedAt ? `
    <section>
      <h3>${escapeHtml(t("panel.deletedHeading"))}</h3>
      <p>${escapeHtml(t("panel.deletedText", { date: formatTimestamp(detail.deletedAt) }))}</p>
      <button class="secondary" id="restore-btn">${escapeHtml(t("panel.restore"))}</button>
    </section>` : canDelete ? `
    <section>
      <h3>${escapeHtml(t("panel.manageHeading"))}</h3>
      <button class="danger" id="delete-btn">${escapeHtml(t("panel.deleteNode"))}</button>
    </section>` : "";

  // Ook een quay (stip binnen het vlak van zijn groep) is versleepbaar; chb.set_location/reset_location kennen het quay-niveau.
  const moveSection = state.me.curator ? `
    <section>
      <h3>${escapeHtml(t("panel.locationHeading"))}</h3>
      <p class="hint-text">${escapeHtml(t("panel.moveHint"))}</p>
      <button class="secondary" id="move-location-btn">${escapeHtml(t("panel.moveLocation"))}</button>
      ${detail.isWrapper || detail.isQuay ? `<button class="secondary" id="reset-location-btn">${escapeHtml(t("panel.resetLocation"))}</button>` : ""}
    </section>` : "";

  const recomputeSection = state.me.curator && detail.isSynthetic && !detail.isWrapper && detail.children.length ? `
    <section>
      <h3>${escapeHtml(t("panel.locationHeading"))}</h3>
      <p class="hint-text">${escapeHtml(t("panel.recomputeHint"))}</p>
      <button class="secondary" id="recompute-location-btn">${escapeHtml(t("panel.recomputeLocation"))}</button>
    </section>` : "";

  const wrapperSection = state.me.curator && detail.isWrapper ? `
    <section>
      <h3>${escapeHtml(t("panel.wrapperHeading"))} ${helpLink("general")}</h3>
      <p class="hint-text">${escapeHtml(t("panel.wrapperHint"))}</p>
      <button class="secondary" id="promote-btn">${escapeHtml(t("panel.promote"))}</button>
    </section>` : "";

  const typeBadge = `<span class="badge ${detail.isSynthetic ? "badge-synthetic" : "badge-physical"}">${escapeHtml(stopTypeLabel(detail.stopType))}</span>`;
  const autoBadge = detail.sourceStation ? autoGroupBadge() : "";
  const reviewBadge = detail.reviewedAt ? `<span class="badge badge-reviewed">${escapeHtml(t("panel.reviewedBadge"))}</span>` : "";
  const disabledBadge = detail.disabledAt
    ? `<span class="badge badge-disabled" title="${escapeHtml(t("panel.disabledSince", { date: formatTimestamp(detail.disabledAt) }))}">${escapeHtml(t("panel.disabledBadge"))}</span>`
    : "";

  let html = `
    <h2>${escapeHtml(detail.displayName)}</h2>
    <div class="path">${parentLine}</div>
    <div class="badge-row">${typeBadge}${kindBadge(detail.kind)}${autoBadge}${reviewBadge}${disabledBadge}</div>
    ${detail.kind === "general" && !detail.transportMode ? `<p class="error-text">${escapeHtml(t("panel.generalNoMode"))}</p>` : ""}
    ${detail.kind === "general" && detail.transportMode && !detail.otherTransportModes?.length ? `<p class="error-text">${escapeHtml(t("panel.generalSingleMode"))} ${helpLink("general")}</p>` : ""}
    <dl>
      <dt>${escapeHtml(t("panel.transportMode"))}</dt><dd>${escapeHtml(modesText(detail))}</dd>
      <dt>${escapeHtml(t("panel.publicCode"))}</dt><dd>${escapeHtml(detail.publicCode || "-")}</dd>
      <dt>${escapeHtml(t("panel.origin"))}</dt><dd>${sourcesLine}</dd>
      ${detail.sourceStation ? `<dt>${escapeHtml(t("panel.sourceStation"))}</dt><dd>${escapeHtml(detail.sourceStation)}</dd>` : ""}
      ${detail.firstSeenAt ? `<dt>${escapeHtml(t("panel.firstSeen"))}</dt><dd>${escapeHtml(formatTimestamp(detail.firstSeenAt))}</dd>` : ""}
      ${detail.lastSeenAt ? `<dt>${escapeHtml(t("panel.lastSeen"))}</dt><dd>${escapeHtml(formatTimestamp(detail.lastSeenAt))}</dd>` : ""}
      <dt>${escapeHtml(t("panel.synthetic"))}</dt><dd>${detail.isSynthetic ? escapeHtml(t("common.yes")) : escapeHtml(t("common.no"))}</dd>
    </dl>
    ${renderNamesSection(detail)}
    ${reviewSection}
    ${wrapperSection}
    ${moveSection}
    ${recomputeSection}
    ${childrenSection}
    <section>
      <h3>${escapeHtml(t("panel.attributesHeading"))}</h3>
      <table id="attr-table"><tbody>
        ${Object.entries(detail.attributes).map(([k, v]) => attributeRow(detail.id, k, v)).join("")}
      </tbody></table>
    </section>
    ${deleteSection}
  `;

  if (state.me.curator) {
    html += renderEditSections(detail);
  }
  if (state.me.authenticated) {
    html += renderHistorySection(history);
  }

  content.innerHTML = html;
  wirePanelActions(detail);
}

// Alle talen uit chb.quay_names/stop_names, de default van het taalgebied en (curator) waarom die afwijkt.
// Bewerken: attribuut name_<taal> hieronder (chb.set_attribute); de default volgt vanzelf.
function renderNamesSection(detail) {
  const langs = Object.entries(detail.names || {});
  if (!langs.length && !detail.defaultName) return "";
  return `
    <section>
      <h3>${escapeHtml(t("panel.namesHeading"))} ${helpLink("names")}</h3>
      <dl>
        ${langs.map(([lang, name]) => `<dt>${escapeHtml(lang)}</dt><dd>${escapeHtml(name)}</dd>`).join("")}
        <dt>${escapeHtml(t("panel.defaultName"))}</dt>
        <dd>${escapeHtml(detail.defaultName || "-")}${detail.defaultLang ? ` <span class="hint-text">(${escapeHtml(detail.defaultLang)})</span>` : ""}</dd>
        ${state.me.curator && detail.nameReason ? `<dt>${escapeHtml(t("panel.nameReason"))}</dt><dd>${escapeHtml(detail.nameReason)}</dd>` : ""}
      </dl>
      ${state.me.curator ? `<p class="hint-text">${escapeHtml(t("panel.namesEditHint"))}</p>` : ""}
    </section>`;
}

function attributeRow(stopId, key, value) {
  return `
    <tr data-key="${escapeHtml(key)}">
      <td>${escapeHtml(key)}</td>
      <td>${escapeHtml(value)}</td>
      <td>${state.me.curator
        ? `<button class="secondary attr-edit" data-key="${escapeHtml(key)}" data-value="${escapeHtml(value)}">${escapeHtml(t("panel.attrEdit"))}</button>
           <button class="secondary attr-delete" data-key="${escapeHtml(key)}">${escapeHtml(t("panel.attrDelete"))}</button>`
        : ""}</td>
    </tr>`;
}

function renderEditSections(detail) {
  // Een wrapper is een afgeleide schil van zijn quay: naam en code zijn daar niet te wijzigen
  // (de database weigert het met UC003 + een verwijzing naar de quay), dus niet eens aanbieden.
  const locked = detail.isWrapper ? " readonly" : "";
  // stop_type hoort bij de StopPlace en is verplicht; een quay heeft er zelf geen (die stuurt null = geen wijziging).
  const typeField = detail.isSynthetic ? `
        <label>${escapeHtml(t("stopType.label"))} ${helpLink("stopType")}
          <select name="stopType">${stopTypeOptionsHtml(detail.stopType, false)}</select>
        </label>` : "";
  return `
    <section>
      <h3>${escapeHtml(t("panel.editHeading"))}</h3>
      <form class="inline-form" id="edit-form">
        <label title="${escapeHtml(t("panel.internalNameHint"))}">${escapeHtml(t("panel.internalName"))} ${helpLink("names")}
          <input name="name" value="${escapeHtml(detail.name)}" required${locked}>
        </label>${typeField}
        <label>${escapeHtml(t("panel.publicCode"))} ${helpLink("publicCode")}
          <input name="publicCode" value="${escapeHtml(detail.publicCode || "")}"${locked}>
        </label>
        <label${detail.isQuay ? ` title="${escapeHtml(t("panel.moveIsNotMerge"))}"` : ""}>${escapeHtml(t("panel.parentIdLabel"))}
          <input name="parentId" type="number" value="${detail.parentId != null ? detail.parentId : ""}">
        </label>
        ${detail.isQuay ? `<p class="hint-text">${escapeHtml(t("panel.moveIsNotMerge"))}</p>` : ""}
        <button type="button" class="secondary" id="pick-parent">${escapeHtml(t("panel.pickParent"))}</button>
        <button type="submit">${escapeHtml(t("common.save"))}</button>
        <span class="error-text" id="edit-error"></span>
      </form>
    </section>
    <section>
      <h3>${escapeHtml(t("panel.attrAddHeading"))}</h3>
      <form class="inline-form" id="attr-form">
        <label>${escapeHtml(t("common.key"))}
          <input name="key" required>
        </label>
        <label>${escapeHtml(t("common.value"))}
          <input name="value" required>
        </label>
        <button type="submit">${escapeHtml(t("common.save"))}</button>
        <span class="error-text" id="attr-error"></span>
      </form>
    </section>
  `;
}

function formatTimestamp(iso) {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString(getLocale());
}

function renderHistorySection(history) {
  if (!history) {
    return `<section><h3>${escapeHtml(t("panel.historyHeading"))}</h3><p class="hint-text">${escapeHtml(t("panel.historyUnavailable"))}</p></section>`;
  }
  if (history.length === 0) {
    return `<section><h3>${escapeHtml(t("panel.historyHeading"))}</h3><p class="hint-text">${escapeHtml(t("panel.historyEmpty"))}</p></section>`;
  }
  const rows = history.map((entry) => `
    <tr>
      <td>${escapeHtml(formatTimestamp(entry.changedAt))}</td>
      <td>${escapeHtml(entry.changedBy)}</td>
      <td>${escapeHtml(entry.changeType)}</td>
      <td>${escapeHtml(entry.oldValue)} &rarr; ${escapeHtml(entry.newValue)}</td>
      <td>${escapeHtml(entry.note)}</td>
    </tr>`).join("");
  return `
    <section>
      <h3>${escapeHtml(t("panel.historyHeading"))}</h3>
      <table>
        <thead><tr><th>${escapeHtml(t("panel.historyWhen"))}</th><th>${escapeHtml(t("panel.historyWho"))}</th><th>${escapeHtml(t("panel.historyType"))}</th><th>${escapeHtml(t("panel.historyChange"))}</th><th>${escapeHtml(t("panel.historyNote"))}</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </section>`;
}

function wirePanelActions(detail) {
  document.getElementById("panel-close").onclick = closePanel;

  document.querySelectorAll(".child-row").forEach((row) => {
    row.addEventListener("click", () => selectStop(Number(row.dataset.id)));
  });
  document.querySelectorAll(".duplicate-merge-btn").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.stopPropagation(); // niet ook de rij openen
      confirmMerge(Number(btn.dataset.source), Number(btn.dataset.target)).catch(console.error);
    });
  });

  const reviewBtn = document.getElementById("review-btn");
  if (reviewBtn) {
    reviewBtn.addEventListener("click", () => guarded(async () => {
      await apiFetch("/api/stops/" + detail.id + "/review", { method: "POST" });
      await selectStop(detail.id);
      await loadStops(); // laat de halte meteen verdwijnen als de "enkel nog te bekijken"-kaart actief is
    }));
  }
  const unreviewBtn = document.getElementById("unreview-btn");
  if (unreviewBtn) {
    unreviewBtn.addEventListener("click", () => guarded(async () => {
      await apiFetch("/api/stops/" + detail.id + "/review", { method: "DELETE" });
      await selectStop(detail.id);
      await loadStops();
    }));
  }

  const moveBtn = document.getElementById("move-location-btn");
  if (moveBtn) {
    moveBtn.addEventListener("click", () => {
      const marker = state.markersById.get(detail.id);
      if (!marker || !marker.dragging) return alert(t("panel.moveNoMarker"));
      state.markerLayer.zoomToShowLayer(marker, () => {
        marker.dragging.enable();
        moveBtn.disabled = true;
        moveBtn.textContent = t("panel.moveActive");
      });
    });
  }

  const resetBtn = document.getElementById("reset-location-btn");
  if (resetBtn) {
    resetBtn.addEventListener("click", async () => {
      if (!confirm(t("panel.resetLocationConfirm"))) return;
      try {
        await apiFetch("/api/stops/" + detail.id + "/reset-location", { method: "POST" });
        await selectStop(detail.id);
        await loadStops();
      } catch (e) {
        showError(e);
      }
    });
  }

  const recomputeBtn = document.getElementById("recompute-location-btn");
  if (recomputeBtn) {
    recomputeBtn.addEventListener("click", async () => {
      try {
        await apiFetch("/api/stops/" + detail.id + "/recompute-location", { method: "POST" });
        await selectStop(detail.id);
        await loadStops(); // marker verplaatst zich mee op de kaart
      } catch (e) {
        showError(e);
      }
    });
  }

  const deleteBtn = document.getElementById("delete-btn");
  if (deleteBtn) {
    deleteBtn.addEventListener("click", async () => {
      if (!confirm(t("panel.deleteConfirm", { name: detail.displayName }))) return;
      try {
        await apiFetch("/api/stops/" + detail.id, { method: "DELETE" });
        closePanel();
        await loadStops();
      } catch (e) {
        showError(e);
      }
    });
  }
  const restoreBtn = document.getElementById("restore-btn");
  if (restoreBtn) {
    restoreBtn.addEventListener("click", () => guarded(async () => {
      await apiFetch("/api/stops/" + detail.id + "/restore", { method: "POST" });
      await selectStop(detail.id);
      await loadStops();
    }));
  }

  document.querySelectorAll(".attr-delete").forEach((btn) => {
    btn.addEventListener("click", () => guarded(async () => {
      if (!confirm(t("panel.attrDeleteConfirm", { key: btn.dataset.key }))) return;
      await apiFetch("/api/stops/" + detail.id + "/attributes/" + encodeURIComponent(btn.dataset.key), { method: "DELETE" });
      await selectStop(detail.id);
    }));
  });

  const editForm = document.getElementById("edit-form");
  if (editForm) {
    document.getElementById("pick-parent").addEventListener("click", () => {
      startPicking((id, label) => {
        editForm.elements.parentId.value = id;
      });
    });
    editForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const errorEl = document.getElementById("edit-error");
      errorEl.textContent = "";
      const parentIdRaw = editForm.elements.parentId.value;
      const parentId = parentIdRaw === "" ? null : Number(parentIdRaw);
      if (parentId != null) {
        const cycleError = checkForCycle(detail.id, parentId);
        if (cycleError) {
          errorEl.textContent = cycleError;
          return;
        }
      }
      const body = {
        name: editForm.elements.name.value,
        stopType: editForm.elements.stopType ? editForm.elements.stopType.value || null : null,
        publicCode: editForm.elements.publicCode.value || null,
        parentId,
      };
      const save = async () => {
        await apiFetch("/api/stops/" + detail.id, { method: "PATCH", body: JSON.stringify(body) });
        await loadStops();
        await selectStop(detail.id);
      };
      try {
        const twin = detail.isQuay && parentId != null && parentId !== detail.parentId
          ? await findTwinQuay(detail, parentId) : null;
        if (twin) confirmMoveOntoTwin(detail, twin, save);
        else await save();
      } catch (e) {
        showActionError(errorEl, e);
      }
    });
  }

  const attrForm = document.getElementById("attr-form");
  if (attrForm) {
    // Wijzigen = hetzelfde upsert-formulier, vooraf ingevuld.
    document.querySelectorAll(".attr-edit").forEach((btn) => {
      btn.addEventListener("click", () => {
        attrForm.elements.key.value = btn.dataset.key;
        attrForm.elements.value.value = btn.dataset.value;
        attrForm.scrollIntoView({ block: "nearest" });
        attrForm.elements.value.focus();
        attrForm.elements.value.select();
      });
    });
    attrForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const errorEl = document.getElementById("attr-error");
      errorEl.textContent = "";
      const key = attrForm.elements.key.value;
      const value = attrForm.elements.value.value;
      try {
        await apiFetch("/api/stops/" + detail.id + "/attributes/" + encodeURIComponent(key), {
          method: "PUT",
          body: JSON.stringify({ value }),
        });
        await selectStop(detail.id);
      } catch (e) {
        showActionError(errorEl, e);
      }
    });
  }

  const promoteBtn = document.getElementById("promote-btn");
  if (promoteBtn) {
    promoteBtn.addEventListener("click", () => openPromoteDialog(detail));
  }
}

/**
 * Wrapper promoveren: de StopPlace krijgt een eigen (verplichte) naam en volgt zijn quay daarna niet
 * meer (chb.promote_wrapper). Terugzetten kan niet stilzwijgend, dus eerst een expliciete bevestiging.
 */
function openPromoteDialog(detail) {
  showDialog(`
    <h2>${escapeHtml(t("promote.heading"))}</h2>
    <p>${escapeHtml(t("promote.explain"))}</p>
    <form class="inline-form" id="promote-form">
      <label>${escapeHtml(t("promote.nameLabel"))}
        <input name="name" value="${escapeHtml(detail.displayName)}" required>
      </label>
      <span class="error-text" id="promote-error"></span>
      <div class="dialog-actions">
        <button type="button" class="secondary" id="promote-cancel">${escapeHtml(t("common.cancel"))}</button>
        <button type="submit">${escapeHtml(t("promote.submit"))}</button>
      </div>
    </form>
  `);
  document.getElementById("promote-cancel").addEventListener("click", closeDialog);
  document.getElementById("promote-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      await apiFetch("/api/stops/" + detail.id + "/promote-wrapper", {
        method: "POST",
        body: JSON.stringify({ name: event.target.elements.name.value }),
      });
      closeDialog();
      await loadStops();
      await selectStop(detail.id);
    } catch (e) {
      showActionError(document.getElementById("promote-error"), e);
    }
  });
}

/**
 * Client-side cirkel-preventie (build-instruction.md §3.2): loopt van de voorgestelde parent
 * omhoog naar de wortel. Komt movingId daarbij tegen, dan zou de herparenting die halte onder
 * zichzelf of een eigen afstammeling hangen. De database-trigger blijft het echte vangnet.
 */
function checkForCycle(movingId, candidateParentId) {
  if (candidateParentId === movingId) {
    return t("error.selfParent");
  }
  let current = candidateParentId;
  const seen = new Set();
  while (current != null) {
    if (current === movingId) {
      return t("error.parentIsDescendant");
    }
    if (seen.has(current)) break; // defensief, voorkomt een oneindige lus bij al-corrupte data
    seen.add(current);
    const parentStop = state.stopsById.get(current);
    current = parentStop ? parentStop.parentId : null;
  }
  return null;
}

// --- "Kies een halte"-modus (parent kiezen, target kiezen) ------------------

function startPicking(callback) {
  state.pickCallback = callback;
  alert(t("picking.hint"));
}

// --- Nieuwe groeperingsknoop --------------------------------------------------

function wireCreateNode() {
  document.getElementById("btn-new-node").addEventListener("click", () => {
    showDialog(`
      <h2>${escapeHtml(t("create.heading"))}</h2>
      <form class="inline-form" id="create-form">
        <label>${escapeHtml(t("common.name"))}
          <input name="name" required>
        </label>
        <label>${escapeHtml(t("stopType.label"))} ${helpLink("stopType")}
          <select name="stopType" required>${stopTypeOptionsHtml("onstreetBus", false)}</select>
        </label>
        <label>${escapeHtml(t("create.parentIdOptional"))}
          <input name="parentId" type="number">
        </label>
        <button type="button" class="secondary" id="create-pick-parent">${escapeHtml(t("panel.pickParent"))}</button>
        <label>${escapeHtml(t("create.latOptional"))}
          <input name="lat" type="number" step="any">
        </label>
        <label>${escapeHtml(t("create.lonOptional"))}
          <input name="lon" type="number" step="any">
        </label>
        <span class="error-text" id="create-error"></span>
        <div class="dialog-actions">
          <button type="button" class="secondary" id="create-cancel">${escapeHtml(t("common.cancel"))}</button>
          <button type="submit">${escapeHtml(t("common.create"))}</button>
        </div>
      </form>
    `);
    const form = document.getElementById("create-form");
    document.getElementById("create-cancel").addEventListener("click", closeDialog);
    document.getElementById("create-pick-parent").addEventListener("click", () => {
      startPicking((id) => { form.elements.parentId.value = id; });
    });
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const errorEl = document.getElementById("create-error");
      const baseBody = {
        name: form.elements.name.value,
        stopType: form.elements.stopType.value,
        parentId: form.elements.parentId.value ? Number(form.elements.parentId.value) : null,
        lat: form.elements.lat.value ? Number(form.elements.lat.value) : null,
        lon: form.elements.lon.value ? Number(form.elements.lon.value) : null,
      };
      const submit = async (allowDuplicateName) => {
        await apiFetch("/api/stops", {
          method: "POST",
          body: JSON.stringify(Object.assign({}, baseBody, { allowDuplicateName })),
        });
        closeDialog();
        await loadStops();
      };
      try {
        await submit(false);
      } catch (e) {
        if (e.code === "UC001") {
          if (await confirmDuplicateName(baseBody.name)) {
            try {
              await submit(true);
            } catch (e2) {
              showError(e2);
            }
          }
        } else {
          showActionError(errorEl, e);
        }
      }
    });
  });
}

// --- Groeperen (zelfde presentatie-punt, blijven onderliggend apart bestaan) ----------------

function wireGroupMode() {
  const btn = document.getElementById("btn-group-mode");
  btn.addEventListener("click", () => {
    if (state.group.active) {
      exitGroupMode();
      return;
    }
    exitMergeMode();
    state.group.active = true;
    state.group.pickedIds = [];
    btn.classList.add("active");
    document.getElementById("group-status").hidden = false;
    updateGroupStatus();
  });
  document.getElementById("group-confirm-btn").addEventListener("click", () => openGroupDialog(state.group.pickedIds));
}

function exitGroupMode() {
  state.group.active = false;
  state.group.pickedIds = [];
  document.getElementById("btn-group-mode").classList.remove("active");
  document.getElementById("group-status").hidden = true;
  document.getElementById("group-confirm-btn").hidden = true;
}

function handleGroupPick(id) {
  if (!state.group.pickedIds.includes(id)) {
    state.group.pickedIds.push(id);
  }
  updateGroupStatus();
}

function updateGroupStatus() {
  const status = document.getElementById("group-status");
  const confirmBtn = document.getElementById("group-confirm-btn");
  const picked = state.group.pickedIds;
  if (picked.length === 0) {
    status.textContent = t("group.pickHint");
  } else {
    const names = picked.map(pickLabel).join(", ");
    status.textContent = t("group.pickedCount", { count: picked.length, names });
  }
  confirmBtn.hidden = picked.length < 2;
}

// after: terug naar het voorstellenoverzicht, met grouped = of er gegroepeerd is.
async function openGroupDialog(picked, after = null) {
  // Niet via state.stopsById lezen: dat komt enkel uit de kaart-GeoJSON van het huidige
  // viewport (zie loadStops), maar een halte kan ook via zoeken gekozen zijn terwijl ze buiten
  // beeld ligt. Rechtstreeks ophalen dekt beide gevallen en levert meteen ook de bronnen.
  const details = await Promise.all(picked.map((id) => apiFetch("/api/stops/" + id)));
  const defaultName = details[0]?.displayName || "";
  // Alleen wrappers (losse haltes zonder eigen StopPlace) -> hun quays worden één nieuwe StopPlace (de
  // klassieke "twee perrons aan weerszijden"); anders (echte StopPlaces erbij) groeperen StopPlaces onder een nieuwe general.
  const quayIds = details.map(resolveQuayId);
  const isLoose = (d) => d.isWrapper || d.isQuay;
  // Losse haltes samen met echte StopPlaces: niet stil als general groeperen (dan komen wrappers onder een
  // general, issue #4). Quays horen eerst in een monomodale StopPlace; die kan daarna onder de general.
  if (details.some(isLoose) && !details.every(isLoose)) {
    const loose = details.filter(isLoose).map((d) => `<li>${escapeHtml(d.displayName)}</li>`).join("");
    showDialog(`
      <h2>${escapeHtml(t("toolbar.groupStops"))} ${helpLink("general")}</h2>
      <p class="error-text">${escapeHtml(t("group.mixedRefused"))}</p>
      <ul>${loose}</ul>
      <div class="dialog-actions">
        <button type="button" id="group-mixed-close">${escapeHtml(t("common.close"))}</button>
      </div>
    `);
    document.getElementById("group-mixed-close").addEventListener("click", closeDialog);
    return;
  }
  const groupsQuays = details.every(isLoose) && quayIds.every((q) => q != null);
  const groupIds = groupsQuays ? quayIds : picked;
  // De modus van een wrapper kan afwijken van die van zijn quay; gegroepeerd wordt de quay, dus die telt.
  const modeHolder = (d) => (groupsQuays && !d.isQuay ? d.children.find((c) => c.id === resolveQuayId(d)) : d);
  // Quays: hoofdmodus per quay, zoals chb.stop_place_mode_mismatch (ndov V149). Een general krijgt zijn modus
  // uit de kinderen (V138), maar het type kiest de curator: stel het voor.
  const modes = [...new Set(details.flatMap((d) => {
    const h = modeHolder(d);
    return groupsQuays ? [h.transportMode] : [h.transportMode, ...(h.otherTransportModes || [])];
  }).filter(Boolean))];
  const mode = dominantMode(modes);
  const modeConflict = groupsQuays && modes.length > 1;
  const suggestedType = modeConflict ? null : MODE_TO_STOP_TYPE[mode] || null;
  const listHtml = details.map((d) => {
    const sourceCodes = d.sources.map((s) => `${s.sourceSchema}:${s.sourceId}`).join(", ");
    return `
    <li>${modeBadges(modeHolder(d))} ${escapeHtml(d.displayName)}${d.publicCode ? " (" + escapeHtml(d.publicCode) + ")" : ""}
      ${sourceCodes ? ` <span class="search-result-code">${escapeHtml(sourceCodes)}</span>` : ""}</li>
  `;
  }).join("");

  showDialog(`
    <h2>${escapeHtml(t("toolbar.groupStops"))} ${helpLink(groupsQuays ? "group" : "general")}</h2>
    <p>${escapeHtml(t(groupsQuays ? "group.explainQuays" : "group.explainStopPlaces", { count: picked.length }))}</p>
    ${!groupsQuays && modes.length === 1 ? `<p class="error-text">${escapeHtml(t("group.singleModeWarn", { mode }))}</p>` : ""}
    ${modeConflict ? `<p class="error-text">${escapeHtml(t("group.modeConflictWarn", { modes: modes.join(", ") }))}</p>` : ""}
    ${groupsQuays
      ? (suggestedType ? `<p class="hint-text">${escapeHtml(t("group.quayTypeSuggested", { mode, type: stopTypeLabel(suggestedType) }))}</p>` : "")
      : `<p class="hint-text">${escapeHtml(t("group.typeRule"))}${suggestedType ? " " + escapeHtml(t("group.typeSuggested", { mode, type: stopTypeLabel(suggestedType) })) : ""}</p>`}
    <ul>${listHtml}</ul>
    <form class="inline-form" id="group-form">
      <label>${escapeHtml(t("group.nameLabel"))}
        <input name="name" value="${escapeHtml(defaultName)}" required>
      </label>
      <label>${escapeHtml(t("group.typeLabel"))} ${helpLink("stopType")}
        <select name="stopType" required>${suggestedType ? "" : `<option value="" selected disabled>${escapeHtml(t("group.typeChoose"))}</option>`}${stopTypeOptionsHtml(suggestedType, false)}</select>
      </label>
      <span class="error-text" id="group-error"></span>
      <div class="dialog-actions">
        <button type="button" class="secondary" id="group-dialog-cancel">${escapeHtml(t("common.cancel"))}</button>
        <button type="submit">${escapeHtml(t("group.confirmSubmit"))}</button>
      </div>
    </form>
  `);
  document.getElementById("group-dialog-cancel").addEventListener("click", () => {
    closeDialog();
    after?.(false);
  });
  document.getElementById("group-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const errorEl = document.getElementById("group-error");
    const name = event.target.elements.name.value;
    const baseBody = {
      stopIds: groupIds,
      name,
      stopType: event.target.elements.stopType.value || null,
      lat: null,
      lon: null,
    };
    const submit = async (allowDuplicateName) => {
      await apiFetch("/api/stops/group", {
        method: "POST",
        body: JSON.stringify(Object.assign({}, baseBody, { allowDuplicateName })),
      });
      closeDialog();
      closePanel();
      exitGroupMode();
      await loadStops();
      after?.(true);
    };
    try {
      await submit(false);
    } catch (e) {
      // UC001 = er bestaat al een gelijknamige groeperingsknoop; de curator mag annuleren of toch
      // doorgaan (chb.cur_guard_duplicate_name in de database). Andere 409's (lock/deadlock) zijn
      // gewone fouten met een "probeer opnieuw"-melding.
      if (e.code === "UC001") {
        if (await confirmDuplicateName(name)) {
          try {
            await submit(true);
          } catch (e2) {
            showError(e2);
          }
        } else {
          after?.(false);
        }
      } else {
        showActionError(errorEl, e);
      }
    }
  });
}

// --- Samenvoegen -----------------------------------------------------------

function renderMergeStatus() {
  const status = document.getElementById("merge-status");
  if (state.merge.sourceId == null) {
    status.textContent = t("merge.startHint");
  } else {
    const name = pickLabel(state.merge.sourceId);
    status.textContent = t("merge.sourcePicked", { name });
  }
}

function wireMergeMode() {
  const btn = document.getElementById("btn-merge-mode");
  const status = document.getElementById("merge-status");
  btn.addEventListener("click", () => {
    if (state.merge.active) {
      exitMergeMode();
      return;
    }
    exitGroupMode();
    state.merge.active = true;
    state.merge.sourceId = null;
    state.merge.targetId = null;
    btn.classList.add("active");
    status.hidden = false;
    renderMergeStatus();
  });
}

function exitMergeMode() {
  state.merge.active = false;
  state.merge.sourceId = null;
  state.merge.targetId = null;
  document.getElementById("btn-merge-mode").classList.remove("active");
  document.getElementById("merge-status").hidden = true;
}

function handleMergePick(id) {
  if (state.merge.sourceId == null) {
    state.merge.sourceId = id;
    renderMergeStatus();
    return;
  }
  state.merge.targetId = id;
  const sourceId = state.merge.sourceId;
  const targetId = state.merge.targetId;
  exitMergeMode();
  confirmMerge(sourceId, targetId);
}

/**
 * De kaart toont StopPlaces, maar samenvoegen (duplicaten uit meerdere bronfeeds) werkt op quays
 * (chb.merge_quays). Een quay is zichzelf; een wrapper vertaalt naar zijn ene quay; een echte
 * StopPlace heeft er meerdere en is dus niet eenduidig (null).
 */
function resolveQuayId(detail) {
  if (detail.isQuay) return detail.id;
  if (detail.isWrapper) {
    const quays = detail.children.filter((c) => c.isQuay);
    if (quays.length === 1) return quays[0].id;
  }
  return null;
}

/**
 * Vervoerder van een bron: bmc_tec = tec, delijn_opendata = delijn. Mergen over vervoerders heen mag
 * sinds V135 (ndov#70), maar alleen voor dezelfde fysieke paal (Business Rules CHB §6).
 */
function operatorsDiffer(a, b) {
  const ops = (d) => new Set(d.sources.map((s) => s.sourceSchema.replace(/^bmc_/, "").replace(/_opendata$/, "")));
  const all = new Set([...ops(a), ...ops(b)]);
  return ops(a).size > 0 && ops(b).size > 0 && all.size > 1;
}

// --- Dubbele quays (chb#22) -------------------------------------------------
// Twee levende quays met een gemeenschappelijk bron-haltenummer zijn dezelfde paal. Dat ontstaat als een curator
// de tweeling-quay naar de StopPlace verplaatst i.p.v. samenvoegt. Voorstel: de jongste (hoogste id) gaat op in
// de oudste, die al in de StopPlace zat. Naam + afstand is geen criterium: TEC-palen aan weerszijden van de
// straat en NMBS-perrons liggen even dicht bij elkaar en hebben geen haltenummer.

const sharesStopCode = (codesA, codesB) => codesA.some((code) => codesB.includes(code));
const isLiveQuay = (c) => c.isQuay && !c.disabledAt;

/** Map dubbel-quay-id -> te behouden quay-id, binnen de kinderen van één StopPlace. */
function duplicateQuaysAmong(children) {
  const live = children.filter(isLiveQuay).sort((a, b) => a.id - b.id);
  const result = new Map();
  live.forEach((c, i) => {
    const keep = live.slice(0, i).find((k) => sharesStopCode(k.sourceStopCodes, c.sourceStopCodes));
    if (keep) result.set(c.id, keep.id);
  });
  return result;
}

function duplicateBadge(child, keepId) {
  if (keepId == null) return "";
  const badge = ` <span class="badge badge-duplicate" title="${escapeHtml(t("duplicateQuays.hint"))}">${escapeHtml(t("duplicateQuays.badge", { id: keepId }))}</span>`;
  return state.me.curator
    ? `${badge} <button type="button" class="secondary duplicate-merge-btn" data-source="${child.id}" data-target="${keepId}">${escapeHtml(t("duplicateQuays.merge"))}</button>`
    : badge;
}

/** Levende quay in de doel-StopPlace met een haltenummer van deze quay, of null. */
async function findTwinQuay(quay, targetStopId) {
  const codes = quay.sources.map((s) => s.sourceStopCode).filter(Boolean);
  if (!codes.length) return null;
  const target = await apiFetch("/api/stops/" + targetStopId);
  return target.children.find((c) => isLiveQuay(c) && c.id !== quay.id && sharesStopCode(c.sourceStopCodes, codes)) || null;
}

function confirmMoveOntoTwin(quay, twin, save) {
  showDialog(`
    <h2>${escapeHtml(t("duplicateQuays.moveTitle"))} ${helpLink("merge")}</h2>
    <p>${escapeHtml(t("duplicateQuays.moveWarning", { quay: quay.id, twin: twin.id, name: twin.displayName, codes: twin.sourceStopCodes.join(", ") }))}</p>
    <span class="error-text" id="move-error"></span>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="move-cancel">${escapeHtml(t("common.cancel"))}</button>
      <button type="button" class="secondary" id="move-anyway">${escapeHtml(t("duplicateQuays.moveAnyway"))}</button>
      <button type="button" id="move-merge">${escapeHtml(t("duplicateQuays.mergeInstead", { id: twin.id }))}</button>
    </div>
  `);
  document.getElementById("move-cancel").addEventListener("click", closeDialog);
  document.getElementById("move-anyway").addEventListener("click", async () => {
    try {
      await save();
      closeDialog();
    } catch (e) {
      showActionError(document.getElementById("move-error"), e);
    }
  });
  document.getElementById("move-merge").addEventListener("click", () => confirmMerge(quay.id, twin.id).catch(console.error));
}

async function confirmMerge(pickedSourceId, pickedTargetId) {
  const [source, target] = await Promise.all([
    apiFetch("/api/stops/" + pickedSourceId),
    apiFetch("/api/stops/" + pickedTargetId),
  ]);
  const sourceId = resolveQuayId(source);
  const targetId = resolveQuayId(target);
  if (sourceId == null || targetId == null) {
    alert(t("merge.needQuay"));
    return;
  }
  if (sourceId === targetId) {
    alert(t("merge.sameStop"));
    return;
  }
  const sourceFeeds = source.sources.map((s) => `${s.sourceSchema}:${s.sourceId} (${s.sourceName})`).join(", ") || t("common.none");
  const targetFeeds = target.sources.map((s) => `${s.sourceSchema}:${s.sourceId} (${s.sourceName})`).join(", ") || t("common.none");
  showDialog(`
    <h2>${escapeHtml(t("toolbar.mergeStops"))} ${helpLink("merge")}</h2>
    <p>${t("merge.explain1", { source: escapeHtml(source.displayName), sourceFeeds: escapeHtml(sourceFeeds), target: escapeHtml(target.displayName), targetFeeds: escapeHtml(targetFeeds) })}</p>
    <p>${t("merge.explain2", { source: escapeHtml(source.displayName), target: escapeHtml(target.displayName) })}</p>
    ${operatorsDiffer(source, target) ? `<p class="error-text">${t("merge.crossOperator")}</p>` : ""}
    <span class="error-text" id="merge-error"></span>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="merge-cancel">${escapeHtml(t("common.cancel"))}</button>
      <button type="button" id="merge-confirm">${escapeHtml(t("merge.confirmSubmit"))}</button>
    </div>
  `);
  document.getElementById("merge-cancel").addEventListener("click", closeDialog);
  document.getElementById("merge-confirm").addEventListener("click", async () => {
    const errorEl = document.getElementById("merge-error");
    try {
      await apiFetch("/api/stops/" + sourceId + "/merge", {
        method: "POST",
        body: JSON.stringify({ targetId }),
      });
      closeDialog();
      closePanel();
      await loadStops();
    } catch (e) {
      showActionError(errorEl, e);
    }
  });
}

// --- Doorlichtingsrapport ----------------------------------------------------
// "Nagekeken" wordt bijgehouden als een gereserveerde chb.stop_attribute-sleutel (server-side,
// zie StopEditController#markReviewed) — een halte zonder die sleutel is per definitie nog niet
// bekeken, dus nieuwe haltes verschijnen hier vanzelf, zonder extra werk.

function wireReport() {
  document.getElementById("btn-report").addEventListener("click", openReportDialog);
  document.getElementById("btn-duplicate-names").addEventListener("click", openDuplicateNamesDialog);
  document.getElementById("btn-name-issues").addEventListener("click", openNameIssuesDialog);
  document.getElementById("btn-tec-cities").addEventListener("click", openTecCitiesDialog);
  document.getElementById("btn-multimode-quays").addEventListener("click", openMultimodeQuaysDialog);
  document.getElementById("btn-mode-mismatches").addEventListener("click", openModeMismatchesDialog);
  document.getElementById("btn-brussels-names").addEventListener("click", openBrusselsNamesDialog);
  document.getElementById("btn-duplicate-quays").addEventListener("click", openDuplicateQuaysDialog);
  document.getElementById("btn-group-candidates").addEventListener("click", openGroupCandidatesDialog);
  // Dicht bij een klik op een rapport of ernaast, en met Escape.
  const menu = document.getElementById("reports-menu");
  document.addEventListener("click", (event) => {
    if (!menu.contains(event.target) || event.target.closest(".reports-menu-items")) menu.open = false;
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") menu.open = false;
  });
}

function applyUnreviewedToggleUi() {
  const btn = document.getElementById("btn-unreviewed-map");
  btn.classList.toggle("active", state.showOnlyUnreviewed);
  document.getElementById("unreviewed-toggle-label").textContent =
    t(state.showOnlyUnreviewed ? "toolbar.showAll" : "toolbar.unreviewedOnly");
}

function wireUnreviewedMapToggle() {
  document.getElementById("btn-unreviewed-map").addEventListener("click", async () => {
    state.showOnlyUnreviewed = !state.showOnlyUnreviewed;
    applyUnreviewedToggleUi();
    persistUnreviewedToggle(state.showOnlyUnreviewed);
    closePanel();
    await loadStops();
  });
}

// --- "Toon uitgeschakelde haltes"-toggle ------------------------------------
// Beschikbaar voor iedereen (niet curator-only, in tegenstelling tot de doorlichtingstoggle
// hierboven): disabled_at is geen curatiestatus maar een feitelijk, automatisch bijgehouden
// gegeven over of een halte momenteel in de bronfeeds voorkomt.

function applyDisabledToggleUi() {
  const btn = document.getElementById("btn-show-disabled");
  btn.classList.toggle("active", state.showDisabled);
  document.getElementById("show-disabled-label").textContent =
    t(state.showDisabled ? "toolbar.hideDisabled" : "toolbar.showDisabled");
}

function wireShowDisabledToggle() {
  document.getElementById("btn-show-disabled").addEventListener("click", async () => {
    state.showDisabled = !state.showDisabled;
    applyDisabledToggleUi();
    persistDisabledToggle(state.showDisabled);
    await loadStops();
  });
}

async function openReportDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.report"))}</h2>
    <p id="report-stats" class="hint-text">${escapeHtml(t("common.loading"))}</p>
    <div id="report-mismatches" hidden>
      <p class="error-text" id="report-mismatches-text"></p>
      <ul id="report-mismatch-list"></ul>
    </div>
    <input type="search" id="report-search" placeholder="${escapeHtml(t("report.filterPlaceholder"))}">
    <ul id="report-list"></ul>
    <button type="button" class="secondary" id="report-load-more" hidden>${escapeHtml(t("report.loadMore"))}</button>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="report-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("report-close").addEventListener("click", closeDialog);

  const paging = { query: "", offset: 0, limit: 50 };
  let debounceHandle = null;
  let generation = 0;

  async function loadStats() {
    const stats = await apiFetch("/api/review/stats");
    const done = stats.grouped + stats.standaloneOk;
    const pct = stats.totalQuays ? Math.round((done / stats.totalQuays) * 100) : 100;
    document.getElementById("report-stats").textContent =
      t("report.stats", { reviewed: done, total: stats.totalQuays, pct, grouped: stats.grouped, standalone: stats.standaloneOk,
        remaining: stats.unreviewedStopPlaces });
    if (stats.reviewMismatches.length) {
      document.getElementById("report-mismatches").hidden = false;
      document.getElementById("report-mismatches-text").textContent = t("report.mismatches", { n: stats.reviewMismatches.length });
      const list = document.getElementById("report-mismatch-list");
      for (const m of stats.reviewMismatches) {
        const li = document.createElement("li");
        li.className = "report-item";
        li.textContent = t("report.mismatchRow", { name: m.name, id: m.stopId, quay: m.quayId,
          stopDate: formatTimestamp(m.stopReviewedAt) || "–", quayDate: formatTimestamp(m.quayReviewedAt) || "–" });
        li.addEventListener("click", () => {
          closeDialog();
          selectStop(m.stopId);
        });
        list.appendChild(li);
      }
    }
    // Blinde vlek dichten: de database kan geweigerde acties niet zelf tellen (transactie rolt terug).
    const rejections = await apiFetch("/api/review/rejections");
    const list = Object.entries(rejections).map(([code, n]) => code + ": " + n).join(", ") || t("common.none");
    document.getElementById("report-stats").textContent += " " + t("report.rejections", { list });
  }

  async function loadPage(reset) {
    if (reset) paging.offset = 0;
    document.getElementById("report-load-more").hidden = true; // geen 'meer' bovenop een lopende reset
    const mine = ++generation;
    const results = await apiFetch(
      "/api/review/unreviewed?q=" + encodeURIComponent(paging.query) + "&limit=" + paging.limit + "&offset=" + paging.offset);
    const list = document.getElementById("report-list");
    if (mine !== generation || !list) return;
    if (reset) list.innerHTML = "";
    for (const stop of results) {
      const li = document.createElement("li");
      li.className = "report-item";
      li.innerHTML = escapeHtml(stop.path) + (stop.autoGroup ? " " + autoGroupBadge() : "");
      li.addEventListener("click", () => {
        closeDialog();
        selectStop(stop.id);
      });
      list.appendChild(li);
    }
    paging.offset += results.length;
    document.getElementById("report-load-more").hidden = results.length < paging.limit;
  }

  document.getElementById("report-search").addEventListener("input", (event) => {
    clearTimeout(debounceHandle);
    paging.query = event.target.value.trim();
    debounceHandle = setTimeout(() => loadPage(true), 250);
  });
  document.getElementById("report-load-more").addEventListener("click", () => loadPage(false));

  await loadStats();
  await loadPage(true);
}

// --- Rapport: gelijknamige haltes waarvan sommige al nagekeken zijn --------
// Een naam die zowel een nagekeken als een niet-nagekeken StopPlace draagt: de niet-nagekeken
// exemplaren zijn mogelijk over het hoofd gezien doordat een curator de naam al bij een
// gelijknamige halte zag afvinken.

async function openDuplicateNamesDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.duplicateNames"))}</h2>
    <p class="hint-text">${escapeHtml(t("duplicateNames.hint"))}</p>
    <input type="search" id="dup-search" placeholder="${escapeHtml(t("report.filterPlaceholder"))}">
    <ul id="dup-list"></ul>
    <button type="button" class="secondary" id="dup-load-more" hidden>${escapeHtml(t("report.loadMore"))}</button>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="dup-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("dup-close").addEventListener("click", closeDialog);

  const paging = { query: "", offset: 0, limit: 20 };
  let debounceHandle = null;
  let generation = 0;

  async function loadPage(reset) {
    if (reset) paging.offset = 0;
    document.getElementById("dup-load-more").hidden = true; // geen 'meer' bovenop een lopende reset
    const mine = ++generation;
    const groups = await apiFetch(
      "/api/review/duplicate-names?q=" + encodeURIComponent(paging.query) + "&limit=" + paging.limit + "&offset=" + paging.offset);
    const list = document.getElementById("dup-list");
    if (mine !== generation || !list) return;
    if (reset) list.innerHTML = "";
    for (const group of groups) {
      const groupLi = document.createElement("li");
      groupLi.className = "report-group";
      const heading = document.createElement("div");
      heading.className = "report-group-heading";
      heading.textContent = t("duplicateNames.group", { name: group.name, reviewed: group.reviewedCount });
      groupLi.appendChild(heading);
      const sub = document.createElement("ul");
      for (const stop of group.unreviewed) {
        const li = document.createElement("li");
        li.className = "report-item";
        li.textContent = stop.path;
        li.addEventListener("click", () => {
          closeDialog();
          selectStop(stop.id);
        });
        sub.appendChild(li);
      }
      groupLi.appendChild(sub);
      list.appendChild(groupLi);
    }
    paging.offset += groups.length;
    document.getElementById("dup-load-more").hidden = groups.length < paging.limit;
  }

  document.getElementById("dup-search").addEventListener("input", (event) => {
    clearTimeout(debounceHandle);
    paging.query = event.target.value.trim();
    debounceHandle = setTimeout(() => loadPage(true), 250);
  });
  document.getElementById("dup-load-more").addEventListener("click", () => loadPage(false));

  await loadPage(true);
}

// --- Rapport: naamproblemen (issue #3) ----------------------------------------
// Bron van waarheid zijn chb.quay_names/chb.stop_names (ndov V132/V137): de reden komt uit de
// database, hier wordt niets herberekend. Groepsredenen eerst, info-categorieën achteraan.

const NAME_ISSUE_REASONS = ["disagree", "region_mixed", "general_region_unknown", "general_no_language",
  "region_unknown", "no_region_language", "bare_code", "no_language", "bru_single", "dg_no_de"];

async function openNameIssuesDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.nameIssues"))} ${helpLink("names")}</h2>
    <p class="hint-text">${escapeHtml(t("nameIssues.hint"))}</p>
    <div id="names-filters">
      <select id="names-reason">${NAME_ISSUE_REASONS.map((r) =>
        `<option value="${r}">${escapeHtml(t("nameIssues.reason." + r))}</option>`).join("")}</select>
      <select id="names-feed"><option value="">${escapeHtml(t("nameIssues.allFeeds"))}</option></select>
    </div>
    <ul id="names-list"></ul>
    <button type="button" class="secondary" id="names-load-more" hidden>${escapeHtml(t("report.loadMore"))}</button>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="names-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("names-close").addEventListener("click", closeDialog);

  const paging = { offset: 0, limit: 50 };
  let generation = 0;

  async function loadPage(reset) {
    const list = document.getElementById("names-list");
    const more = document.getElementById("names-load-more");
    if (reset) {
      paging.offset = 0;
      list.innerHTML = `<li class="report-item">${escapeHtml(t("common.loading"))}</li>`;
    }
    more.hidden = true;
    // Een trage pagina (views lezen volledig) mag een nieuwere filterkeuze niet overschrijven.
    const mine = ++generation;
    const reason = document.getElementById("names-reason").value;
    const feed = document.getElementById("names-feed").value;
    const rows = await apiFetch("/api/review/name-issues?reason=" + encodeURIComponent(reason) +
      "&feed=" + encodeURIComponent(feed) + "&limit=" + paging.limit + "&offset=" + paging.offset);
    if (mine !== generation || !document.getElementById("names-list")) {
      return;
    }
    if (reset) {
      list.innerHTML = rows.length ? "" : `<li class="report-item">${escapeHtml(t("nameIssues.none"))}</li>`;
    }
    for (const row of rows) {
      const li = document.createElement("li");
      li.className = "report-item";
      const head = [row.name, row.municipality, row.feeds.join(", ")].filter(Boolean).join(" — ");
      const sub = [row.names,
        t("nameIssues.default", { name: row.defaultName + (row.defaultLang ? " (" + row.defaultLang + ")" : "") }),
        row.disagreeLangs.length ? t("nameIssues.disagree", { langs: row.disagreeLangs.join(", ") }) : null,
        row.reason].filter(Boolean).join(" · ");
      li.innerHTML = escapeHtml(head) +
        (row.isQuay ? ` <span class="badge badge-physical">${escapeHtml(t("panel.quayBadge"))}</span>` : "") +
        `<div class="report-item-sub">${escapeHtml(sub)}</div>`;
      li.addEventListener("click", () => {
        closeDialog();
        selectStop(row.id);
      });
      list.appendChild(li);
    }
    paging.offset += rows.length;
    more.hidden = rows.length < paging.limit;
  }

  document.getElementById("names-reason").addEventListener("change", () => loadPage(true));
  document.getElementById("names-feed").addEventListener("change", () => loadPage(true));
  document.getElementById("names-load-more").addEventListener("click", () => loadPage(false));

  apiFetch("/api/review/feeds").then((feeds) => {
    const select = document.getElementById("names-feed");
    for (const feed of feeds) {
      select.add(new Option(feed, feed));
    }
  }).catch(() => {});
  await loadPage(true);
}

// --- Rapport: TEC-plaatsen (issue #1) ------------------------------------------
// TEC-quays waarvoor chb.tec_city_candidates() (ndov V115) geen plaats vond. Opslaan zet het
// quay-attribuut 'city' via de gewone attribuutroute; daarna valt de quay uit de lijst (stored_city).

const TEC_CITY_REASONS = ["shorter_prefix", "caps_only", "rare_prefix", "no_caps_prefix", "other"];

async function openTecCitiesDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.tecCities"))}</h2>
    <p class="hint-text">${escapeHtml(t("tecCities.hint"))}</p>
    <div id="names-filters">
      <select id="tec-reason"><option value="">${escapeHtml(t("tecCities.allReasons"))}</option>${TEC_CITY_REASONS.map((r) =>
        `<option value="${r}">${escapeHtml(t("tecCities.reason." + r))}</option>`).join("")}</select>
      <span id="tec-count" class="hint-text"></span>
    </div>
    <ul id="names-list"><li class="report-item">${escapeHtml(t("common.loading"))}</li></ul>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="tec-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("tec-close").addEventListener("click", closeDialog);
  const rows = await apiFetch("/api/review/tec-cities");
  const reasonSelect = document.getElementById("tec-reason");
  if (!reasonSelect) {
    return;
  }

  function render() {
    const list = document.getElementById("names-list");
    const shown = rows.filter((row) => !reasonSelect.value || row.reason === reasonSelect.value);
    document.getElementById("tec-count").textContent = t("tecCities.count", { n: shown.length });
    list.innerHTML = shown.length ? "" : `<li class="report-item">${escapeHtml(t("tecCities.none"))}</li>`;
    for (const row of shown) {
      const li = document.createElement("li");
      li.className = "report-item tec-city-row";
      const sub = [t("tecCities.reason." + row.reason),
        row.municipality ? t("tecCities.municipality", { name: row.municipality }) : null].filter(Boolean).join(" · ");
      li.innerHTML = `<div class="tec-city-name">${escapeHtml(row.name)}<div class="report-item-sub">${escapeHtml(sub)}</div></div>
        <form class="tec-city-form"><input name="city" required value="${escapeHtml(row.suggestion || "")}">
        <button type="submit">${escapeHtml(t("common.save"))}</button></form>
        <div class="error-text"></div>`;
      li.querySelector(".tec-city-name").addEventListener("click", () => {
        closeDialog();
        selectStop(row.quayId);
      });
      li.querySelector("form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const errorEl = li.querySelector(".error-text");
        errorEl.textContent = "";
        try {
          await apiFetch("/api/stops/" + row.quayId + "/attributes/city", {
            method: "PUT",
            body: JSON.stringify({ value: event.target.elements.city.value.trim() }),
          });
          rows.splice(rows.indexOf(row), 1);
          render();
        } catch (e) {
          showActionError(errorEl, e);
        }
      });
      list.appendChild(li);
    }
  }

  reasonSelect.addEventListener("change", render);
  render();
}

// chb.multimode_quay (ndov V145): hoort leeg te zijn. Blijft staan bij een vervoerder zonder splitsing
// of als een curator de quays van één halte samenvoegde.
async function openMultimodeQuaysDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.multimodeQuays"))}</h2>
    <p class="hint-text">${escapeHtml(t("multimodeQuays.hint"))}</p>
    <ul id="names-list"><li class="report-item">${escapeHtml(t("common.loading"))}</li></ul>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="multimode-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("multimode-close").addEventListener("click", closeDialog);
  const rows = await apiFetch("/api/review/multimode-quays");
  const list = document.getElementById("names-list");
  if (!list) {
    return;
  }
  list.innerHTML = rows.length ? "" : `<li class="report-item">${escapeHtml(t("multimodeQuays.none"))}</li>`;
  for (const row of rows) {
    const li = document.createElement("li");
    li.className = "report-item";
    li.innerHTML = `${escapeHtml(row.name)}<div class="report-item-sub">${escapeHtml(row.modes.join(" + "))} · ${escapeHtml(row.sourceSchemas.join(", "))}</div>`;
    li.addEventListener("click", () => {
      closeDialog();
      selectStop(row.quayId);
    });
    list.appendChild(li);
  }
}

// chb.stop_group_candidate (ndov V143): voorstellen, de curator beslist. Afwijzen kan nog niet: een voorstel
// verdwijnt pas na groeperen. De ketting kan langer zijn dan de 150 m van de clustering (max_distance_m).
const CANDIDATE_WIDE_M = 150;

async function openGroupCandidatesDialog() {
  let rows = null;
  let filter = "";
  let scrollTop = 0;

  function render() {
    const list = document.getElementById("names-list");
    const needle = filter.trim().toLowerCase();
    const shown = rows.filter((row) => row.name.toLowerCase().includes(needle));
    document.getElementById("candidates-count").textContent = t("groupCandidates.count", { n: shown.length });
    list.innerHTML = shown.length ? "" : `<li class="report-item">${escapeHtml(t("groupCandidates.none"))}</li>`;
    for (const row of shown) {
      const li = document.createElement("li");
      li.className = "report-item tec-city-row";
      const wide = row.maxDistanceM > CANDIDATE_WIDE_M;
      li.innerHTML = `<div class="tec-city-name">${modeBadges(row)} ${escapeHtml(row.name)}
          <div class="report-item-sub">${escapeHtml(t("groupCandidates.meta", { count: row.quayIds.length, m: row.maxDistanceM }))}${wide ? ` · <span class="error-text">${escapeHtml(t("groupCandidates.wide", { m: CANDIDATE_WIDE_M }))}</span>` : ""}</div></div>
        <button type="button" class="secondary">${escapeHtml(t("groupCandidates.group"))}</button>`;
      li.querySelector(".tec-city-name").addEventListener("click", () => {
        closeDialog();
        selectStop(row.quayIds[0]);
      });
      li.querySelector("button").addEventListener("click", () => {
        scrollTop = list.scrollTop;
        openGroupDialog(row.quayIds, (grouped) => {
          if (grouped) rows = rows.filter((r) => r !== row);
          show();
        });
      });
      list.appendChild(li);
    }
    list.scrollTop = scrollTop;
  }

  function show() {
    showDialog(`
      <h2>${escapeHtml(t("toolbar.groupCandidates"))} ${helpLink("group")}</h2>
      <p class="hint-text">${escapeHtml(t("groupCandidates.hint", { m: CANDIDATE_WIDE_M }))}</p>
      <div id="names-filters">
        <input type="search" id="candidates-search" placeholder="${escapeHtml(t("report.filterPlaceholder"))}">
        <span id="candidates-count" class="hint-text"></span>
      </div>
      <ul id="names-list"><li class="report-item">${escapeHtml(t("common.loading"))}</li></ul>
      <div class="dialog-actions">
        <button type="button" class="secondary" id="candidates-close">${escapeHtml(t("common.close"))}</button>
      </div>
    `);
    document.getElementById("candidates-close").addEventListener("click", closeDialog);
    const search = document.getElementById("candidates-search");
    search.value = filter;
    search.addEventListener("input", () => {
      filter = search.value;
      scrollTop = 0;
      render();
    });
    if (rows) render();
  }

  show();
  rows = await apiFetch("/api/review/group-candidates");
  // Dialoog intussen gesloten of vervangen: niets meer te tekenen.
  if (document.getElementById("candidates-search")) render();
}

// Het enige signaal voor deze afwijkingen (ndov#75, chb#22): de tellers staan op de altijd zichtbare menuknop.
// Verversen bij het laden en na elke schrijfactie; een import zie je pas na herladen.

function refreshReviewCounts() {
  refreshModeMismatches().catch(console.error);
  refreshDuplicateQuays().catch(console.error);
}

async function refreshModeMismatches() {
  const rows = await apiFetch("/api/review/mode-mismatches");
  state.reviewCounts.modeMismatches = rows.length;
  renderReviewCounts();
  return rows;
}

async function refreshDuplicateQuays() {
  const rows = await apiFetch("/api/review/duplicate-quays");
  state.reviewCounts.duplicateQuays = rows.length;
  renderReviewCounts();
  return rows;
}

function renderReviewCounts() {
  const counts = state.reviewCounts;
  const total = counts.modeMismatches + counts.duplicateQuays;
  for (const badge of document.querySelectorAll(".review-count")) {
    const n = badge.dataset.count === "total" ? total : counts[badge.dataset.count];
    badge.textContent = n;
    badge.hidden = n === 0;
  }
  // De aria-label van de knop gaat voor op zijn tekst, dus de tellers moeten er ook in.
  const reports = t("toolbar.reports");
  const parts = [];
  if (counts.modeMismatches) parts.push(t("modeMismatches.count", { n: counts.modeMismatches }));
  if (counts.duplicateQuays) parts.push(t("duplicateQuays.count", { n: counts.duplicateQuays }));
  const label = parts.length ? `${reports} (${parts.join(", ")})` : reports;
  const summary = document.querySelector("#reports-menu summary");
  summary.setAttribute("aria-label", label);
  summary.title = parts.length ? label : "";
}

// chb.stop_place_mode_mismatch (V149) en chb.source_station_mode_mismatch (V144): horen leeg te zijn.
async function openModeMismatchesDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.modeMismatches"))} ${helpLink("general")}</h2>
    <p class="hint-text">${escapeHtml(t("modeMismatches.hint"))}</p>
    <ul id="names-list"><li class="report-item">${escapeHtml(t("common.loading"))}</li></ul>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="mode-mismatches-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("mode-mismatches-close").addEventListener("click", closeDialog);
  const rows = await refreshModeMismatches();
  const list = document.getElementById("names-list");
  if (!list) {
    return;
  }
  list.innerHTML = rows.length ? "" : `<li class="report-item">${escapeHtml(t("modeMismatches.none"))}</li>`;
  for (const row of rows) {
    const parts = [t("modeMismatches.row", { quay: row.quayId, quayMode: row.quayMode || t("modeMismatches.mixedOrNoTrips"),
      stop: row.stopId, stopMode: row.stopMode || t("group.noMode") })];
    if (row.isWrapper) parts.push(t("kind.wrapper"));
    if (row.sourceStation) parts.push(`${t("panel.sourceStation")}: ${row.sourceStation}`);
    const li = document.createElement("li");
    li.className = "report-item";
    li.innerHTML = `${escapeHtml(row.name)}<div class="report-item-sub">${escapeHtml(parts.join(" · "))}</div>`;
    li.addEventListener("click", () => {
      closeDialog();
      selectStop(row.quayId);
    });
    list.appendChild(li);
  }
}

// chb#22: hoort leeg te zijn. Samenvoegen gaat via de gewone merge-dialoog, met de voorgestelde richting.
async function openDuplicateQuaysDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.duplicateQuays"))} ${helpLink("merge")}</h2>
    <p class="hint-text">${escapeHtml(t("duplicateQuays.listHint"))}</p>
    <ul id="names-list"><li class="report-item">${escapeHtml(t("common.loading"))}</li></ul>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="duplicate-quays-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("duplicate-quays-close").addEventListener("click", closeDialog);
  const rows = await refreshDuplicateQuays();
  const list = document.getElementById("names-list");
  if (!list) {
    return;
  }
  list.innerHTML = rows.length ? "" : `<li class="report-item">${escapeHtml(t("duplicateQuays.none"))}</li>`;
  for (const row of rows) {
    const li = document.createElement("li");
    li.className = "report-item";
    li.innerHTML = `${escapeHtml(row.name)}<div class="report-item-sub">${escapeHtml(t("duplicateQuays.row",
      { quay: row.quayId, keep: row.keepId, stop: row.stopId, code: row.sourceStopCode }))}</div>`;
    li.addEventListener("click", () => {
      closeDialog();
      selectStop(row.stopId);
    });
    list.appendChild(li);
  }
}

// Brusselse StopPlace-namen (chb#24, Business Rules §7). Deel 1 hoort leeg te zijn, deel 2 is een werklijst.
async function openBrusselsNamesDialog() {
  showDialog(`
    <h2>${escapeHtml(t("toolbar.brusselsNames"))}</h2>
    <p class="hint-text">${escapeHtml(t("brusselsNames.hint"))}</p>
    <ul id="names-list"><li class="report-item">${escapeHtml(t("common.loading"))}</li></ul>
    <h3>${escapeHtml(t("brusselsNames.pairsHeading"))}</h3>
    <p class="hint-text">${escapeHtml(t("brusselsNames.pairsHint"))}</p>
    <ul id="bru-pairs"></ul>
    <div class="dialog-actions">
      <button type="button" class="secondary" id="bru-close">${escapeHtml(t("common.close"))}</button>
    </div>
  `);
  document.getElementById("bru-close").addEventListener("click", closeDialog);
  const report = await apiFetch("/api/review/brussels-names");
  const list = document.getElementById("names-list");
  if (!list) {
    return;
  }
  const rows = report.stopPlaces;

  function renderStopPlaces() {
    list.innerHTML = rows.length ? "" : `<li class="report-item">${escapeHtml(t("brusselsNames.none"))}</li>`;
    for (const row of rows) {
      const sub = [
        row.blocking ? t("brusselsNames.blocking", { langs: row.disagreeLangs.join(", ") }) : t("brusselsNames.deviates"),
        t("kind." + row.kind) + (row.transportMode ? " · " + row.transportMode : ""),
        row.expected ? t("brusselsNames.expected", { name: row.expected }) : null,
        row.nameReason,
        row.curatorFr || row.curatorNl ? t("brusselsNames.curator", { fr: row.curatorFr || "–", nl: row.curatorNl || "–" }) : null,
        row.sources.join(", "),
      ].filter(Boolean).join(" · ");
      const li = document.createElement("li");
      li.className = "report-item tec-city-row";
      li.innerHTML = `<div class="tec-city-name">${escapeHtml(row.defaultName || String(row.id))}
          <div class="report-item-sub">${escapeHtml(sub)}</div></div>
        ${row.mivbFr && row.mivbNl ? `<button type="button">${escapeHtml(t("brusselsNames.adopt"))}</button>` : ""}
        <div class="error-text"></div>`;
      li.querySelector(".tec-city-name").addEventListener("click", () => {
        closeDialog();
        selectStop(row.id);
      });
      const button = li.querySelector("button");
      if (button) {
        button.addEventListener("click", async () => {
          const errorEl = li.querySelector(".error-text");
          errorEl.textContent = "";
          button.disabled = true;
          try {
            for (const [key, value] of [["name_fr", row.mivbFr], ["name_nl", row.mivbNl]]) {
              await apiFetch("/api/stops/" + row.id + "/attributes/" + key, { method: "PUT", body: JSON.stringify({ value }) });
            }
            rows.splice(rows.indexOf(row), 1);
            renderStopPlaces();
          } catch (e) {
            button.disabled = false;
            showActionError(errorEl, e);
          }
        });
      }
      list.appendChild(li);
    }
  }
  renderStopPlaces();

  // Per MIVB-naam gegroepeerd: één groepering lost meestal alle paren van die naam op.
  const groups = new Map();
  for (const pair of report.pairs) {
    const name = pair.mivbFr === pair.mivbNl ? pair.mivbFr : pair.mivbFr + " / " + pair.mivbNl;
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(pair);
  }
  const pairList = document.getElementById("bru-pairs");
  pairList.innerHTML = groups.size ? "" : `<li class="report-item">${escapeHtml(t("brusselsNames.noPairs"))}</li>`;
  for (const [name, pairs] of groups) {
    const groupLi = document.createElement("li");
    groupLi.className = "report-group";
    groupLi.innerHTML = `<div class="report-group-heading">${escapeHtml(name)}</div><ul></ul>`;
    for (const pair of pairs) {
      const li = document.createElement("li");
      li.className = "report-item";
      const other = [pair.otherFr, pair.otherNl].filter(Boolean).join(" / ");
      li.innerHTML = `${escapeHtml(other)}<div class="report-item-sub">${escapeHtml(t("brusselsNames.pair", {
        mivb: pair.mivbQuay, other: pair.otherQuay, source: pair.otherSource, mode: pair.transportMode, distance: pair.distanceM }))}</div>`;
      li.addEventListener("click", () => {
        closeDialog();
        selectStop(pair.mivbQuay);
      });
      groupLi.querySelector("ul").appendChild(li);
    }
    pairList.appendChild(groupLi);
  }
}

// --- Generieke dialoog -------------------------------------------------------

// Helppop-ups: samenvatting van de Business Rules CHB (ndov-repo), tekst in i18n.js (HELP_TEXTS, alleen Nederlands).
const HELP_SECTIONS = { group: "§1, §2", general: "§1, §7", stopType: "§3", merge: "§6", publicCode: "§4", names: "§5, §6" };

// Een link en geen <button>: een knop in een <label> wordt het gelabelde element, en dan opent een klik
// op de labeltekst de hulp in plaats van het veld te focussen.
function helpLink(topic) {
  const title = escapeHtml(HELP_TEXTS[topic].title);
  return `<a class="help-link" href="#" role="button" data-help="${topic}" title="${title}" aria-label="${title}">?</a>`;
}

// Een eigen <dialog>: die ligt boven #dialog-box, dus een half ingevuld formulier blijft staan.
function wireHelp() {
  document.addEventListener("click", (event) => {
    const topic = event.target.closest("[data-help]")?.dataset.help;
    if (!topic) return;
    event.preventDefault();
    document.getElementById("help-title").textContent = HELP_TEXTS[topic].title;
    document.getElementById("help-body").innerHTML = HELP_TEXTS[topic].body;
    document.getElementById("help-source").textContent = `Business Rules CHB ${HELP_SECTIONS[topic]}`;
    document.getElementById("help-dialog").showModal();
  });
}

function showDialog(html) {
  document.getElementById("dialog-box").innerHTML = html;
  document.getElementById("dialog-overlay").hidden = false;
}

function closeDialog() {
  document.getElementById("dialog-overlay").hidden = true;
  document.getElementById("dialog-box").innerHTML = "";
}

/**
 * Waarschuwing bij een reeds bestaande gelijknamige groeperingsknoop (backend antwoordt met 409).
 * Vervangt tijdelijk het open dialoogvenster; geeft true terug als de curator toch wil doorgaan,
 * false bij annuleren. Bij "toch doorgaan" blijft de waarschuwing staan tot de herhaalde aanvraag
 * slaagt (die roept dan zelf closeDialog aan).
 */
function confirmDuplicateName(name) {
  return new Promise((resolve) => {
    showDialog(`
      <h2>${escapeHtml(t("group.duplicateTitle"))}</h2>
      <p>${escapeHtml(t("group.duplicateWarn", { name }))}</p>
      <div class="dialog-actions">
        <button type="button" class="secondary" id="dup-cancel">${escapeHtml(t("common.cancel"))}</button>
        <button type="button" id="dup-proceed">${escapeHtml(t("group.duplicateProceed"))}</button>
      </div>
    `);
    document.getElementById("dup-cancel").addEventListener("click", () => { closeDialog(); resolve(false); });
    document.getElementById("dup-proceed").addEventListener("click", () => resolve(true));
  });
}

// --- Init --------------------------------------------------------------------

async function init() {
  setLang(initialLang());
  applyStaticTranslations(document);
  applyLangSwitchUi();
  wireLangSwitch();
  wireLogout();
  wireHelp();
  initMap();
  wireSearch();
  wireCreateNode();
  wireGroupMode();
  wireMergeMode();
  wireReport();
  wireUnreviewedMapToggle();
  wireShowDisabledToggle();
  state.showOnlyUnreviewed = initialUnreviewedToggle();
  state.showDisabled = initialDisabledToggle();
  // Kaart laden ook als een van beide faalt: die heeft ze niet nodig (zonder /api/me = anoniem).
  await Promise.all([loadMe().catch(showError), loadStopPlaceTypes().catch(showError)]);
  applyUnreviewedToggleUi();
  applyDisabledToggleUi();
  await loadStops();
  wireMapReloadOnMove();
}

document.addEventListener("DOMContentLoaded", init);
