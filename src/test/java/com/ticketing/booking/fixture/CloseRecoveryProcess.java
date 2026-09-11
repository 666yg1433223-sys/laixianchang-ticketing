package com.ticketing.booking.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.config.Bootstrap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;

/** Parent-owned local process, controlled by stdin only; no test HTTP routes. */
public final class CloseRecoveryProcess {
    public static void main(String[] args) throws Exception {
        String run = args[0], mode = args[1];
        if (!run.matches("[a-z0-9-]{8,50}") || !(mode.equals("crash") || mode.equals("recover"))) {
            throw new IllegalArgumentException("Invalid local test arguments");
        }
        var runtime = TestRuntime.instance(); runtime.reset();
        runtime.userA = Long.parseLong(args[2]); runtime.userB = Long.parseLong(args[3]);
        runtime.fixedTime = mode.equals("crash") ? BookingHarness.BASE : BookingHarness.BASE.plusSeconds(600);
        Path evidence = Bootstrap.ROOT.resolve("evidence/BIZ-02-I-001").resolve(run);
        var json = new ObjectMapper();
        if (mode.equals("crash")) {
            runtime.hooks.put("T2_COMMITTED", id -> {
                try {
                    json.writeValue(evidence.resolve("S07-hold-committed.json").toFile(), Map.of(
                        "requestId", Long.toString(id), "pid", ProcessHandle.current().pid(),
                        "point", "after physical T2 commit, durable HOLD before HTTP response", "redisKeys", runtime.observedRedisKeys));
                } catch (Exception failure) { Runtime.getRuntime().halt(73); }
                Runtime.getRuntime().halt(71);
            });
        } else { runtime.recovery = true; }
        CompletableFuture.delayedExecutor(45, TimeUnit.SECONDS).execute(() -> Runtime.getRuntime().halt(72));
        try (var app = new SpringApplication(BookingApplication.class).run("--spring.profiles.active=biz01-it", "--biz.runId=" + run)) {
            json.writeValue(evidence.resolve("S07-" + mode + "-ready.json").toFile(), Map.of("pid", ProcessHandle.current().pid(), "port", 18083));
            String control = new BufferedReader(new InputStreamReader(System.in)).readLine();
            if (!"STOP".equals(control)) { throw new IllegalStateException("Missing parent shutdown instruction"); }
        }
    }
}
