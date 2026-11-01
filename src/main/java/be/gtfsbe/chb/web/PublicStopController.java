package be.gtfsbe.chb.web;

import be.gtfsbe.chb.dto.DuplicateNameGroup;
import be.gtfsbe.chb.dto.GroupCandidate;
import be.gtfsbe.chb.dto.ModeMismatch;
import be.gtfsbe.chb.dto.MultimodeQuay;
import be.gtfsbe.chb.dto.NameIssue;
import be.gtfsbe.chb.dto.GeoJsonFeatureCollection;
import be.gtfsbe.chb.dto.MapBounds;
import be.gtfsbe.chb.dto.ReviewStats;
import be.gtfsbe.chb.dto.StopDetail;
import be.gtfsbe.chb.dto.StopHistoryEntry;
import be.gtfsbe.chb.dto.StopPlaceTypeOption;
import be.gtfsbe.chb.dto.StopSearchResult;
import be.gtfsbe.chb.dto.TecCityIssue;
import be.gtfsbe.chb.dto.UnreviewedStop;
import be.gtfsbe.chb.repository.StopViewerRepository;
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

    public PublicStopController(StopViewerRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/stops")
    public GeoJsonFeatureCollection stops(@RequestParam double minLon, @RequestParam double minLat,
                                           @RequestParam double maxLon, @RequestParam double maxLat,
                                           @RequestParam(name = "showDisabled", defaultValue = "false") boolean showDisabled,
                                           Locale locale) {
        return repository.findAllAsMapFeatures(mapBounds(minLon, minLat, maxLon, maxLat), showDisabled, locale.getLanguage());
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
    public GeoJsonFeatureCollection unreviewedMap(@RequestParam double minLon, @RequestParam double minLat,
                                                   @RequestParam double maxLon, @RequestParam double maxLat,
                                                   @RequestParam(name = "showDisabled", defaultValue = "false") boolean showDisabled,
                                                   Locale locale) {
        return repository.findUnreviewedAsMapFeatures(mapBounds(minLon, minLat, maxLon, maxLat), showDisabled, locale.getLanguage());
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

    @GetMapping("/api/review/mode-mismatches")
    public List<ModeMismatch> modeMismatches() {
        return repository.findModeMismatches();
    }

    @GetMapping("/api/review/feeds")
    public List<String> feeds() {
        return repository.findFeeds();
    }

    private static MapBounds mapBounds(double minLon, double minLat, double maxLon, double maxLat) {
        if (minLon >= maxLon || minLat >= maxLat) {
            throw new InvalidRequestException("Ongeldig kaartviewport: minLon/minLat moeten kleiner zijn dan maxLon/maxLat");
        }
        return new MapBounds(minLon, minLat, maxLon, maxLat);
    }
}
