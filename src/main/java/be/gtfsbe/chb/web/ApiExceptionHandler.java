package be.gtfsbe.chb.web;

import be.gtfsbe.chb.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Vangt de domeinfouten uit de repository-laag zodat de API nooit een onverklaarde 500 teruggeeft
 * voor iets wat gewoon een foute aanvraag is (build-instruction.md §3.2, §8).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalid(InvalidRequestException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(StopNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(StopNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage()));
    }

    /** Door de database geweigerde curator-actie: status volgens de SQLSTATE-tabel, met code/detail/targetId voor de UI. */
    @ExceptionHandler(CuratorActionException.class)
    ResponseEntity<ApiError> handleCuratorAction(CuratorActionException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.getMessage(), e.sqlState(), e.detail(), e.targetId()));
    }
}
