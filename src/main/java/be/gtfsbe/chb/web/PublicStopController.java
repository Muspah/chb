package be.gtfsbe.chb.web;

import be.gtfsbe.chb.dto.BrusselsNames;
import be.gtfsbe.chb.dto.DuplicateNameGroup;
import be.gtfsbe.chb.dto.GroupCandidate;
import be.gtfsbe.chb.dto.DuplicateQuay;
import be.gtfsbe.chb.dto.ModeMismatch;
import be.gtfsbe.chb.dto.MultimodeQuay;
import be.gtfsbe.chb.dto.NameIssue;
import be.gtfsbe.chb.dto.ReviewStats;
import be.gtfsbe.chb.dto.StopDetail;
import be.gtfsbe.chb.dto.StopHistoryEntry;
import be.gtfsbe.chb.dto.StopPlaceTypeOption;
import be.gtfsbe.chb.dto.StopSearchResult;
import be.gtfsbe.chb.dto.TecCityIssue;
import be.gtfsbe.chb.dto.UnreviewedStop;
import be.gtfsbe.chb.repository.StopMapCache;
import be.gtfsbe.chb.repository.StopViewerRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Leest uitsluitend via de chb_viewer-pool ({@link StopViewerRepository}), zowel voor publieke
 * bezoekers als voor ingelogde gebruikers (build-instruction.md §5). Welke van deze routes
 * publiek zijn en welke een sessie vereisen (met name {@code /history}) staat vastgelegd in
 * {@link be.gtfsbe.chb.config.SecurityConfig}, niet in deze klasse.
 *
 * <p>{@code Locale} komt uit de {@code Accept-Language}-header, die {@code apiFetch()} op de UI-taal zet
 * (issue #2: namen in de UI-taal).
 */
@RestController
public class PublicStopController {

    private final StopViewerRepository repository;
    private final StopMapCache mapCache;

    public PublicStopController(StopViewerRepository repository, StopMapCache mapCache) {
        this.repository = repository;
        this.mapCache = mapCache;
    }

    /** Voor externe monitoring: 200 als de app draait en de database antwoordt, anders een fout. */
    @GetMapping("/api/health")
    public String health() {
        repository.ping();
        return "ok";
    }

    /** Alle kaartpunten in één keer, compact (zie {@link StopViewerRepository#findMapRows}). */
    @GetMapping("/api/stops")
    public ResponseEntity<byte[]> stops(@RequestParam(name = "showDisabled", defaultValue = "false") boolean showDisabled,
                                        Locale locale) {
        return mapResponse(mapCache.get(false, showDisabled, locale.getLanguage()));
    }

    @GetMapping("/api/stops/{id}")
    public StopDetail stop(@PathVariable long id, Locale locale) {
        return repository.findDetail(id, locale.getLanguage()).orElseThrow(() -> new StopNotFoundException(id));
    }

    @GetMapping("/api/search")
    public List<StopSearchResult> search(@RequestParam("q") String query, Locale locale) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return repository.search(query, locale.getLanguage());
    }

    @GetMapping("/api/stop-place-types")
    public List<StopPlaceTypeOption> stopPlaceTypes() {
        return repository.findStopPlaceTypes();
    }

    @GetMapping("/api/stops/{id}/history")
    public List<StopHistoryEntry> history(@PathVariable long id) {
        if (!repository.exists(id)) {
            throw new StopNotFoundException(id);
        }
        return repository.findHistory(id);
    }

    // --- Doorlichtingsrapport (curator-only, zie SecurityConfig) -------------------------------

    @GetMapping("/api/review/stats")
    public ReviewStats reviewStats() {
        return repository.reviewStats();
    }

    @GetMapping("/api/review/unreviewed-map")
    public ResponseEntity<byte[]> unreviewedMap(@RequestParam(name = "showDisabled", defaultValue = "false") boolean showDisabled,
                                                Locale locale) {
        return mapResponse(mapCache.get(true, showDisabled, locale.getLanguage()));
    }

    @GetMapping("/api/review/unreviewed")
    public List<UnreviewedStop> unreviewed(@RequestParam(name = "q", defaultValue = "") String query,
                                            @RequestParam(name = "limit", defaultValue = "50") int limit,
                                            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return repository.findUnreviewed(query, cappedLimit, Math.max(offset, 0));
    }

    @GetMapping("/api/review/duplicate-names")
    public List<DuplicateNameGroup> duplicateNames(@RequestParam(name = "q", defaultValue = "") String query,
                                                     @RequestParam(name = "limit", defaultValue = "50") int limit,
                                                     @RequestParam(name = "offset", defaultValue = "0") int offset) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return repository.findDuplicateNames(query, cappedLimit, Math.max(offset, 0));
    }

    @GetMapping("/api/review/name-issues")
    public List<NameIssue> nameIssues(@RequestParam(name = "reason") String reason,
                                      @RequestParam(name = "feed", defaultValue = "") String feed,
                                      @RequestParam(name = "limit", defaultValue = "50") int limit,
                                      @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (!StopViewerRepository.isNameIssueReason(reason)) {
            throw new InvalidRequestException("Onbekende reden: " + reason);
        }
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return repository.findNameIssues(reason, feed, cappedLimit, Math.max(offset, 0));
    }

    @GetMapping("/api/review/tec-cities")
    public List<TecCityIssue> tecCities() {
        return repository.findTecCityIssues();
    }

    @GetMapping("/api/review/multimode-quays")
    public List<MultimodeQuay> multimodeQuays() {
        return repository.findMultimodeQuays();
    }

    @GetMapping("/api/review/group-candidates")
    public List<GroupCandidate> groupCandidates() {
        return repository.findGroupCandidates();
    }

    @GetMapping("/api/review/brussels-names")
    public BrusselsNames brusselsNames() {
        return repository.findBrusselsNames();
    }

    @GetMapping("/api/review/mode-mismatches")
    public List<ModeMismatch> modeMismatches() {
        return repository.findModeMismatches();
    }

    @GetMapping("/api/review/duplicate-quays")
    public List<DuplicateQuay> duplicateQuays() {
        return repository.findDuplicateQuays();
    }

    @GetMapping("/api/review/feeds")
    public List<String> feeds() {
        return repository.findFeeds();
    }

    // no-cache + ETag: de browser bewaart de set en vraagt telkens of hij nog klopt (304 zonder body).
    // Expliciet gezet, anders zet Spring Security no-store en is er niets om te hervalideren.
    private static ResponseEntity<byte[]> mapResponse(StopMapCache.Snapshot snapshot) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noCache())
            .eTag(snapshot.etag()).body(snapshot.json());
    }
}
