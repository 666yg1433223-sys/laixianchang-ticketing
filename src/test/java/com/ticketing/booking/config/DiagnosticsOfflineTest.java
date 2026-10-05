package com.ticketing.booking.config;

import com.ticketing.booking.domain.ApiFailure;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticsOfflineTest {
    @TempDir Path project;

    private static Bootstrap.Ready ready(boolean product, String runId) {
        RuntimeServices runtime = mock(RuntimeServices.class);
        when(runtime.productEnabled()).thenReturn(product);
        return new Bootstrap.Ready(mock(HikariDataSource.class), runtime, runId);
    }

    @Test void learningWritesOnlyItsIgnoredProjectDirectoryEvenWhenOldLocalPathIsBlocked() throws Exception {
        Path blocked = project.resolve(".local");
        Files.writeString(blocked, "private path must remain untouched");
        var diagnostics = new Diagnostics(ready(true, "learning"), project);
        diagnostics.record(new ApiFailure(503, "DEPENDENCY_UNAVAILABLE"));
        Path log = project.resolve("evidence/learning/application-errors.log");
        assertTrue(Files.isRegularFile(log));
        assertEquals("private path must remain untouched", Files.readString(blocked));
        assertEquals(1, Files.readAllLines(log).size());
        assertTrue(Files.readString(log).contains("DEPENDENCY_UNAVAILABLE ApiFailure"));
    }

    @Test void legacyProductAndPrivateFixtureKeepTheirOriginalDiagnosticLocations() {
        assertEquals(Bootstrap.ROOT.resolve(".local/logs"),
            Diagnostics.logDirectory(ready(true, "local-sim"), project));
        assertEquals(Bootstrap.evidenceDirectory("offline-run-001"),
            Diagnostics.logDirectory(ready(false, "offline-run-001"), project));
        assertEquals(project.resolve("evidence/learning"),
            Diagnostics.logDirectory(ready(true, "learning"), project));
    }

    @Test void learningLogStillDropsRawMessagesSecretsNewlinesAndCauses() throws Exception {
        var diagnostics = new Diagnostics(ready(true, "learning"), project);
        diagnostics.record(new IllegalStateException("Authorization=private-token\nbody=private-text",
            new IllegalArgumentException("jdbc-password=private-password")));
        diagnostics.record(new IllegalStateException("PAYMENT_SETTLEMENT_FAILED: private-driver-message"));
        String log = Files.readString(project.resolve("evidence/learning/application-errors.log"));
        assertEquals(2, log.lines().count());
        assertTrue(log.contains("DEPENDENCY_UNAVAILABLE IllegalStateException"));
        assertTrue(log.contains("PAYMENT_SETTLEMENT_FAILED IllegalStateException"));
        assertFalse(log.contains("private-"));
        assertFalse(log.contains("Authorization"));
        assertFalse(log.contains("IllegalArgumentException"));
    }

    @Test void learningDiagnosticVolumeRemainsBoundedToTwoHundredRecords() throws Exception {
        var diagnostics = new Diagnostics(ready(true, "learning"), project);
        for (int i = 0; i < 205; i++) { diagnostics.record(new IllegalStateException("private-detail")); }
        assertEquals(200, Files.readAllLines(project.resolve("evidence/learning/application-errors.log")).size());
    }
}
