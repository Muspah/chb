package be.gtfsbe.chb.web;

import be.gtfsbe.chb.dto.AttributeUpsertRequest;
import be.gtfsbe.chb.dto.CreateStopRequest;
import be.gtfsbe.chb.dto.GroupStopsRequest;
import be.gtfsbe.chb.dto.MergeRequest;
import be.gtfsbe.chb.dto.PromoteWrapperRequest;
import be.gtfsbe.chb.dto.UpdateStopRequest;
import be.gtfsbe.chb.repository.StopEditorRepository;
import be.gtfsbe.chb.service.RejectionCounter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Alle schrijvende endpoints (build-instruction.md §3.2, §6). Bereikbaarheid is uitsluitend
 * afgedwongen door {@link be.gtfsbe.chb.config.SecurityConfig} (alles hier valt buiten de
 * publieke permitAll-lijst en vereist dus een Entra ID-sessie) en, onafhankelijk daarvan, door de
 * database zelf: de onderliggende {@link StopEditorRepository} gebruikt enkel de chb_editor-pool,
 * die geen enkel recht heeft buiten wat build-instruction.md §5 toekent.
 */
@RestController
public class StopEditController {

    private final StopEditorRepository repository;
    private final RejectionCounter rejections;

    public StopEditController(StopEditorRepository repository, RejectionCounter rejections) {
        this.repository = repository;
        this.rejections = rejections;
    }

    @PostMapping("/api/stops")
    public ResponseEntity<Void> create(@AuthenticationPrincipal OidcUser user, @RequestBody CreateStopRequest request) {
        long id = repository.createStop(CurrentUser.email(user), request);
        return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/api/stops/" + id).build();
    }

    @PatchMapping("/api/stops/{id}")
    public ResponseEntity<Void> update(@AuthenticationPrincipal OidcUser user, @PathVariable long id,
                                        @RequestBody UpdateStopRequest request) {
        repository.updateStop(CurrentUser.email(user), id, request);
        return ResponseEntity.noContent().build();
    }

    public record LocationRequest(double lat, double lon) {
    }

    @PostMapping("/api/stops/{id}/location")
    public ResponseEntity<Void> setLocation(@AuthenticationPrincipal OidcUser user, @PathVariable long id,
                                             @RequestBody LocationRequest request) {
        repository.setLocation(CurrentUser.email(user), id, request.lat(), request.lon());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/stops/{id}/reset-location")
    public ResponseEntity<Void> resetLocation(@AuthenticationPrincipal OidcUser user, @PathVariable long id) {
        repository.resetLocation(CurrentUser.email(user), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/stops/{id}/attributes/{key}")
    public ResponseEntity<Void> putAttribute(@AuthenticationPrincipal OidcUser user, @PathVariable long id,
                                              @PathVariable String key, @RequestBody AttributeUpsertRequest request) {
        repository.upsertAttribute(CurrentUser.email(user), id, key, request.value());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/stops/{id}/attributes/{key}")
    public ResponseEntity<Void> deleteAttribute(@AuthenticationPrincipal OidcUser user, @PathVariable long id,
                                                 @PathVariable String key) {
        repository.deleteAttribute(CurrentUser.email(user), id, key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/stops/{sourceId}/merge")
    public ResponseEntity<Void> merge(@AuthenticationPrincipal OidcUser user, @PathVariable long sourceId,
                                       @RequestBody MergeRequest request) {
        repository.mergeQuays(CurrentUser.email(user), sourceId, request.targetId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/stops/{id}/recompute-location")
    public ResponseEntity<Void> recomputeLocation(@AuthenticationPrincipal OidcUser user, @PathVariable long id) {
        repository.recomputeLocation(CurrentUser.email(user), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/stops/group")
    public ResponseEntity<Void> group(@AuthenticationPrincipal OidcUser user, @RequestBody GroupStopsRequest request) {
        long id = repository.groupStops(CurrentUser.email(user), request);
        return ResponseEntity.status(HttpStatus.CREATED).header("Location", "/api/stops/" + id).build();
    }

    // --- Doorlichting: halte als nagekeken (af)markeren ------------------------------------
    // Het tijdstip zet de database zelf (chb.set_attribute, sleutel reviewed_at, database-tijd —
    // issue #8); de client kan er geen waarde voor meesturen.

    @PostMapping("/api/stops/{id}/review")
    public ResponseEntity<Void> markReviewed(@AuthenticationPrincipal OidcUser user, @PathVariable long id) {
        repository.markReviewed(CurrentUser.email(user), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/stops/{id}/review")
    public ResponseEntity<Void> unmarkReviewed(@AuthenticationPrincipal OidcUser user, @PathVariable long id) {
        repository.unmarkReviewed(CurrentUser.email(user), id);
        return ResponseEntity.noContent().build();
    }

    // --- Lege groeperingsknoop soft-verwijderen (nooit een echte hard delete) — expliciete,
    // aparte actie, geen omweg via samenvoegen. De voorwaarden controleert de database (UC007).

    @DeleteMapping("/api/stops/{id}")
    public ResponseEntity<Void> softDelete(@AuthenticationPrincipal OidcUser user, @PathVariable long id) {
        repository.softDeleteStop(CurrentUser.email(user), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/stops/{id}/restore")
    public ResponseEntity<Void> restore(@AuthenticationPrincipal OidcUser user, @PathVariable long id) {
        repository.restoreStop(CurrentUser.email(user), id);
        return ResponseEntity.noContent().build();
    }

    /** Wrapper → curated StopPlace: is_wrapper := false met een verplichte eigen naam. */
    @PostMapping("/api/stops/{id}/promote-wrapper")
    public ResponseEntity<Void> promoteWrapper(@AuthenticationPrincipal OidcUser user, @PathVariable long id,
                                                @RequestBody PromoteWrapperRequest request) {
        repository.promoteWrapper(CurrentUser.email(user), id, request.name());
        return ResponseEntity.noContent().build();
    }

    /** Aantal door de database geweigerde curator-acties per SQLSTATE sinds de start van deze JVM (blinde vlek, zie RejectionCounter). */
    @GetMapping("/api/review/rejections")
    public Map<String, Long> rejections() {
        return rejections.snapshot();
    }
}
