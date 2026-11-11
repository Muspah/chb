package be.gtfsbe.chb.repository;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StopMapCacheTest {

    private final StopViewerRepository repository = mock(StopViewerRepository.class);
    private final StopNameCache names = mock(StopNameCache.class);
    private final StopMapCache cache = new StopMapCache(repository, names, JsonMapper.builder().build());

    @Test
    void rebuildsOnlyAfterAnEditOrNewNames() {
        List<Object[]> rows = List.<Object[]>of(new Object[] {1L, 4.35, 50.85, "Centraal", null, null, "bus", null, "w", 2});
        when(repository.findMapRows(anyString())).thenReturn(rows);

        StopMapCache.Snapshot first = cache.get("nl");
        assertEquals("{\"stops\":[[1,4.35,50.85,\"Centraal\",null,null,\"bus\",null,\"w\",2]]}",
            new String(first.json(), StandardCharsets.UTF_8));
        assertSame(first, cache.get("nl"));
        verify(repository, times(1)).findMapRows("nl");

        cache.invalidate();
        StopMapCache.Snapshot afterEdit = cache.get("nl");
        verify(repository, times(2)).findMapRows("nl");
        assertEquals(first.etag(), afterEdit.etag()); // zelfde inhoud: de browser krijgt een 304

        when(names.version()).thenReturn(1L);
        cache.get("nl");
        verify(repository, times(3)).findMapRows("nl");

        // Een onbekende taal deelt de defaultvariant; een andere inhoud geeft een andere ETag.
        when(repository.findMapRows("")).thenReturn(List.<Object[]>of(new Object[] {2L}));
        assertSame(cache.get("ja"), cache.get("xx"));
        assertNotEquals(first.etag(), cache.get("ja").etag());
    }
}
