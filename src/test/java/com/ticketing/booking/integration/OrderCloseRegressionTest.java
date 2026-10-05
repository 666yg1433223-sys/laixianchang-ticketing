package com.ticketing.booking.integration;

import com.ticketing.booking.fixture.BookingHarness;
import com.ticketing.booking.fixture.BookingHarness.Fixture;
import com.ticketing.booking.fixture.BookingHarness.Reply;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.service.AllocationService;
import com.ticketing.booking.service.OrderCloseService;
import com.ticketing.booking.recovery.OrderExpiryScanner;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static com.ticketing.booking.fixture.BookingHarness.BASE;
import static com.ticketing.booking.domain.Values.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCloseRegressionTest {
    BookingHarness h;
    @BeforeAll void open() throws Exception { h = new BookingHarness(System.getProperty("biz.runId") + "-orders"); }
    @AfterAll void close() throws Exception {
        if (h != null) { h.cleanOnClose = h.passed.size() == 9; h.close(); }
    }
    interface Scenario { void run() throws Exception; }
    @TestFactory Stream<DynamicTest> scenarios() {
        List<Scenario> cases = List.of(this::s01, this::s02, this::s03, this::s04, this::s05,
                this::s06, this::s07, this::s08, this::s09);
        return java.util.stream.IntStream.range(0, cases.size())
                .mapToObj(i -> DynamicTest.dynamicTest(String.format("S%02d", i + 1), () -> cases.get(i).run()));
    }
    record Hold(long request, long order, Reply accepted) { }
    Hold hold(Fixture f, String key) throws Exception {
        var reply = h.post(f, key);
        assertEquals(202, reply.status());
        var result = h.query(reply.id()).body();
        assertEquals("SUCCEEDED", result.path("status").asText());
        assertEquals("HOLD", result.path("order").path("orderStatus").asText());
        return new Hold(reply.id(), Long.parseLong(result.path("order").path("orderId").asText()), reply);
    }
    Reply cancel(long id) throws Exception { return h.request("POST", "/v1/orders/" + id + "/cancel", "{}", null, false, true); }
    Reply order(long id) throws Exception { return h.request("GET", "/v1/orders/" + id, null, null, false, true); }
    OrderExpiryScanner scanner() { return h.app.getBean(OrderExpiryScanner.class); }
    OrderExpiryScanner independentScanner() {
        return new OrderExpiryScanner(new com.ticketing.booking.repository.RecoveryRepository(h.jdbc),new com.ticketing.booking.repository.DatabaseTimeRepository(h.jdbc), h.runtime, h.app.getBean(OrderCloseService.class), h.app.getBean(Diagnostics.class));
    }
    String status(long id) { return h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?", String.class, id); }
    void done(String code, String notes, List<Fixture> fixtures) throws Exception {
        for (var f : fixtures) { h.save(f.name() + "-facts.json", h.snapshot(f)); }
        h.passed.add(code); h.log(Map.of("case", code, "result", "passed", "notes", notes));
    }
    void done(String code, String notes, Fixture... fixtures) throws Exception { done(code, notes, List.of(fixtures)); }
    static void latch(CountDownLatch gate) { BookingRegressionTest.waitLatch(gate); }
    static void barrier(CyclicBarrier gate) { BookingRegressionTest.barrier(gate); }
    void parallel(List<Callable<Void>> calls) throws Exception {
        var executor = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new ArrayList<Future<Void>>();
            for (var call : calls) { futures.add(executor.submit(call)); }
            for (var future : futures) { future.get(20, TimeUnit.SECONDS); }
        } finally {
            h.runtime.hooks.clear(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    void assertClosed(Fixture f, Hold hold, String state) throws Exception {
        var result = order(hold.order());
        assertEquals(200, result.status()); assertEquals(state, result.body().path("orderStatus").asText());
        var request = h.query(hold.request()).body();
        assertEquals("SUCCEEDED", request.path("status").asText());
        for (var fields = request.path("order").fields(); fields.hasNext();) {
            var field = fields.next(); assertEquals(field.getValue(), result.body().path(field.getKey()));
        }
        h.assertCounts(f, 1, 1, 2, 1);
        assertEquals("NONE", h.jdbc.queryForObject("SELECT owner_type FROM user_session_quota WHERE user_id=? AND session_id=?", String.class, f.userA(), f.session()));
    }

    void s01() throws Exception {
        var f = h.fixture("S01", 1); var hold = hold(f, "s01-regression-key");
        h.runtime.fixedTime = BASE.plusSeconds(30);
        var result = cancel(hold.order()); assertEquals(200, result.status());
        assertEquals(time(BASE.plusSeconds(30)), result.body().path("closedAt").asText());
        assertClosed(f, hold, "CANCELLED");
        done("S01", "Real cancellation and both owner queries; historical request stays SUCCEEDED", f);
    }
    void s02() throws Exception {
        var f = h.fixture("S02", 1); var hold = hold(f, "s02-repeat-key");
        var gate = new CyclicBarrier(4);
        h.runtime.hooks.put("BEFORE_CLOSE", id -> { if (id == hold.order()) { barrier(gate); } });
        var replies = new ConcurrentLinkedQueue<Reply>();
        var calls = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 4; i++) { calls.add(() -> { replies.add(cancel(hold.order())); return null; }); }
        parallel(calls);
        var fixed = replies.peek().body();
        for (var reply : replies) { assertEquals(200, reply.status()); assertEquals(fixed, reply.body()); }
        h.runtime.fixedTime = BASE.plusSeconds(601);
        assertEquals(fixed, cancel(hold.order()).body());
        scanner().scanOnce(); assertEquals(fixed, order(hold.order()).body());
        assertClosed(f, hold, "CANCELLED");
        done("S02", "Four cancellations meet at a barrier; sequential post-deadline retry preserves full response", f);
    }
    void s03() throws Exception {
        var used = new ArrayList<Fixture>();
        for (long offset : List.of(-1L, 0L, 1L)) {
            var f = h.fixture("S03-cancel-" + (offset + 1), 1); used.add(f);
            var hold = hold(f, "s03-boundary-key");
            h.runtime.fixedTime = BASE.plusSeconds(600).plusMillis(offset);
            assertEquals(200, cancel(hold.order()).status());
            assertClosed(f, hold, offset < 0 ? "CANCELLED" : "EXPIRED");
        }
        var scan = h.fixture("S03-scan", 1); used.add(scan); var due = hold(scan, "s03-scan-key");
        h.runtime.fixedTime = BASE.plusSeconds(600).minusMillis(1);
        scanner().scanOnce(); assertEquals("HOLD", status(due.order())); h.assertCounts(scan, 1, 1, 1, 0);
        h.save("S03-before-deadline.json", h.snapshot(scan));
        h.runtime.fixedTime = BASE.plusSeconds(600);
        scanner().scanOnce(); assertClosed(scan, due, "EXPIRED");
        var locked = h.fixture("S03-lock", 1); used.add(locked); var waiting = hold(locked, "s03-lock-key");
        h.runtime.fixedTime = BASE.plusSeconds(599);
        var reached = new CountDownLatch(1);
        h.runtime.hooks.put("CLOSE_SESSION_LOCKED", id -> { if (id == waiting.order()) { reached.countDown(); } });
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = h.probe.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT id FROM ticket_tier WHERE id=? FOR UPDATE")) {
                query.setLong(1, locked.tier()); try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            var cancel = executor.submit(() -> cancel(waiting.order())); latch(reached);
            BookingHarness.await(() -> h.jdbc.queryForList("SHOW PROCESSLIST").stream().anyMatch(row -> {
                Object info = row.get("Info");
                return info != null && info.toString().contains("ticket_tier") && info.toString().contains("FOR UPDATE");
            }));
            h.save("S03-lock-observed.json", Map.of("orderId", Long.toString(waiting.order()), "actualBlockedTierStatement", true));
            h.runtime.fixedTime = BASE.plusSeconds(600); connection.rollback();
            assertEquals("EXPIRED", cancel.get(12, TimeUnit.SECONDS).body().path("orderStatus").asText());
        } finally {
            h.runtime.hooks.clear(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertClosed(locked, waiting, "EXPIRED");
        done("S03", "D-1ms/D/D+1ms plus actual database lock wait; database time queried after all locks", used);
    }
    void s04() throws Exception {
        var used = new ArrayList<Fixture>();
        for (boolean twoScans : List.of(false, true)) {
            var f = h.fixture(twoScans ? "S04-scanners" : "S04-cancel-scan", 1); used.add(f);
            var held = hold(f, "s04-race-key"); h.runtime.fixedTime = BASE.plusSeconds(600);
            var gate = new CyclicBarrier(2);
            h.runtime.hooks.put("BEFORE_CLOSE", id -> { if (id == held.order()) { barrier(gate); } });
            var first = independentScanner(); var second = independentScanner();
            parallel(List.of(() -> { first.scanOnce(); return null; }, () -> {
                if (twoScans) { second.scanOnce(); } else { assertEquals(200, cancel(held.order()).status()); }
                return null;
            }));
            assertClosed(f, held, "EXPIRED");
            var other = h.request("POST", "/v1/booking-requests", h.input(f), "s04-other-user", true, true);
            assertEquals(202, other.status());
            var query = h.request("GET", "/v1/booking-requests/" + other.id(), null, null, true, true);
            assertEquals("HOLD", query.body().path("order").path("orderStatus").asText());
            h.assertCounts(f, 2, 2, 3, 0);
            // Close the second user's sample too, using that user's authenticated endpoint.
            String id = query.body().path("order").path("orderId").asText();
            assertEquals(200, h.request("POST", "/v1/orders/" + id + "/cancel", "{}", null, true, true).status());
            h.assertCounts(f, 2, 2, 4, 1);
        }
        done("S04", "Cancel-vs-scan and independent scanners collide; returned ticket can be allocated to another user", used);
    }
    void s05() throws Exception {
        var f = h.fixture("S05", 1); var old = hold(f, "s05-original-key");
        assertEquals(200, cancel(old.order()).status()); var fixed = order(old.order()).body();
        String cross = h.input(f).replace("\"tierId\":\"" + f.tier() + "\"", "\"tierId\":\"" + f.otherTier() + "\"");
        var accepted = h.request("POST", "/v1/booking-requests", cross, "s05-new-cross-key", false, true);
        assertEquals(202, accepted.status());
        long newOrder = Long.parseLong(h.query(accepted.id()).body().path("order").path("orderId").asText());
        var before = h.json.valueToTree(h.snapshot(f));
        assertEquals(fixed, cancel(old.order()).body());
        h.app.getBean(AllocationService.class).process(old.request());
        scanner().scanOnce(); h.app.getBean(OrderCloseService.class).expire(old.order());
        assertEquals(old.accepted().body(), h.post(f, "s05-original-key").body());
        assertEquals(before, h.json.valueToTree(h.snapshot(f)));
        assertEquals(newOrder, h.jdbc.queryForObject("SELECT owner_id FROM user_session_quota WHERE user_id=? AND session_id=?", Long.class, f.userA(), f.session()));
        h.assertCounts(f, 2, 2, 3, 1);
        done("S05", "Old terminal wins before current owner checks; O2 cross-tier quota and inventory untouched", f);
    }
    void s06() throws Exception {
        var used = new ArrayList<Fixture>();
        for (String point : List.of("CLOSE_AFTER_STATUS", "CLOSE_AFTER_STOCK", "CLOSE_AFTER_QUOTA", "CLOSE_AFTER_LEDGER")) {
            var f = h.fixture("S06-" + point.substring(12).toLowerCase(Locale.ROOT), 1); used.add(f);
            var held = hold(f, "s06-fault-key");
            var before = h.json.valueToTree(h.snapshot(f));
            h.runtime.hooks.put(point, id -> { throw new IllegalStateException("INJECTED_" + point); });
            var failure = cancel(held.order());
            assertEquals(503, failure.status()); assertEquals("CLOSE_RESULT_UNKNOWN", failure.body().path("code").asText());
            assertEquals(before, h.json.valueToTree(h.snapshot(f)));
            h.assertCounts(f, 1, 1, 1, 0); h.save(f.name() + "-rollback.json", h.snapshot(f));
            h.runtime.hooks.clear();
            assertEquals(200, cancel(held.order()).status()); assertClosed(f, held, "CANCELLED");
        }
        var wire = h.fixture("S06-wire", 1); used.add(wire); var held = hold(wire, "s06-wire-key");
        var committed = new CountDownLatch(1); var release = new CountDownLatch(1);
        h.runtime.hooks.put("BEFORE_CANCEL_RESPONSE", id -> { committed.countDown(); latch(release); });
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 18083), 3000); socket.setSoTimeout(3000);
            String request = "POST /v1/orders/" + held.order() + "/cancel HTTP/1.1\r\nHost: 127.0.0.1:18083\r\nAuthorization: Bearer "
                    + h.runtime.token(false) + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            latch(committed); assertEquals("CANCELLED", status(held.order()));
            h.assertCounts(wire, 1, 1, 2, 1); h.save("S06-before-response-loss.json", h.snapshot(wire));
            socket.setSoLinger(true, 0); socket.close();
            h.log(Map.of("case", "S06", "orderId", Long.toString(held.order()), "fault", "TCP reset after C physically committed before response write"));
        } finally { h.runtime.hooks.clear(); release.countDown(); }
        var persisted = order(held.order()).body();
        assertEquals(persisted, cancel(held.order()).body()); assertClosed(wire, held, "CANCELLED");
        var unknown = h.fixture("S06-unknown", 1); used.add(unknown); var ack = hold(unknown, "s06-unknown-key");
        h.runtime.hooks.put("CLOSE_COMMITTED", id -> { throw new IllegalStateException("INJECTED_POST_COMMIT_ACK_UNKNOWN"); });
        assertEquals("CLOSE_RESULT_UNKNOWN", cancel(ack.order()).body().path("code").asText());
        h.runtime.hooks.clear(); assertEquals(order(ack.order()).body(), cancel(ack.order()).body());
        assertClosed(unknown, ack, "CANCELLED");
        done("S06", "Four atomic rollback points, real HTTP response loss after C commit; separate labelled application ACK simulation", used);
    }
    Process child(Fixture f, String mode) throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String options = "-Xmx256m\n-Dfile.encoding=UTF-8\n-Dbiz.evidenceTask="+System.getProperty("biz.evidenceTask","BIZ-03-I-001")+"\n-cp\n\"" + cp.replace('\\', '/') + "\"\ncom.ticketing.booking.fixture.CloseRecoveryProcess\n"
                + h.runId + "\n" + mode + "\n" + f.userA() + "\n" + f.userB() + "\n";
        Path args = h.evidence.resolve("S07-" + mode + "-args.txt"); Files.writeString(args, options);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "@" + args)
                .directory(Bootstrap.ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(h.evidence.resolve("S07-" + mode + "-process.log").toFile());
        Process process = builder.start();
        h.log(Map.of("process", mode, "pid", process.pid(), "port", 18083));
        try {
            BookingHarness.await(() -> Files.exists(h.evidence.resolve("S07-" + mode + "-ready.json")) || !process.isAlive());
            assertTrue(process.isAlive(), "child failed to start");
        } catch (Throwable failure) {
            if (process.isAlive()) { process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
            throw failure;
        }
        return process;
    }
    void stop(Process child) throws Exception {
        if (child != null && child.isAlive()) {
            child.getOutputStream().write("STOP\n".getBytes(StandardCharsets.UTF_8)); child.getOutputStream().flush();
            if (!child.waitFor(8, TimeUnit.SECONDS)) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS); fail("owned child did not stop gracefully");
            }
            assertEquals(0, child.exitValue());
        }
    }
    void s07() throws Exception {
        var f = h.fixture("S07", 1); h.stopApp(); Process crash = null, recover = null;
        try {
            crash = child(f, "crash");
            assertThrows(Exception.class, () -> h.post(f, "s07-hold-crash"));
            assertTrue(crash.waitFor(10, TimeUnit.SECONDS)); assertEquals(71, crash.exitValue());
            var marker = h.json.readTree(Files.readString(h.evidence.resolve("S07-hold-committed.json")));
            for (var key : marker.path("redisKeys")) { h.runtime.observedRedisKeys.add(key.asText()); }
            long request = Long.parseLong(marker.path("requestId").asText());
            long order = h.jdbc.queryForObject("SELECT order_id FROM booking_request WHERE id=?", Long.class, request);
            assertEquals("HOLD", status(order)); h.assertCounts(f, 1, 1, 1, 0);
            h.save("S07-before-restart.json", h.snapshot(f));
            recover = child(f, "recover"); assertNotEquals(crash.pid(), recover.pid());
            BookingHarness.await(() -> "EXPIRED".equals(status(order)));
            var result = h.request("GET", "/v1/orders/" + order, null, null, false, true);
            assertEquals(200, result.status()); assertEquals("EXPIRED", result.body().path("orderStatus").asText());
            h.assertCounts(f, 1, 1, 2, 1);
            h.save("S07-recovery-summary.json", Map.of("crashPid", crash.pid(), "recoveryPid", recover.pid(),
                "exitCode", 71, "requestId", Long.toString(request), "orderId", Long.toString(order),
                "deadlineClock", time(BASE.plusSeconds(600)), "databaseRestarted", false));
            done("S07", "Actual application exit after durable HOLD; a new PID closes it using deadline scanner", f);
        } finally { stop(recover); stop(crash); h.startApp(); }
    }
    void s08() throws Exception {
        var f = h.fixture("S08", 1); var held = hold(f, "s08-security-key");
        String path = "/v1/orders/" + held.order();
        for (String method : List.of("GET", "POST")) {
            String route = path + (method.equals("POST") ? "/cancel" : "");
            String body = method.equals("POST") ? "{}" : null;
            assertEquals(401, h.request(method, route, body, null, false, false).status());
            assertEquals(404, h.request(method, route, body, null, true, true).status());
            String absent = "/v1/orders/9223372036854775807" + (method.equals("POST") ? "/cancel" : "");
            var missing = h.request(method, absent, body, null, true, true);
            assertEquals(404, missing.status()); assertEquals("RESOURCE_NOT_FOUND", missing.body().path("code").asText());
            var forged = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083" + route)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer invalid").header("X-User-Id", Long.toString(f.userA()))
                .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = h.http.send(forged, HttpResponse.BodyHandlers.ofString()); assertEquals(401, response.statusCode());
            h.log(Map.of("case", "S08", "method", method, "forgedUserHeader", true, "status", response.statusCode(), "body", h.json.readTree(response.body())));
        }
        for (String body : List.of("", "null", "[]", "1", "{} {}", "{\"userId\":\"1\"}", "{\"status\":\"CANCELLED\"}",
                "{\"payExpiresAt\":\"2040-01-01\"}", "{\"amount\":1}", "{\"userId\":1,\"userId\":1}")) {
            assertEquals(400, h.request("POST", path + "/cancel", body, null, false, true).status());
        }
        for (String id : List.of("0", "01", "-1", "abc", "9223372036854775808")) {
            assertEquals(400, h.request("GET", "/v1/orders/" + id, null, null, false, true).status());
            assertEquals(400, h.request("POST", "/v1/orders/" + id + "/cancel", "{}", null, false, true).status());
        }
        assertEquals(401, h.request("POST", "/v1/orders/invalid/cancel", "not json", null, false, false).status());
        h.assertCounts(f, 1, 1, 1, 0);
        assertEquals(200, cancel(held.order()).status());
        done("S08", "New routes preserve authentication-first, owner privacy and strict empty JSON request; inputs cannot set state/time", f);
    }
    void s09() throws Exception {
        var used = new ArrayList<Fixture>();
        var paused = h.fixture("S09-paused", 1); used.add(paused); var held = hold(paused, "s09-paused-key");
        h.jdbc.update("UPDATE event_session SET state='PAUSED' WHERE id=?", paused.session());
        h.runtime.fixedTime = BASE.plusSeconds(3600);
        independentScanner().scanOnce(); assertClosed(paused, held, "EXPIRED");
        var badIds = new HashSet<Long>();
        for (int i = 0; i < 20; i++) {
            var f = h.fixture("S09-bad-" + i, 1); used.add(f);
            badIds.add(hold(f, "s09-bad-key").order());
        }
        var good = h.fixture("S09-good", 1); used.add(good); var goodOrder = hold(good, "s09-good-key");
        h.runtime.fixedTime = BASE.plusSeconds(600);
        var attempts = new ConcurrentLinkedQueue<Long>();
        h.runtime.hooks.put("BEFORE_CLOSE", attempts::add);
        h.runtime.hooks.put("CLOSE_AFTER_STATUS", id -> { if (badIds.contains(id)) { throw new IllegalStateException("INJECTED_BAD_CANDIDATE"); } });
        var bounded = independentScanner();
        long failuresBefore = bounded.failedCloses.get();
        for (int page = 0; page < 3; page++) {
            int before = attempts.size(); bounded.scanOnce(); assertTrue(attempts.size() - before <= 20);
        }
        assertEquals("EXPIRED", status(goodOrder.order())); assertTrue(bounded.failedCloses.get() > failuresBefore);
        for (long id : badIds) { assertEquals("HOLD", status(id)); }
        h.save("S09-fairness.json", Map.of("pageLimit", 20, "pages", 3, "badOrderIds", badIds,
            "goodOrderId", Long.toString(goodOrder.order()), "failedCount", bounded.failedCloses.get(), "attempts", attempts));
        h.runtime.hooks.clear();
        for (int i = 0; i < 3; i++) { bounded.scanOnce(); }
        for (var f : used) { h.assertCounts(f, 1, 1, 2, 1); }
        assertTrue(Files.readString(h.evidence.resolve("application-errors.log")).contains("EXPIRY_CLOSE_FAILED"));
        // Deliberate test-only inconsistencies are diagnosed, not silently repaired; cleanup remains fixture-only.
        for (String mode : List.of("owner", "release")) {
            var f = h.fixture("S09-inconsistent-" + mode, 1); used.add(f); var inconsistent = hold(f, "s09-inconsistent-key");
            if (mode.equals("owner")) {
                h.jdbc.update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL WHERE user_id=? AND session_id=?", f.userA(), f.session());
            } else {
                h.store.insert("INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RELEASE',1,1,?)",
                    f.tier(), inconsistent.order(), sqlTime(BASE));
            }
            var before = h.json.valueToTree(h.snapshot(f));
            assertEquals(503, cancel(inconsistent.order()).status());
            assertEquals(before, h.json.valueToTree(h.snapshot(f)));
            assertEquals("HOLD", status(inconsistent.order()));
            h.save(f.name() + "-no-self-heal.json", h.snapshot(f));
        }
        done("S09", "Paused/post-close orders still expire; bounded cursor passes twenty failing candidates; inconsistent HOLD is diagnosed without self-healing", used);
    }
}
