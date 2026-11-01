package be.gtfsbe.chb.dto;

import java.util.List;

/**
 * Een live quay met meerdere modi uit chb.multimode_quay (ndov V145, #76). Hoort niet voor te komen: een vervoerder
 * zonder splitsing of een samenvoeging door een curator.
 */
public record MultimodeQuay(long quayId, String name, List<String> sourceSchemas, List<String> modes) {
}
