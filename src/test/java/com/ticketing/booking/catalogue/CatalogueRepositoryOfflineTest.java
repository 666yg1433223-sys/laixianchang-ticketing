package com.ticketing.booking.catalogue;

import com.ticketing.booking.repository.CatalogueRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogueRepositoryOfflineTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CatalogueRepository catalogue = new CatalogueRepository(jdbc);

    @Test void failedSessionCompareAndSetIsNotReportedAsSuccess() {
        assertThrows(IllegalStateException.class, () -> catalogue.transitionSession(5L, "DRAFT", "OPEN"));
        verify(jdbc).update("UPDATE event_session SET state=? WHERE id=? AND state=?", "OPEN", 5L, "DRAFT");
    }

    @Test void invalidationCompletionCannotAffectAnotherTypeOrRecoveryGeneration() {
        catalogue.completeInvalidation(10L, 7L);
        verify(jdbc).update("UPDATE outbox_event SET state='DONE',next_attempt_at=NULL,last_error_code=NULL,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND event_type='CATALOG_INVALIDATE' AND state='READY' AND recovery_generation=?", 10L, 7L);
    }

    @Test void staleFailureCannotOverwriteANewerAttemptOrRecoveredGeneration() {
        Instant retry = Instant.parse("2026-10-05T00:00:08Z");
        catalogue.recordInvalidationFailure(10L, 2L, 7L, 3L, "READY", retry, null);
        verify(jdbc).update("UPDATE outbox_event SET attempts=?,state=?,next_attempt_at=?,finished_at=?,last_error_code='CACHE_DELETE_FAILED' WHERE id=? AND event_type='CATALOG_INVALIDATE' AND state='READY' AND attempts=? AND recovery_generation=?",
            3L, "READY", Timestamp.from(retry), null, 10L, 2L, 7L);
    }
}
