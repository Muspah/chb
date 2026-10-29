"use strict";

/**
 * Vertalingen voor de UI-chrome (knoppen, labels, statische/dynamische teksten in app.js).
 * Bewust enkel client-side: backend-foutmeldingen (InvalidRequestException e.d.) blijven
 * Nederlands, dat was een expliciete scope-keuze bij het invoeren van i18n — zie Claude/CLAUDE.md.
 * Standaardtaal is Engels; de statische tekst in index.html is dus in het Engels geschreven en
 * `data-i18n`-attributen laten deze module ze nadien overschrijven zodra een andere taal gekozen
 * is (geen flits van een andere taal bij het laden, want "en" is toch al de brontekst).
 */

const SUPPORTED_LANGS = ["en", "nl", "de", "fr"];
const DEFAULT_LANG = "en";
const LANG_STORAGE_KEY = "chb-lang";

// Voor Date#toLocaleString — zelfde landen/conventie als de rest van de tool (België-context),
// enkel de taal verschilt.
const LOCALE_MAP = { en: "en-GB", nl: "nl-BE", de: "de-DE", fr: "fr-BE" };

let currentLang = DEFAULT_LANG;

function getLang() {
  return currentLang;
}

function getLocale() {
  return LOCALE_MAP[currentLang] || LOCALE_MAP[DEFAULT_LANG];
}

// Zelfde patroon als initialUnreviewedToggle() in app.js: URL-query weegt het zwaarst (expliciet,
// deelbare link), dan localStorage (vangnet voor de Entra ID-login-redirect, zie ontwerpbeslissing
// 5 in Claude/CLAUDE.md — geldt hier evengoed), dan pas de vaste standaardtaal.
function initialLang() {
  const url = new URL(location.href);
  const urlLang = url.searchParams.get("lang");
  if (urlLang && SUPPORTED_LANGS.includes(urlLang)) return urlLang;
  try {
    const stored = localStorage.getItem(LANG_STORAGE_KEY);
    if (stored && SUPPORTED_LANGS.includes(stored)) return stored;
  } catch (e) { /* bv. privénavigatie zonder opslag toegestaan */ }
  return DEFAULT_LANG;
}

function setLang(lang) {
  currentLang = SUPPORTED_LANGS.includes(lang) ? lang : DEFAULT_LANG;
  const url = new URL(location.href);
  if (currentLang === DEFAULT_LANG) {
    url.searchParams.delete("lang");
  } else {
    url.searchParams.set("lang", currentLang);
  }
  history.replaceState(null, "", url);
  try {
    localStorage.setItem(LANG_STORAGE_KEY, currentLang);
  } catch (e) { /* geen vangnet, verder onschadelijk */ }
  document.documentElement.lang = currentLang;
}

function t(key, vars) {
  const dict = translations[currentLang] || translations[DEFAULT_LANG];
  let str = dict[key] ?? translations[DEFAULT_LANG][key] ?? key;
  if (vars) {
    for (const name of Object.keys(vars)) {
      str = str.replaceAll(`{${name}}`, vars[name]);
    }
  }
  return str;
}

// Zet alle [data-i18n]/[data-i18n-placeholder]/[data-i18n-aria-label]-elementen binnen root naar
// de huidige taal. Enkel voor de statische index.html-elementen — dynamisch, door app.js
// gegenereerde HTML roept t() rechtstreeks aan bij het opbouwen van de template-string.
function applyStaticTranslations(root) {
  root.querySelectorAll("[data-i18n]").forEach((el) => {
    el.textContent = t(el.getAttribute("data-i18n"));
  });
  root.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
    el.placeholder = t(el.getAttribute("data-i18n-placeholder"));
  });
  root.querySelectorAll("[data-i18n-aria-label]").forEach((el) => {
    el.setAttribute("aria-label", t(el.getAttribute("data-i18n-aria-label")));
  });
}

const translations = {
  en: {
    "app.tagline": "",
    "app.langSwitchAria": "Language",
    "app.exportDownload": "Download NeTEx export",

    "common.yes": "Yes",
    "common.no": "No",
    "common.save": "Save",
    "common.cancel": "Cancel",
    "common.create": "Create",
    "common.close": "Close",
    "common.name": "Name",
    "common.key": "Key",
    "common.value": "Value",
    "common.none": "(none)",
    "common.loading": "Loading…",

    "search.placeholder": "Search by name or platform code…",

    "auth.signIn": "Sign in",
    "auth.signOut": "Sign out",
    "auth.signedIn": "Signed in",

    "map.hint": "Too many stops in this view — zoom in for more detail.",
    "map.attribution": "&copy; OpenStreetMap contributors",
    "map.stopFallbackName": "Stop {id}",
    "map.disabledSince": "disabled since {date}",

    "toolbar.newNode": "New grouping node",
    "toolbar.groupStops": "Group stops",
    "toolbar.mergeStops": "Merge stops",
    "toolbar.groupNow": "Group now",
    "toolbar.report": "Review report",
    "toolbar.unreviewedOnly": "Map: unreviewed only",
    "toolbar.showAll": "Map: show all stops",
    "toolbar.showDisabled": "Show disabled stops",
    "toolbar.hideDisabled": "Hide disabled stops",

    "stopType.none": "(grouping)",
    "stopType.emptyOption": "(no type — grouping node)",
    "stopType.label": "Type (NeTEx StopPlaceType)",

    "panel.noPath": "(no path available)",
    "panel.syntheticNoSource": "(synthetic node, no source data)",
    "panel.childrenHeading": "Underlying stop places ({count})",
    "panel.childrenHint": "This stop is a grouping of several physical stop places — click through for each one's own details (own location, own sources).",
    "panel.reviewHeading": "Review",
    "panel.reviewedOn": "Reviewed on {date}.",
    "panel.reopen": "Reopen",
    "panel.notReviewedYet": "Not reviewed yet.",
    "panel.markReviewed": "Mark as reviewed",
    "panel.deletedHeading": "Deleted",
    "panel.deletedText": "This grouping node was deleted on {date}. It no longer appears on the map or in search results.",
    "panel.restore": "Restore",
    "panel.manageHeading": "Manage",
    "panel.deleteNode": "Delete grouping node",
    "panel.locationHeading": "Location",
    "panel.recomputeHint": "Recalculate this grouping node's marker position as the average of its underlying stops.",
    "panel.recomputeLocation": "Recompute location",
    "panel.resetLocation": "Reset to feed location",
    "panel.resetLocationConfirm": "Reset this stop to the location from the source feed?",
    "panel.moveLocation": "Move location",
    "panel.moveHint": "Click, then drag this stop's marker on the map to its new position.",
    "panel.moveActive": "Drag the marker now…",
    "panel.moveNoMarker": "This stop is not on the current map view (maybe filtered out).",
    "panel.reviewedBadge": "reviewed",
    "panel.disabledBadge": "disabled",
    "panel.disabledSince": "Disabled since {date}",
    "panel.publicCode": "Platform code",
    "panel.origin": "Source",
    "panel.firstSeen": "First seen",
    "panel.lastSeen": "Last seen",
    "panel.synthetic": "Synthetic",
    "panel.attributesHeading": "Attributes",
    "panel.attrDelete": "delete",
    "panel.attrDeleteConfirm": "Delete attribute '{key}'?",
    "panel.editHeading": "Edit",
    "panel.parentIdLabel": "Parent ID (empty = detach)",
    "panel.pickParent": "Pick parent on map/search",
    "panel.attrAddHeading": "Add/change attribute",
    "panel.historyHeading": "History",
    "panel.historyUnavailable": "Not available.",
    "panel.historyEmpty": "No changes logged yet.",
    "panel.historyWhen": "When",
    "panel.historyWho": "Who",
    "panel.historyType": "Type",
    "panel.historyChange": "Change",
    "panel.historyNote": "Note",
    "panel.deleteConfirm": "Delete \"{name}\"? It will disappear from the map and from search results. There is no list of deleted nodes in the UI — restoring is only possible if you later land on this stop again (e.g. via a saved link) and click \"Restore\".",

    "error.selfParent": "A stop cannot be its own parent.",
    "error.parentIsDescendant": "Invalid: the chosen parent is a descendant of this stop.",

    "picking.hint": "Click a stop on the map, or search and click a result.",

    "create.heading": "New grouping node",
    "create.parentIdOptional": "Parent ID (optional)",
    "create.latOptional": "Latitude (optional)",
    "create.lonOptional": "Longitude (optional)",

    "group.pickHint": "Click the stops you want to group (on the map or via search).",
    "group.pickedCount": "{count} selected: {names}",
    "kind.general": "general",
    "kind.monomodal": "monomodal",
    "kind.wrapper": "single stop (wrapper)",
    "panel.transportMode": "Transport mode",
    "panel.generalNoMode": "This general StopPlace has no transport mode. It normally follows from its children; check that they have one, otherwise the export fails.",
    "panel.generalSingleMode": "This general has only one transport mode. A general is only for a hub with several modes (Business Rules CHB §1); with one mode, group the quays into a single monomodal StopPlace.",
    "group.explainQuays": "The quays of these {count} stops are moved into one new monomodal StopPlace. The quays keep their own location, sources and history; the single-stop wrappers disappear. On the map the new StopPlace appears as an area around its quays.",
    "group.explainStopPlaces": "These {count} StopPlaces are placed under one new general StopPlace (a multimodal hub). They keep their own quays, type and names. Only monomodal StopPlaces can go under a general, not single-stop wrappers or quays.",
    "group.typeRule": "Type of a general: that of the dominant mode (railStation if there is rail, otherwise metroStation if there is metro, and so on).",
    "group.typeSuggested": "Dominant mode here: {mode}, so {type} is preselected.",
    "group.singleModeWarn": "All these StopPlaces have the same single transport mode ({mode}). A general is only for several modes; with one mode, group their quays into one monomodal StopPlace instead.",
    "group.nameLabel": "Group name",
    "group.typeLabel": "Group type (NeTEx StopPlaceType)",
    "group.confirmSubmit": "Group",
    "group.duplicateTitle": "Name already in use",
    "group.duplicateWarn": "A grouping node named “{name}” already exists. Create this one anyway?",
    "group.duplicateProceed": "Create anyway",
    "error.goTo": "Go to {id}",
    "panel.wrapperHeading": "Wrapper StopPlace",
    "panel.wrapperHint": "This StopPlace is an automatic wrapper around a single quay: its name, code and location follow that quay. Edit the quay, or promote the wrapper to give the StopPlace a name of its own.",
    "panel.promote": "Promote to StopPlace",
    "promote.heading": "Promote wrapper",
    "promote.explain": "From now on this StopPlace has its own name and no longer follows its quay. This cannot be undone silently.",
    "promote.nameLabel": "Name of the StopPlace",
    "promote.submit": "Promote",
    "panel.quayBadge": "quay",
    "merge.needQuay": "Merging works on quays: pick single stops (or wrappers), not a StopPlace with several quays.",
    "report.rejections": "Actions rejected by the database since server start: {list}.",

    "merge.startHint": "First click the stop that will disappear (source), then the stop that remains (target).",
    "merge.sourcePicked": "Source: {name} — now click the target stop.",
    "merge.sameStop": "Source and target are the same stop.",
    "merge.explain1": "<strong>{source}</strong> (sources: {sourceFeeds}) will be merged into <strong>{target}</strong> (current sources: {targetFeeds}).",
    "merge.explain2": "The name <strong>{target}</strong> is kept. <strong>{source}</strong> disappears from the map as an active stop, but remains in the audit trail. Attributes of <strong>{source}</strong> move along too, unless <strong>{target}</strong> already has a value for the same key — in that case <strong>{target}</strong>'s value is kept and <strong>{source}</strong>'s is not applied.",
    "merge.crossOperator": "These stops come from different operators. Only merge if it is the same physical pole (same place <em>and</em> same direction); otherwise group them under one StopPlace. After the merge, the next import sets the names per language from the sources.",
    "merge.confirmSubmit": "Confirm merge",

    "report.filterPlaceholder": "Filter by name…",
    "report.loadMore": "Load more",
    "report.stats": "{reviewed} of {total} quays handled ({pct}%): {grouped} grouped, {standalone} OK as standalone stop. Worklist: {remaining} stops to review.",

    "toolbar.duplicateNames": "Duplicate names",
    "duplicateNames.hint": "Names carried by both a reviewed and an unreviewed stop — the unreviewed ones may have been missed.",
    "duplicateNames.group": "{name} ({reviewed} reviewed)",
  },

  nl: {
    "app.tagline": "",
    "app.langSwitchAria": "Taal",
    "app.exportDownload": "NeTEx-export downloaden",

    "common.yes": "Ja",
    "common.no": "Nee",
    "common.save": "Opslaan",
    "common.cancel": "Annuleren",
    "common.create": "Aanmaken",
    "common.close": "Sluiten",
    "common.name": "Naam",
    "common.key": "Sleutel",
    "common.value": "Waarde",
    "common.none": "(geen)",
    "common.loading": "Bezig met laden…",

    "search.placeholder": "Zoek op naam of perroncode…",

    "auth.signIn": "Inloggen",
    "auth.signOut": "Uitloggen",
    "auth.signedIn": "Ingelogd",

    "map.hint": "Te veel haltes in deze weergave — zoom in voor meer detail.",
    "map.attribution": "&copy; OpenStreetMap-auteurs",
    "map.stopFallbackName": "Halte {id}",
    "map.disabledSince": "uitgeschakeld sinds {date}",

    "toolbar.newNode": "Groeperingsknoop",
    "toolbar.groupStops": "Haltes groeperen",
    "toolbar.mergeStops": "Haltes samenvoegen",
    "toolbar.groupNow": "Groepeer nu",
    "toolbar.report": "Doorlichtingsrapport",
    "toolbar.unreviewedOnly": "Kaart: enkel nog te bekijken",
    "toolbar.showAll": "Kaart: alle haltes tonen",
    "toolbar.showDisabled": "Toon uitgeschakelde haltes",
    "toolbar.hideDisabled": "Verberg uitgeschakelde haltes",

    "stopType.none": "(groepering)",
    "stopType.emptyOption": "(geen type — groeperingsknoop)",
    "stopType.label": "Type (NeTEx StopPlaceType)",

    "panel.noPath": "(geen pad beschikbaar)",
    "panel.syntheticNoSource": "(synthetische knoop, geen brondata)",
    "panel.childrenHeading": "Onderliggende stopplaatsen ({count})",
    "panel.childrenHint": "Deze halte is een groepering van meerdere fysieke stopplaatsen — klik voor de details van elk apart (eigen locatie, eigen bronnen).",
    "panel.reviewHeading": "Doorlichting",
    "panel.reviewedOn": "Nagekeken op {date}.",
    "panel.reopen": "Heropenen",
    "panel.notReviewedYet": "Nog niet nagekeken.",
    "panel.markReviewed": "Markeer als nagekeken",
    "panel.deletedHeading": "Verwijderd",
    "panel.deletedText": "Deze groeperingsknoop is verwijderd op {date}. Ze staat niet meer op de kaart en niet meer in zoekresultaten.",
    "panel.restore": "Herstellen",
    "panel.manageHeading": "Beheer",
    "panel.deleteNode": "Groeperingsknoop verwijderen",
    "panel.locationHeading": "Locatie",
    "panel.recomputeHint": "Herbereken de markerpositie van deze groeperingsknoop als het gemiddelde van de onderliggende haltes.",
    "panel.recomputeLocation": "Locatie herberekenen",
    "panel.resetLocation": "Terug naar feedlocatie",
    "panel.resetLocationConfirm": "Deze halte terugzetten naar de locatie uit de bronfeed?",
    "panel.moveLocation": "Locatie verplaatsen",
    "panel.moveHint": "Klik, en sleep daarna de marker van deze halte op de kaart naar de nieuwe positie.",
    "panel.moveActive": "Sleep nu de marker…",
    "panel.moveNoMarker": "Deze halte staat niet op de huidige kaart (mogelijk uitgefilterd).",
    "panel.reviewedBadge": "nagekeken",
    "panel.disabledBadge": "uitgeschakeld",
    "panel.disabledSince": "Uitgeschakeld sinds {date}",
    "panel.publicCode": "Perroncode",
    "panel.origin": "Herkomst",
    "panel.firstSeen": "Eerst gezien",
    "panel.lastSeen": "Laatst gezien",
    "panel.synthetic": "Synthetisch",
    "panel.attributesHeading": "Attributen",
    "panel.attrDelete": "verwijder",
    "panel.attrDeleteConfirm": "Attribuut '{key}' verwijderen?",
    "panel.editHeading": "Bewerken",
    "panel.parentIdLabel": "Parent-ID (leeg = loskoppelen)",
    "panel.pickParent": "Kies parent op kaart/zoek",
    "panel.attrAddHeading": "Attribuut toevoegen/wijzigen",
    "panel.historyHeading": "Geschiedenis",
    "panel.historyUnavailable": "Niet beschikbaar.",
    "panel.historyEmpty": "Nog geen wijzigingen gelogd.",
    "panel.historyWhen": "Wanneer",
    "panel.historyWho": "Wie",
    "panel.historyType": "Type",
    "panel.historyChange": "Wijziging",
    "panel.historyNote": "Nota",
    "panel.deleteConfirm": "\"{name}\" verwijderen? Ze verdwijnt van de kaart en uit zoekresultaten. Er is geen lijst met verwijderde knopen in de UI — herstellen kan enkel als je later opnieuw op deze halte terechtkomt (bv. via een bewaarde link) en op \"Herstellen\" klikt.",

    "error.selfParent": "Een halte kan niet zijn eigen parent zijn.",
    "error.parentIsDescendant": "Ongeldig: de gekozen parent is een afstammeling van deze halte.",

    "picking.hint": "Klik op een halte op de kaart, of zoek en klik een zoekresultaat aan.",

    "create.heading": "Nieuwe groeperingsknoop",
    "create.parentIdOptional": "Parent-ID (optioneel)",
    "create.latOptional": "Latitude (optioneel)",
    "create.lonOptional": "Longitude (optioneel)",

    "group.pickHint": "Klik de haltes aan die je wil groeperen (op de kaart of via zoeken).",
    "group.pickedCount": "{count} gekozen: {names}",
    "kind.general": "general",
    "kind.monomodal": "monomodaal",
    "kind.wrapper": "losse halte (wrapper)",
    "panel.transportMode": "Vervoerswijze",
    "panel.generalNoMode": "Deze general-StopPlace heeft geen vervoerswijze. Die volgt normaal uit zijn kinderen; kijk na of die er een hebben, anders faalt de export.",
    "panel.generalSingleMode": "Deze general heeft maar één vervoerswijze. Een general is alleen voor een knoop met meerdere modaliteiten (Business Rules CHB §1); bij één modaliteit horen de quays in één monomodale StopPlace.",
    "group.explainQuays": "De quays van deze {count} haltes komen samen in één nieuwe monomodale StopPlace. De quays behouden hun eigen locatie, bronnen en geschiedenis; de wrappers van de losse haltes verdwijnen. Op de kaart verschijnt de nieuwe StopPlace als een vlak rond zijn quays.",
    "group.explainStopPlaces": "Deze {count} StopPlaces komen onder één nieuwe general-StopPlace (een multimodale knoop). Ze behouden hun eigen quays, type en namen. Alleen monomodale StopPlaces kunnen onder een general, geen wrappers van losse haltes en geen quays.",
    "group.typeRule": "Type van een general: dat van de dominante modus (railStation als er rail in zit, anders metroStation als er metro in zit, enzovoort).",
    "group.typeSuggested": "Dominante modus hier: {mode}, dus {type} is voorgeselecteerd.",
    "group.singleModeWarn": "Al deze StopPlaces hebben dezelfde ene vervoerswijze ({mode}). Een general is alleen voor meerdere modaliteiten; groepeer bij één modaliteit liever hun quays tot één monomodale StopPlace.",
    "group.nameLabel": "Naam van de groep",
    "group.typeLabel": "Type van de groep (NeTEx StopPlaceType)",
    "group.confirmSubmit": "Groepeer",
    "group.duplicateTitle": "Naam al in gebruik",
    "group.duplicateWarn": "Er bestaat al een groeperingsknoop met de naam “{name}”. Toch aanmaken?",
    "group.duplicateProceed": "Toch aanmaken",
    "error.goTo": "Ga naar {id}",
    "panel.wrapperHeading": "Wrapper-StopPlace",
    "panel.wrapperHint": "Deze StopPlace is een automatische wrapper rond één quay: naam, code en locatie volgen die quay. Wijzig de quay, of promoveer de wrapper om de StopPlace een eigen naam te geven.",
    "panel.promote": "Promoveren tot StopPlace",
    "promote.heading": "Wrapper promoveren",
    "promote.explain": "Vanaf nu heeft deze StopPlace een eigen naam en volgt hij zijn quay niet meer. Dit kan niet stilzwijgend teruggezet worden.",
    "promote.nameLabel": "Naam van de StopPlace",
    "promote.submit": "Promoveren",
    "panel.quayBadge": "quay",
    "merge.needQuay": "Samenvoegen werkt op quays: kies losse haltes (of wrappers), geen StopPlace met meerdere quays.",
    "report.rejections": "Door de database geweigerde acties sinds serverstart: {list}.",

    "merge.startHint": "Klik eerst de halte die verdwijnt (bron), dan de halte die overblijft (doel).",
    "merge.sourcePicked": "Bron: {name} — klik nu de doel-halte aan.",
    "merge.sameStop": "Bron en doel zijn dezelfde halte.",
    "merge.explain1": "<strong>{source}</strong> (bronnen: {sourceFeeds}) wordt samengevoegd in <strong>{target}</strong> (huidige bronnen: {targetFeeds}).",
    "merge.explain2": "De naam <strong>{target}</strong> blijft behouden. <strong>{source}</strong> verdwijnt van de kaart als levende halte, maar blijft in de audit trail staan. Attributen van <strong>{source}</strong> verhuizen mee, behalve als <strong>{target}</strong> al een waarde heeft voor dezelfde sleutel — dan blijft de waarde van <strong>{target}</strong> staan en wordt die van <strong>{source}</strong> niet overgenomen.",
    "merge.crossOperator": "Deze haltes komen van verschillende vervoerders. Voeg alleen samen als het dezelfde fysieke paal is (zelfde plek <em>én</em> zelfde richting); anders groepeer je ze onder één StopPlace. Na het samenvoegen zet de volgende import de namen per taal volgens de bronnen.",
    "merge.confirmSubmit": "Bevestig samenvoegen",

    "report.filterPlaceholder": "Filter op naam…",
    "report.loadMore": "Meer laden",
    "report.stats": "{reviewed} van {total} quays afgehandeld ({pct}%): {grouped} gegroepeerd, {standalone} akkoord als losse halte. Werkvoorraad: nog {remaining} haltes te bekijken.",

    "toolbar.duplicateNames": "Gelijknamige haltes",
    "duplicateNames.hint": "Namen die zowel bij een nagekeken als bij een nog niet nagekeken halte horen — de niet-nagekeken exemplaren zijn mogelijk gemist.",
    "duplicateNames.group": "{name} ({reviewed} nagekeken)",
  },

  de: {
    "app.tagline": "",
    "app.langSwitchAria": "Sprache",
    "app.exportDownload": "NeTEx-Export herunterladen",

    "common.yes": "Ja",
    "common.no": "Nein",
    "common.save": "Speichern",
    "common.cancel": "Abbrechen",
    "common.create": "Erstellen",
    "common.close": "Schließen",
    "common.name": "Name",
    "common.key": "Schlüssel",
    "common.value": "Wert",
    "common.none": "(keine)",
    "common.loading": "Wird geladen…",

    "search.placeholder": "Suche nach Name oder Bahnsteigcode…",

    "auth.signIn": "Anmelden",
    "auth.signOut": "Abmelden",
    "auth.signedIn": "Angemeldet",

    "map.hint": "Zu viele Haltestellen in dieser Ansicht — zoomen Sie hinein für mehr Details.",
    "map.attribution": "&copy; OpenStreetMap-Mitwirkende",
    "map.stopFallbackName": "Haltestelle {id}",
    "map.disabledSince": "deaktiviert seit {date}",

    "toolbar.newNode": "Neuer Gruppierungsknoten",
    "toolbar.groupStops": "Haltestellen gruppieren",
    "toolbar.mergeStops": "Haltestellen zusammenführen",
    "toolbar.groupNow": "Jetzt gruppieren",
    "toolbar.report": "Prüfbericht",
    "toolbar.unreviewedOnly": "Karte: nur ungeprüfte",
    "toolbar.showAll": "Karte: alle Haltestellen anzeigen",
    "toolbar.showDisabled": "Deaktivierte Haltestellen anzeigen",
    "toolbar.hideDisabled": "Deaktivierte Haltestellen ausblenden",

    "stopType.none": "(Gruppierung)",
    "stopType.emptyOption": "(kein Typ — Gruppierungsknoten)",
    "stopType.label": "Typ (NeTEx StopPlaceType)",

    "panel.noPath": "(kein Pfad verfügbar)",
    "panel.syntheticNoSource": "(synthetischer Knoten, keine Quelldaten)",
    "panel.childrenHeading": "Untergeordnete Haltestellen ({count})",
    "panel.childrenHint": "Diese Haltestelle ist eine Gruppierung mehrerer physischer Haltestellen — klicken Sie durch, um die Details jeder einzelnen zu sehen (eigener Standort, eigene Quellen).",
    "panel.reviewHeading": "Prüfung",
    "panel.reviewedOn": "Geprüft am {date}.",
    "panel.reopen": "Wiederöffnen",
    "panel.notReviewedYet": "Noch nicht geprüft.",
    "panel.markReviewed": "Als geprüft markieren",
    "panel.deletedHeading": "Gelöscht",
    "panel.deletedText": "Dieser Gruppierungsknoten wurde am {date} gelöscht. Er erscheint nicht mehr auf der Karte oder in den Suchergebnissen.",
    "panel.restore": "Wiederherstellen",
    "panel.manageHeading": "Verwaltung",
    "panel.deleteNode": "Gruppierungsknoten löschen",
    "panel.locationHeading": "Standort",
    "panel.recomputeHint": "Berechnet die Markerposition dieses Gruppierungsknotens als Durchschnitt der untergeordneten Haltestellen neu.",
    "panel.recomputeLocation": "Standort neu berechnen",
    "panel.resetLocation": "Auf Feed-Standort zurücksetzen",
    "panel.resetLocationConfirm": "Diese Haltestelle auf den Standort aus dem Quell-Feed zurücksetzen?",
    "panel.moveLocation": "Standort verschieben",
    "panel.moveHint": "Klicken und dann den Marker dieser Haltestelle auf der Karte an die neue Position ziehen.",
    "panel.moveActive": "Marker jetzt ziehen…",
    "panel.moveNoMarker": "Diese Haltestelle ist im aktuellen Kartenausschnitt nicht vorhanden (evtl. gefiltert).",
    "panel.reviewedBadge": "geprüft",
    "panel.disabledBadge": "deaktiviert",
    "panel.disabledSince": "Deaktiviert seit {date}",
    "panel.publicCode": "Bahnsteigcode",
    "panel.origin": "Herkunft",
    "panel.firstSeen": "Zuerst gesehen",
    "panel.lastSeen": "Zuletzt gesehen",
    "panel.synthetic": "Synthetisch",
    "panel.attributesHeading": "Attribute",
    "panel.attrDelete": "löschen",
    "panel.attrDeleteConfirm": "Attribut '{key}' löschen?",
    "panel.editHeading": "Bearbeiten",
    "panel.parentIdLabel": "Übergeordnete ID (leer = trennen)",
    "panel.pickParent": "Übergeordnetes Element auf Karte/Suche wählen",
    "panel.attrAddHeading": "Attribut hinzufügen/ändern",
    "panel.historyHeading": "Verlauf",
    "panel.historyUnavailable": "Nicht verfügbar.",
    "panel.historyEmpty": "Noch keine Änderungen protokolliert.",
    "panel.historyWhen": "Wann",
    "panel.historyWho": "Wer",
    "panel.historyType": "Typ",
    "panel.historyChange": "Änderung",
    "panel.historyNote": "Notiz",
    "panel.deleteConfirm": "\"{name}\" löschen? Sie verschwindet von der Karte und aus den Suchergebnissen. Es gibt keine Liste gelöschter Knoten in der Benutzeroberfläche — eine Wiederherstellung ist nur möglich, wenn Sie später erneut auf diese Haltestelle stoßen (z. B. über einen gespeicherten Link) und auf \"Wiederherstellen\" klicken.",

    "error.selfParent": "Eine Haltestelle kann nicht ihr eigenes übergeordnetes Element sein.",
    "error.parentIsDescendant": "Ungültig: Das gewählte übergeordnete Element ist ein Nachkomme dieser Haltestelle.",

    "picking.hint": "Klicken Sie auf eine Haltestelle auf der Karte, oder suchen und klicken Sie ein Suchergebnis an.",

    "create.heading": "Neuer Gruppierungsknoten",
    "create.parentIdOptional": "Übergeordnete ID (optional)",
    "create.latOptional": "Breitengrad (optional)",
    "create.lonOptional": "Längengrad (optional)",

    "group.pickHint": "Klicken Sie die Haltestellen an, die Sie gruppieren möchten (auf der Karte oder über die Suche).",
    "group.pickedCount": "{count} ausgewählt: {names}",
    "kind.general": "general",
    "kind.monomodal": "monomodal",
    "kind.wrapper": "Einzelhaltestelle (Wrapper)",
    "panel.transportMode": "Verkehrsmittel",
    "panel.generalNoMode": "Diese General-StopPlace hat kein Verkehrsmittel. Es ergibt sich normalerweise aus ihren Kindern; prüfen Sie, ob diese eines haben, sonst schlägt der Export fehl.",
    "panel.generalSingleMode": "Diese General hat nur ein Verkehrsmittel. Eine General ist nur für einen Knoten mit mehreren Verkehrsmitteln (Business Rules CHB §1); bei einem Verkehrsmittel gehören die Quays in eine monomodale StopPlace.",
    "group.explainQuays": "Die Quays dieser {count} Haltestellen kommen in eine neue monomodale StopPlace. Die Quays behalten ihren eigenen Standort, ihre Quellen und Historie; die Wrapper der Einzelhaltestellen verschwinden. Auf der Karte erscheint die neue StopPlace als Fläche um ihre Quays.",
    "group.explainStopPlaces": "Diese {count} StopPlaces kommen unter eine neue General-StopPlace (einen multimodalen Knoten). Sie behalten ihre eigenen Quays, ihren Typ und ihre Namen. Nur monomodale StopPlaces können unter eine General, keine Wrapper von Einzelhaltestellen und keine Quays.",
    "group.typeRule": "Typ einer General: der des dominanten Verkehrsmittels (railStation bei Bahn, sonst metroStation bei Metro usw.).",
    "group.typeSuggested": "Dominantes Verkehrsmittel hier: {mode}, daher ist {type} vorausgewählt.",
    "group.singleModeWarn": "Alle diese StopPlaces haben dasselbe einzige Verkehrsmittel ({mode}). Eine General ist nur für mehrere Verkehrsmittel; gruppieren Sie bei einem Verkehrsmittel stattdessen ihre Quays zu einer monomodalen StopPlace.",
    "group.nameLabel": "Name der Gruppe",
    "group.typeLabel": "Typ der Gruppe (NeTEx StopPlaceType)",
    "group.confirmSubmit": "Gruppieren",
    "group.duplicateTitle": "Name bereits vergeben",
    "group.duplicateWarn": "Es existiert bereits ein Gruppierungsknoten mit dem Namen „{name}“. Trotzdem anlegen?",
    "group.duplicateProceed": "Trotzdem anlegen",
    "error.goTo": "Gehe zu {id}",
    "panel.wrapperHeading": "Wrapper-StopPlace",
    "panel.wrapperHint": "Diese StopPlace ist ein automatischer Wrapper um genau ein Quay: Name, Code und Standort folgen diesem Quay. Ändere das Quay oder befördere den Wrapper, um der StopPlace einen eigenen Namen zu geben.",
    "panel.promote": "Zur StopPlace befördern",
    "promote.heading": "Wrapper befördern",
    "promote.explain": "Ab jetzt hat diese StopPlace einen eigenen Namen und folgt ihrem Quay nicht mehr. Das lässt sich nicht stillschweigend rückgängig machen.",
    "promote.nameLabel": "Name der StopPlace",
    "promote.submit": "Befördern",
    "panel.quayBadge": "Quay",
    "merge.needQuay": "Zusammenführen funktioniert auf Quays: wähle einzelne Haltestellen (oder Wrapper), keine StopPlace mit mehreren Quays.",
    "report.rejections": "Seit Serverstart von der Datenbank abgelehnte Aktionen: {list}.",

    "merge.startHint": "Klicken Sie zuerst die Haltestelle an, die verschwindet (Quelle), dann die Haltestelle, die bestehen bleibt (Ziel).",
    "merge.sourcePicked": "Quelle: {name} — klicken Sie nun die Zielhaltestelle an.",
    "merge.sameStop": "Quelle und Ziel sind dieselbe Haltestelle.",
    "merge.explain1": "<strong>{source}</strong> (Quellen: {sourceFeeds}) wird mit <strong>{target}</strong> zusammengeführt (aktuelle Quellen: {targetFeeds}).",
    "merge.explain2": "Der Name <strong>{target}</strong> bleibt erhalten. <strong>{source}</strong> verschwindet von der Karte als aktive Haltestelle, bleibt aber im Audit-Trail erhalten. Attribute von <strong>{source}</strong> wandern ebenfalls mit, außer wenn <strong>{target}</strong> bereits einen Wert für denselben Schlüssel hat — dann bleibt der Wert von <strong>{target}</strong> bestehen und der von <strong>{source}</strong> wird nicht übernommen.",
    "merge.crossOperator": "Diese Haltestellen stammen von verschiedenen Verkehrsunternehmen. Nur zusammenführen, wenn es derselbe physische Mast ist (gleicher Ort <em>und</em> gleiche Richtung); sonst unter einer StopPlace gruppieren. Nach dem Zusammenführen setzt der nächste Import die Namen pro Sprache gemäß den Quellen.",
    "merge.confirmSubmit": "Zusammenführen bestätigen",

    "report.filterPlaceholder": "Nach Name filtern…",
    "report.loadMore": "Mehr laden",
    "report.stats": "{reviewed} von {total} Quays erledigt ({pct}%): {grouped} gruppiert, {standalone} als Einzelhalt in Ordnung. Arbeitsvorrat: noch {remaining} Haltestellen zu prüfen.",

    "toolbar.duplicateNames": "Gleichnamige Haltestellen",
    "duplicateNames.hint": "Namen, die sowohl zu einer geprüften als auch zu einer ungeprüften Haltestelle gehören — die ungeprüften könnten übersehen worden sein.",
    "duplicateNames.group": "{name} ({reviewed} geprüft)",
  },

  fr: {
    "app.tagline": "",
    "app.langSwitchAria": "Langue",
    "app.exportDownload": "Télécharger l'export NeTEx",

    "common.yes": "Oui",
    "common.no": "Non",
    "common.save": "Enregistrer",
    "common.cancel": "Annuler",
    "common.create": "Créer",
    "common.close": "Fermer",
    "common.name": "Nom",
    "common.key": "Clé",
    "common.value": "Valeur",
    "common.none": "(aucune)",
    "common.loading": "Chargement…",

    "search.placeholder": "Rechercher par nom ou code de quai…",

    "auth.signIn": "Se connecter",
    "auth.signOut": "Se déconnecter",
    "auth.signedIn": "Connecté",

    "map.hint": "Trop d'arrêts dans cette vue — zoomez pour plus de détails.",
    "map.attribution": "&copy; contributeurs d'OpenStreetMap",
    "map.stopFallbackName": "Arrêt {id}",
    "map.disabledSince": "désactivé depuis le {date}",

    "toolbar.newNode": "Nouveau nœud de regroupement",
    "toolbar.groupStops": "Grouper les arrêts",
    "toolbar.mergeStops": "Fusionner les arrêts",
    "toolbar.groupNow": "Grouper maintenant",
    "toolbar.report": "Rapport de vérification",
    "toolbar.unreviewedOnly": "Carte : à vérifier seulement",
    "toolbar.showAll": "Carte : afficher tous les arrêts",
    "toolbar.showDisabled": "Afficher les arrêts désactivés",
    "toolbar.hideDisabled": "Masquer les arrêts désactivés",

    "stopType.none": "(regroupement)",
    "stopType.emptyOption": "(aucun type — nœud de regroupement)",
    "stopType.label": "Type (NeTEx StopPlaceType)",

    "panel.noPath": "(aucun chemin disponible)",
    "panel.syntheticNoSource": "(nœud synthétique, sans données source)",
    "panel.childrenHeading": "Arrêts sous-jacents ({count})",
    "panel.childrenHint": "Cet arrêt est un regroupement de plusieurs arrêts physiques — cliquez pour voir les détails de chacun (emplacement propre, sources propres).",
    "panel.reviewHeading": "Vérification",
    "panel.reviewedOn": "Vérifié le {date}.",
    "panel.reopen": "Rouvrir",
    "panel.notReviewedYet": "Pas encore vérifié.",
    "panel.markReviewed": "Marquer comme vérifié",
    "panel.deletedHeading": "Supprimé",
    "panel.deletedText": "Ce nœud de regroupement a été supprimé le {date}. Il n'apparaît plus sur la carte ni dans les résultats de recherche.",
    "panel.restore": "Restaurer",
    "panel.manageHeading": "Gestion",
    "panel.deleteNode": "Supprimer le nœud de regroupement",
    "panel.locationHeading": "Emplacement",
    "panel.recomputeHint": "Recalcule la position du marqueur de ce nœud de regroupement comme la moyenne des arrêts sous-jacents.",
    "panel.recomputeLocation": "Recalculer l'emplacement",
    "panel.resetLocation": "Rétablir l'emplacement du flux",
    "panel.resetLocationConfirm": "Rétablir cet arrêt à l'emplacement du flux source ?",
    "panel.moveLocation": "Déplacer l'emplacement",
    "panel.moveHint": "Cliquez, puis faites glisser le marqueur de cet arrêt sur la carte vers sa nouvelle position.",
    "panel.moveActive": "Faites glisser le marqueur…",
    "panel.moveNoMarker": "Cet arrêt n'est pas sur la carte actuelle (peut-être filtré).",
    "panel.reviewedBadge": "vérifié",
    "panel.disabledBadge": "désactivé",
    "panel.disabledSince": "Désactivé depuis le {date}",
    "panel.publicCode": "Code de quai",
    "panel.origin": "Origine",
    "panel.firstSeen": "Vu pour la première fois",
    "panel.lastSeen": "Vu pour la dernière fois",
    "panel.synthetic": "Synthétique",
    "panel.attributesHeading": "Attributs",
    "panel.attrDelete": "supprimer",
    "panel.attrDeleteConfirm": "Supprimer l'attribut « {key} » ?",
    "panel.editHeading": "Modifier",
    "panel.parentIdLabel": "ID parent (vide = détacher)",
    "panel.pickParent": "Choisir le parent sur la carte/recherche",
    "panel.attrAddHeading": "Ajouter/modifier un attribut",
    "panel.historyHeading": "Historique",
    "panel.historyUnavailable": "Non disponible.",
    "panel.historyEmpty": "Aucune modification enregistrée pour l'instant.",
    "panel.historyWhen": "Quand",
    "panel.historyWho": "Qui",
    "panel.historyType": "Type",
    "panel.historyChange": "Modification",
    "panel.historyNote": "Note",
    "panel.deleteConfirm": "Supprimer « {name} » ? Il disparaîtra de la carte et des résultats de recherche. Il n'existe pas de liste des nœuds supprimés dans l'interface — la restauration n'est possible que si vous retombez plus tard sur cet arrêt (par ex. via un lien enregistré) et cliquez sur « Restaurer ».",

    "error.selfParent": "Un arrêt ne peut pas être son propre parent.",
    "error.parentIsDescendant": "Non valide : le parent choisi est un descendant de cet arrêt.",

    "picking.hint": "Cliquez sur un arrêt sur la carte, ou recherchez et cliquez sur un résultat.",

    "create.heading": "Nouveau nœud de regroupement",
    "create.parentIdOptional": "ID parent (facultatif)",
    "create.latOptional": "Latitude (facultatif)",
    "create.lonOptional": "Longitude (facultatif)",

    "group.pickHint": "Cliquez sur les arrêts que vous souhaitez grouper (sur la carte ou via la recherche).",
    "group.pickedCount": "{count} sélectionné(s) : {names}",
    "kind.general": "général",
    "kind.monomodal": "monomodal",
    "kind.wrapper": "arrêt isolé (wrapper)",
    "panel.transportMode": "Mode de transport",
    "panel.generalNoMode": "Ce StopPlace général n'a pas de mode de transport. Il découle normalement de ses enfants ; vérifiez qu'ils en ont un, sinon l'export échoue.",
    "panel.generalSingleMode": "Ce général n'a qu'un seul mode de transport. Un général n'existe que pour un nœud à plusieurs modes (Business Rules CHB §1) ; avec un seul mode, les quais vont dans un seul StopPlace monomodal.",
    "group.explainQuays": "Les quais de ces {count} arrêts sont réunis dans un nouveau StopPlace monomodal. Les quais gardent leur emplacement, leurs sources et leur historique ; les wrappers des arrêts isolés disparaissent. Sur la carte, le nouveau StopPlace apparaît comme une zone autour de ses quais.",
    "group.explainStopPlaces": "Ces {count} StopPlaces sont placés sous un nouveau StopPlace général (un nœud multimodal). Ils gardent leurs quais, leur type et leurs noms. Seuls des StopPlaces monomodaux peuvent aller sous un général, pas des wrappers d'arrêts isolés ni des quais.",
    "group.typeRule": "Type d'un général : celui du mode dominant (railStation s'il y a du train, sinon metroStation s'il y a du métro, etc.).",
    "group.typeSuggested": "Mode dominant ici : {mode}, donc {type} est présélectionné.",
    "group.singleModeWarn": "Tous ces StopPlaces ont le même mode unique ({mode}). Un général n'existe que pour plusieurs modes ; avec un seul mode, regroupez plutôt leurs quais en un StopPlace monomodal.",
    "group.nameLabel": "Nom du groupe",
    "group.typeLabel": "Type du groupe (NeTEx StopPlaceType)",
    "group.confirmSubmit": "Grouper",
    "group.duplicateTitle": "Nom déjà utilisé",
    "group.duplicateWarn": "Un nœud de regroupement nommé « {name} » existe déjà. Le créer quand même ?",
    "group.duplicateProceed": "Créer quand même",
    "error.goTo": "Aller à {id}",
    "panel.wrapperHeading": "StopPlace enveloppe (wrapper)",
    "panel.wrapperHint": "Cette StopPlace est une enveloppe automatique autour d'un seul quai : son nom, son code et son emplacement suivent ce quai. Modifiez le quai, ou promouvez l'enveloppe pour donner à la StopPlace un nom propre.",
    "panel.promote": "Promouvoir en StopPlace",
    "promote.heading": "Promouvoir l'enveloppe",
    "promote.explain": "Désormais cette StopPlace a son propre nom et ne suit plus son quai. Cela ne peut pas être annulé silencieusement.",
    "promote.nameLabel": "Nom de la StopPlace",
    "promote.submit": "Promouvoir",
    "panel.quayBadge": "quai",
    "merge.needQuay": "La fusion s'applique aux quais : choisissez des arrêts simples (ou des enveloppes), pas une StopPlace avec plusieurs quais.",
    "report.rejections": "Actions rejetées par la base de données depuis le démarrage du serveur : {list}.",

    "merge.startHint": "Cliquez d'abord sur l'arrêt qui va disparaître (source), puis sur l'arrêt qui reste (cible).",
    "merge.sourcePicked": "Source : {name} — cliquez maintenant sur l'arrêt cible.",
    "merge.sameStop": "La source et la cible sont le même arrêt.",
    "merge.explain1": "<strong>{source}</strong> (sources : {sourceFeeds}) sera fusionné dans <strong>{target}</strong> (sources actuelles : {targetFeeds}).",
    "merge.explain2": "Le nom <strong>{target}</strong> est conservé. <strong>{source}</strong> disparaît de la carte en tant qu'arrêt actif, mais reste dans l'historique d'audit. Les attributs de <strong>{source}</strong> sont également déplacés, sauf si <strong>{target}</strong> possède déjà une valeur pour la même clé — dans ce cas, la valeur de <strong>{target}</strong> est conservée et celle de <strong>{source}</strong> n'est pas reprise.",
    "merge.crossOperator": "Ces arrêts proviennent d'opérateurs différents. Ne fusionnez que s'il s'agit du même poteau physique (même endroit <em>et</em> même direction) ; sinon, regroupez-les sous une StopPlace. Après la fusion, le prochain import fixe les noms par langue selon les sources.",
    "merge.confirmSubmit": "Confirmer la fusion",

    "report.filterPlaceholder": "Filtrer par nom…",
    "report.loadMore": "Charger plus",
    "report.stats": "{reviewed} quais traités sur {total} ({pct} %) : {grouped} regroupés, {standalone} validés comme arrêt isolé. À traiter : {remaining} arrêts.",

    "toolbar.duplicateNames": "Arrêts homonymes",
    "duplicateNames.hint": "Noms portés à la fois par un arrêt vérifié et un arrêt non vérifié — ce dernier a peut-être été oublié.",
    "duplicateNames.group": "{name} ({reviewed} vérifié(s))",
  },
};

// Vaste, geverifieerde NeTEx StopTypeEnumeration (15 waarden, zie ontwerpbeslissing 8 in
// Claude/CLAUDE.md) — eigen frontend-vertaling per taal, los van de DB-kolom `label_nl` die
// `GET /api/stop-place-types` teruggeeft (die is en blijft enkel Nederlands, zie i18n-scopekeuze
// hierboven). Een toekomstige, nog onbekende code (bv. na een DB-uitbreiding in de import-repo
// zonder gelijktijdige update hier) valt terug op die rauwe, Nederlandse API-label — zie
// stopTypeLabel() in app.js.
const STOP_TYPE_LABELS = {
  onstreetBus: { en: "On-street bus stop", nl: "Straathalte (bus)", de: "Straßenbushaltestelle", fr: "Arrêt de bus en voirie" },
  onstreetTram: { en: "On-street tram stop", nl: "Straathalte (tram)", de: "Straßenbahnhaltestelle", fr: "Arrêt de tram en voirie" },
  airport: { en: "Airport", nl: "Luchthaven", de: "Flughafen", fr: "Aéroport" },
  railStation: { en: "Railway station", nl: "Treinstation", de: "Bahnhof", fr: "Gare ferroviaire" },
  metroStation: { en: "Metro station", nl: "Metrostation", de: "U-Bahn-Station", fr: "Station de métro" },
  busStation: { en: "Bus station", nl: "Busstation", de: "Busbahnhof", fr: "Gare routière" },
  coachStation: { en: "Coach station", nl: "Autocarstation", de: "Fernbusbahnhof", fr: "Gare d'autocars" },
  tramStation: { en: "Tram station", nl: "Tramstation", de: "Straßenbahnstation", fr: "Station de tram" },
  harbourPort: { en: "Harbour port", nl: "Haven", de: "Hafen", fr: "Port" },
  ferryPort: { en: "Ferry port", nl: "Veerhaven", de: "Fährhafen", fr: "Port de ferry" },
  ferryStop: { en: "Ferry stop", nl: "Veerhalte", de: "Fährhaltestelle", fr: "Arrêt de ferry" },
  liftStation: { en: "Lift station", nl: "Kabelbaanstation", de: "Seilbahnstation", fr: "Station de téléphérique" },
  vehicleRailInterchange: { en: "Vehicle/rail interchange", nl: "Auto-treinoverstap", de: "Autoverladestation", fr: "Embarquement auto-train" },
  taxiRank: { en: "Taxi rank", nl: "Taxistandplaats", de: "Taxistand", fr: "Station de taxis" },
  other: { en: "Other", nl: "Overige", de: "Sonstiges", fr: "Autre" },
};

function translatedStopTypeLabel(code) {
  const entry = STOP_TYPE_LABELS[code];
  return entry ? (entry[currentLang] || entry[DEFAULT_LANG]) : null;
}
