package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

/** Normal local application: real identity and database time, no test hooks or test scope. */
public final class LocalRuntime implements RuntimeServices, ApplicationListener<ApplicationReadyEvent>, DisposableBean {
    private volatile ToLongFunction<String> resolver;
    private final boolean simulationEnabled;
    private final Control control;
    private volatile Thread controlThread;

    public LocalRuntime(boolean simulationEnabled) { this(simulationEnabled, null); }

    LocalRuntime(boolean simulationEnabled, Control control) {
        this.simulationEnabled = simulationEnabled;
        this.control = control;
    }

    public synchronized void bindIdentity(ToLongFunction<String> resolver) {
        if (resolver == null || this.resolver != null) { throw new IllegalStateException("IDENTITY_ALREADY_BOUND"); }
        this.resolver = resolver;
    }

    @Override public long authenticate(String authorization) {
        var current = resolver;
        if (current == null) { throw new ApiFailure(503, "IDENTITY_UNAVAILABLE"); }
        return current.applyAsLong(authorization);
    }
    @Override public Instant afterLocks(com.ticketing.booking.domain.DatabaseTimeSource databaseTime) {
        return databaseTime.now();
    }
    @Override public void at(String boundary, long requestId) { }
    @Override public boolean directEnabled() { return false; }
    @Override public boolean recoveryEnabled() { return false; }
    @Override public boolean settlementRecoveryEnabled() { return true; }
    @Override public boolean productEnabled() { return true; }
    @Override public boolean simulatorEnabled() { return simulationEnabled; }
    @Override public SimulationPlan simulationPlan() {
        if (!simulationEnabled) { throw new IllegalStateException("SIMULATOR_DISABLED"); }
        return new SimulationPlan("SUCCESS", 0);
    }

    /** Optional private-file lifecycle channel used only by the local launch script. */
    @Override public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (control == null || controlThread != null) { return; }
        try {
            var process = ProcessHandle.current();
            Map<String,Object> ready = new LinkedHashMap<>(control.binding());
            ready.put("pid", process.pid());
            ready.put("startedAt", process.info().startInstant().orElseThrow().toString());
            ready.put("javaPath", process.info().command().orElseThrow());
            ready.put("status", "READY");
            ready.put("baseUrl", "http://127.0.0.1:18082");
            control.write("ready.json", ready);
        } catch (Exception failure) {
            throw new IllegalStateException("LOCAL_CONTROL_START_FAILED:" + failure.getClass().getSimpleName());
        }
        var context = event.getApplicationContext();
        controlThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && context.isActive()) {
                    Path stop = control.directory.resolve("stop.json");
                    if (Files.exists(stop, LinkOption.NOFOLLOW_LINKS)) {
                        JsonNode command = Control.read(stop);
                        if (!control.runId.equals(command.path("runId").asText())
                                || !control.nonce.equals(command.path("nonce").asText())
                                || !"STOP".equals(command.path("action").asText())) {
                            throw new IllegalStateException("LOCAL_STOP_IDENTITY_MISMATCH");
                        }
                        // Spring closes request handling, schedulers, pools and Redis normally.
                        context.close();
                        Map<String,Object> stopped = new LinkedHashMap<>(control.binding());
                        stopped.put("status", "STOPPED");
                        stopped.put("stoppedAt", Instant.now().toString());
                        control.write("stopped.json", stopped);
                        return;
                    }
                    Thread.sleep(400);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                // Neither the local nonce nor credentials belong in public logs.
                System.err.println("LOCAL_CONTROL_REFUSED:" + failure.getClass().getSimpleName());
            }
        }, "local-site-control");
        // Keep the thread alive through context.close() until its stop receipt is persisted.
        controlThread.setDaemon(false);
        controlThread.start();
    }

    @Override public void destroy() {
        Thread thread = controlThread;
        if (thread != null && thread != Thread.currentThread()) { thread.interrupt(); }
    }

    static final class Control {
        private static final ObjectMapper JSON = new ObjectMapper();
        final Path directory;
        final String runId;
        final String nonce;

        private Control(Path directory, String runId, String nonce) {
            this.directory = directory; this.runId = runId; this.nonce = nonce;
        }

        static Control open(String runId) throws Exception {
            if (runId.isEmpty()) { return null; }
            if (!runId.matches("local-[a-z0-9-]{8,44}")) { throw new IllegalStateException("INVALID_LOCAL_RUN"); }
            Path directory = Bootstrap.ROOT.resolve(".local/local-site").resolve(runId);
            requirePrivate(directory);
            JsonNode launch = read(directory.resolve("launch.json"));
            String nonce = launch.path("nonce").asText();
            if (!runId.equals(launch.path("runId").asText()) || !nonce.matches("[a-f0-9]{64}")) {
                throw new IllegalStateException("INVALID_LOCAL_LAUNCH");
            }
            if (Files.exists(directory.resolve("ready.json"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(directory.resolve("stop.json"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(directory.resolve("stopped.json"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("LOCAL_RUN_ALREADY_USED");
            }
            return new Control(directory, runId, nonce);
        }

        Map<String,Object> binding() { return Map.of("runId", runId, "nonce", nonce); }

        static void requirePrivate(Path path) throws Exception {
            Path absolute = path.toAbsolutePath().normalize();
            if (!absolute.toRealPath().equals(absolute) || Files.isSymbolicLink(path)) {
                throw new IllegalStateException("LINKED_LOCAL_CONTROL_PATH");
            }
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() && !attributes.isRegularFile()) { throw new IllegalStateException("INVALID_LOCAL_CONTROL_PATH"); }
            var owner = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) { throw new IllegalStateException("LOCAL_CONTROL_OWNER_MISMATCH"); }
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl != null) {
                for (var entry : acl.getAcl()) {
                    if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
                        throw new IllegalStateException("LOCAL_CONTROL_PERMISSIONS_TOO_BROAD");
                    }
                }
            } else {
                for (var permission : Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                        throw new IllegalStateException("LOCAL_CONTROL_PERMISSIONS_TOO_BROAD");
                    }
                }
            }
        }

        static JsonNode read(Path path) throws Exception {
            requirePrivate(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) {
                throw new IllegalStateException("INVALID_LOCAL_CONTROL_FILE");
            }
            return JSON.readTree(Files.readString(path));
        }

        void write(String name, Object value) throws Exception {
            requirePrivate(directory);
            Path target = directory.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { throw new IllegalStateException("LOCAL_RECORD_EXISTS"); }
            Path staged = directory.resolve(UUID.randomUUID() + ".tmp");
            Files.write(staged, JSON.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            requirePrivate(staged);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
