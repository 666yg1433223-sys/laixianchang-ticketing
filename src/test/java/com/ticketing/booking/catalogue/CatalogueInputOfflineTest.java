package com.ticketing.booking.catalogue;

import com.ticketing.booking.domain.ApiFailure;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static com.ticketing.booking.catalogue.CatalogueInput.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueInputOfflineTest {
    @Test void rejectsUnknownTrustedFields() {
        assertThrows(ApiFailure.class,()->body("{\"title\":\"音乐会\",\"role\":\"ADMIN\"}",Set.of("title"),Set.of("title")));
    }
    @Test void rejectsDuplicateAndTrailingObjects() {
        assertThrows(ApiFailure.class,()->body("{\"title\":\"A\",\"title\":\"B\"}",Set.of("title"),Set.of("title")));
        assertThrows(ApiFailure.class,()->body("{}{}",Set.of(),Set.of()));
    }
    @Test void missingNullAndArrayAreNotValidEdits() {
        for(String raw:new String[]{"{}","{\"title\":null}","[]","null"}) {
            assertThrows(ApiFailure.class,()->body(raw,Set.of("title"),Set.of("title")));
        }
    }
    @Test void textIsTrimmedButNeverConvertedFromNumbers() {
        var input=body("{\"title\":\"  音乐会  \"}",Set.of("title"),Set.of("title"));
        assertEquals("音乐会",text(input,"title",128,false));
        assertThrows(ApiFailure.class,()->text(input,"title",2,false));
        assertThrows(ApiFailure.class,()->text(body("{\"title\":123}",Set.of("title"),Set.of()),"title",128,false));
    }
    @Test void idsRemainStringsAndWithinSignedLong() {
        assertEquals(Long.MAX_VALUE,id(body("{\"id\":\"9223372036854775807\"}",Set.of("id"),Set.of()),"id"));
        for(String value:new String[]{"5","\"0\"","\"9223372036854775808\""}) {
            assertThrows(ApiFailure.class,()->id(body("{\"id\":"+value+"}",Set.of("id"),Set.of()),"id"));
        }
    }
    @Test void capacityAndMoneyAreBoundedIntegersNotDecimals() {
        assertEquals(1,integer(body("{\"n\":1}",Set.of("n"),Set.of()),"n",1,1_000_000));
        for(String value:new String[]{"0","-1","1.0","1000001","\"99\"","9223372036854775808"}) {
            assertThrows(ApiFailure.class,()->integer(body("{\"n\":"+value+"}",Set.of("n"),Set.of()),"n",1,1_000_000));
        }
    }
    @Test void timestampsRequireUtcAndPreserveDatabasePrecision() {
        assertEquals(Instant.parse("2026-09-13T12:30:00.123Z"),instant(body("{\"t\":\"2026-09-13T12:30:00.123Z\"}",Set.of("t"),Set.of()),"t"));
        for(String value:new String[]{"2026-09-13T20:30:00+08:00","2026-09-13","2026-09-13T12:30:00.1234Z"}) {
            assertThrows(ApiFailure.class,()->instant(body("{\"t\":\""+value+"\"}",Set.of("t"),Set.of()),"t"));
        }
    }
    @Test void publishingRequiresUnexpiredStrictTimeWindow() {
        Instant now=Instant.parse("2026-09-13T10:00:00Z");
        assertDoesNotThrow(()->window(now.minusSeconds(1),now.plusSeconds(10),now.plusSeconds(20),now));
        assertThrows(ApiFailure.class,()->window(now,now,now.plusSeconds(20),now));
        assertThrows(ApiFailure.class,()->window(now.minusSeconds(10),now,now.plusSeconds(20),now));
        assertThrows(ApiFailure.class,()->window(now,now.plusSeconds(10),now.plusSeconds(10),now));
    }
    @Test void paginationIsBoundedAndNotLenientlyCoerced() {
        assertEquals(12,page(null,12,50));assertEquals(50,page("50",12,50));
        for(String raw:new String[]{"0","01","-1","51","1.0","x","999999999999999"}) {
            assertThrows(ApiFailure.class,()->page(raw,12,50));
        }
    }
}
