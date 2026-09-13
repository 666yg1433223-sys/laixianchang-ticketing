package com.ticketing.booking.config;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Component;

@Component
public class Diagnostics {
    private final Bootstrap.Ready ready;
    private int written;
    public Diagnostics(Bootstrap.Ready ready) { this.ready=ready; }
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
            var directory=ready.runtime().productEnabled()?Bootstrap.ROOT.resolve(".local/logs"):Bootstrap.evidenceDirectory(ready.runId());
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("application-errors.log"),java.time.Instant.now()+" "+code+" "+failure.getClass().getSimpleName()+System.lineSeparator(),
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            written++;
        } catch (Exception unavailable) {
            System.err.println("BIZ01_DIAGNOSTIC_WRITE_FAILED:"+unavailable.getClass().getSimpleName());
        }
    }
}
