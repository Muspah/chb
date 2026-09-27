package be.gtfsbe.chb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code code} = SQLSTATE van een door de database geweigerde curator-actie (UC00x, 23514, ...);
 * {@code detail} = de betrokken id's uit DETAIL; {@code targetId} = het id waar de curator wel
 * moet zijn (uit HINT, bv. de quay achter een wrapper) zodat de UI ernaartoe kan navigeren.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String message, String code, String detail, Long targetId) {

    public ApiError(String message) {
        this(message, null, null, null);
    }
}
