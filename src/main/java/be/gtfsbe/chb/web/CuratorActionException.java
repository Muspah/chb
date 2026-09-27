package be.gtfsbe.chb.web;

import org.springframework.http.HttpStatus;

/** Een door de curator-SQL-laag (chb.create_stop_place e.a.) geweigerde actie, al vertaald naar een HTTP-status. */
public class CuratorActionException extends RuntimeException {

    private final HttpStatus status;
    private final String sqlState;
    private final String detail;
    private final Long targetId;

    public CuratorActionException(HttpStatus status, String sqlState, String message, String detail, Long targetId, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.sqlState = sqlState;
        this.detail = detail;
        this.targetId = targetId;
    }

    public HttpStatus status() { return status; }
    public String sqlState() { return sqlState; }
    public String detail() { return detail; }
    public Long targetId() { return targetId; }
}
