package be.gtfsbe.chb.dto;

import java.util.List;

/**
 * Een naam die zowel door minstens één nagekeken als minstens één nog niet nagekeken StopPlace
 * gedragen wordt — een curator die de nagekeken variant al zag, mist zo licht de andere.
 */
public record DuplicateNameGroup(String name, long reviewedCount, List<UnreviewedStop> unreviewed) {
}
