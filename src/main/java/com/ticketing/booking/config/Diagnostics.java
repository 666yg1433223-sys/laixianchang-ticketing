package com.ticketing.booking.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Diagnostics {
    private final Path directory;
    private int written;
    @Autowired
    public Diagnostics(Bootstrap.Ready ready) { this(ready,Path.of(System.getProperty("user.dir"))); }

    Diagnostics(Bootstrap.Ready ready,Path projectRoot) { this.directory=logDirectory(ready,projectRoot); }

    static Path logDirectory(Bootstrap.Ready ready,Path projectRoot) {
        if(ready.runtime().productEnabled() && "learning".equals(ready.runId())) {
            // The portable launcher starts in its project root. Never reuse the old private .local path.
            return projectRoot.toAbsolutePath().normalize().resolve("evidence/learning");
        }
        return ready.runtime().productEnabled()?Bootstrap.ROOT.resolve(".local/logs"):Bootstrap.evidenceDirectory(ready.runId());
    }
    public synchronized void record(Throwable failure) {
        if(written>=200) { return; }
        try {
            // No driver messages, supplied text, stack traces, bearer tokens or bodies are persisted.
            String message=failure instanceof com.ticketing.booking.domain.ApiFailure api?api.code():failure.getMessage();
            String code="DEPENDENCY_UNAVAILABLE";
            for(String allowed:java.util.List.of("EXPIRY_CLOSE_FAILED","PAYMENT_SETTLEMENT_FAILED",
                    "TRUSTED_RESULT_INCONSISTENT","PAYMENT_TRUSTED_RESULT_INCONSISTENT","REFUND_SETTLEMENT_FAILED",
                    "UNAUTHENTICATED","SIM_MANAGEMENT_FORBIDDEN","INVALID_ARGUMENT","RESOURCE_NOT_FOUND",
                    "IDEMPOTENCY_CONFLICT","OPERATION_ACCEPTANCE_UNKNOWN","DEPENDENCY_UNAVAILABLE")) {
                if(message!=null && (message.equals(allowed) || message.startsWith(allowed+":") || message.startsWith(allowed+" "))) { code=allowed;break; }
            }
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("application-errors.log"),java.time.Instant.now()+" "+code+" "+failure.getClass().getSimpleName()+System.lineSeparator(),
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            written++;
        } catch (Exception unavailable) {
            System.err.println("BIZ01_DIAGNOSTIC_WRITE_FAILED:"+unavailable.getClass().getSimpleName());
        }
    }
}
