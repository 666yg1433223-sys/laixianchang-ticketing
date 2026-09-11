package com.ticketing.booking.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.springframework.stereotype.Component;

@Component
public class Diagnostics {
    private final Bootstrap.Ready ready;
    public Diagnostics(Bootstrap.Ready ready) { this.ready=ready; }
    public synchronized void record(Throwable failure) {
        try {
            var text=new StringWriter(); failure.printStackTrace(new PrintWriter(text));
            String sanitized=text.toString().replaceAll("(?i)[a-f0-9]{64}","[redacted]")
                .replaceAll("(?i)(password|token|authorization)=[^\\s&]+","$1=[redacted]");
            Files.writeString(Bootstrap.ROOT.resolve("evidence/BIZ-02-I-001").resolve(ready.runId()).resolve("application-errors.log"),
                sanitized+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch (Exception unavailable) {
            System.err.println("BIZ01_DIAGNOSTIC_WRITE_FAILED:"+unavailable.getClass().getSimpleName());
        }
    }
}
