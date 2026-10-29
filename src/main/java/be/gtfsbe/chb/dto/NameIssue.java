package be.gtfsbe.chb.dto;

import java.util.List;

/**
 * Een quay of StopPlace waarvan de default-naam niet volgens de taalgebiedregel kon (chb.quay_names /
 * chb.stop_names, ndov V132/V137). {@code names} is "taal: naam" per taal, al samengevoegd in SQL.
 */
public record NameIssue(long id, boolean isQuay, String name, String names, String defaultName, String defaultLang,
                        String reason, List<String> disagreeLangs, List<String> feeds, String municipality) {
}
