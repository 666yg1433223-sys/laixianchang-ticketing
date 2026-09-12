package com.ticketing.booking.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketing.booking.fixture.BookingHarness.Fixture;
import com.ticketing.booking.fixture.BookingHarness.Reply;
import com.ticketing.booking.service.PaymentService;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import static com.ticketing.booking.domain.Values.instant;
import static com.ticketing.booking.domain.Values.number;

/** Real HTTP demonstrations, driven only inside the trusted local test session. */
public final class DemoScenarios {
    private static final String MANAGEMENT = "/v1/internal/sim";
    private final DemoSession session;
    private final List<Map<String,Object>> completed = new ArrayList<>();
    private record Booking(Fixture fixture, long requestId, long orderId) { }
    private record Payment(Booking booking, long attemptId) { }

    private DemoScenarios(DemoSession session) { this.session = session; }

    public static Map<String,Object> run(DemoSession session) throws Exception {
        return new DemoScenarios(session).execute();
    }

    private Map<String,Object> execute() throws Exception {
        check(!session.h.runtime.direct && !session.h.runtime.recovery, "Unscoped automatic processing must be disabled");
        check(session.h.runtime.hooks.isEmpty(), "Unexpected fault hook before demonstration");
        session.h.cleanOnClose = false;
        try {
            normalPaymentAndAccess();
            cancellation();
            expiry();
            lateSuccess();
            failureRecovery();
            session.verifyPriorFactsUnchanged();
            var result = new LinkedHashMap<String,Object>();
            result.put("success", true);
            result.put("runId", session.h.runId);
            result.put("simulated", true);
            result.put("realFunds", false);
            result.put("fixturesRetained", true);
            result.put("scenarios", List.copyOf(completed));
            result.put("summary", "5组真实HTTP场景及独立SQL不变量通过；含正常付款、权限拒绝、取消、受控到期、迟到冲正和授权恢复。模拟支付，无真实资金，夹具保留。");
            save("scenarios-result.json", result);
            return result;
        } catch (Exception | AssertionError failure) {
            save("scenarios-failure.json", Map.of("success", false, "completed", List.copyOf(completed),
                "failureType", failure.getClass().getSimpleName(), "fixturesRetained", true));
            throw failure;
        } finally {
            session.setTime(null);
            session.plan("SUCCESS", 100);
        }
    }

    private void normalPaymentAndAccess() throws Exception {
        Fixture fixture = fresh("http-paid");
        Booking booking = hold(fixture, fixture.tier(), "paid-booking-key");
        Payment payment = pay(booking, "paid-payment-key", "SUCCESS", 100);
        awaitPayment(payment, "APPLIED", "PAID");

        Reply duplicateBooking = postBooking(fixture, fixture.tier(), "paid-booking-key");
        requireStatus(duplicateBooking, 202);
        equal(booking.requestId(), requiredId(duplicateBooking.body().path("requestId")), "Same booking key reused original request");
        Reply duplicatePayment = request(fixture, "POST", orderPath(booking) + "/payment-attempts", "{}", "paid-payment-key", false, true);
        requireStatus(duplicatePayment, 202);
        equal(payment.attemptId(), requiredId(duplicatePayment.body().path("attemptId")), "Same payment key reused original attempt");
        assertFacts(fixture, 1, 1, 1, 0, 1, 0);
        assertPayment(payment, "SUCCESS", "APPLIED", "PAID");

        JsonNode before = snapshot(fixture);
        Reply paidCancel = request(fixture, "POST", orderPath(booking) + "/cancel", "{}", null, false, true);
        requireStatus(paidCancel, 409);
        requireText(paidCancel.body(), "code", "PAID_CANCEL_DISABLED");
        requireStatus(request(fixture, "GET", orderPath(booking), null, null, false, false), 401);
        for (String path : List.of("/v1/booking-requests/" + booking.requestId(), orderPath(booking), paymentPath(payment))) {
            requireStatus(request(fixture, "GET", path, null, null, true, true), 404);
        }
        requireStatus(request(fixture, "POST", orderPath(booking) + "/cancel", "{}", null, true, true), 404);
        requireStatus(request(fixture, "POST", orderPath(booking) + "/payment-attempts", "{}", "other-user-pay-key", true, true), 404);
        String recovery = MANAGEMENT + "/payment-attempts/" + payment.attemptId() + "/recover";
        requireStatus(request(fixture, "POST", recovery, "{\"reason\":\"ordinary caller\"}", "ordinary-recover-key", false, true), 403);
        requireStatus(session.managementRequest("POST", recovery, "{\"reason\":\"anonymous caller\"}", "anonymous-recover-key", null), 401);
        equal(before, snapshot(fixture), "Rejected calls leave all business facts unchanged");
        done("normal-payment-and-access", fixture, false,
            "真实HTTP申请和付款；原幂等键不重复扣库存；已付取消409、匿名401、他人资源404、普通恢复调用403。");
    }

    private void cancellation() throws Exception {
        Fixture fixture = fresh("http-cancel");
        Booking booking = hold(fixture, fixture.tier(), "cancel-booking-key");
        Reply first = request(fixture, "POST", orderPath(booking) + "/cancel", "{}", null, false, true);
        requireStatus(first, 200);
        requireText(first.body(), "orderStatus", "CANCELLED");
        Reply repeated = request(fixture, "POST", orderPath(booking) + "/cancel", "{}", null, false, true);
        requireStatus(repeated, 200);
        equal(first.body(), repeated.body(), "Repeated cancellation preserves the original terminal result");
        assertOrder(booking, "CANCELLED");
        assertFacts(fixture, 1, 1, 2, 1, 0, 0);
        done("cancel-and-repeat", fixture, false, "未付款本人取消后仅归还一次库存，重复取消没有第二条RELEASE。");
    }

    private void expiry() throws Exception {
        Fixture fixture = fresh("http-expiry");
        Booking booking = hold(fixture, fixture.tier(), "expiry-booking-key");
        Instant deadline = instant(order(booking).get("pay_expires_at"));
        session.setTime(deadline);
        session.driveOnce();
        assertOrder(booking, "EXPIRED");
        Reply repeated = request(fixture, "POST", orderPath(booking) + "/cancel", "{}", null, false, true);
        requireStatus(repeated, 200);
        requireText(repeated.body(), "orderStatus", "EXPIRED");
        session.driveOnce();
        assertFacts(fixture, 1, 1, 2, 1, 0, 0);
        done("controlled-expiry", fixture, true, "受控验证：只推进本run服务端时钟至付款期限，原到期业务释放一次库存；未修改订单时间字段。");
    }

    private void lateSuccess() throws Exception {
        Fixture fixture = fresh("http-late");
        Booking original = hold(fixture, fixture.tier(), "late-original-booking");
        Payment payment = pay(original, "late-original-payment", "SUCCESS", 120_000);
        Reply cancelled = request(fixture, "POST", orderPath(original) + "/cancel", "{}", null, false, true);
        requireStatus(cancelled, 200);
        requireText(cancelled.body(), "orderStatus", "CANCELLED");
        Booking replacement = hold(fixture, fixture.otherTier(), "late-replacement-booking");
        JsonNode replacementBefore = session.h.json.valueToTree(order(replacement));
        Instant generationTime = instant(attempt(payment).get("planned_at"));
        check(generationTime.isBefore(instant(order(replacement).get("pay_expires_at"))), "Replacement stays within payment deadline");
        session.setTime(generationTime);
        session.driveOnce();
        assertPayment(payment, "SUCCESS", "REVERSED", "CANCELLED");
        assertOrder(replacement, "HOLD");
        equal(replacementBefore, session.h.json.valueToTree(order(replacement)), "Late old result never changes the replacement order");
        equal(replacement.orderId(), session.h.jdbc.queryForObject(
            "SELECT owner_id FROM user_session_quota WHERE user_id=? AND session_id=?", Long.class, fixture.userA(), fixture.session()),
            "Quota still belongs to the replacement order");
        var settled = attempt(payment);
        check(settled.get("compensation_id") != null, "Late success has a compensation identity");
        equal(number(settled, "amount_cent"), number(settled, "compensation_cent"), "Compensation equals original authoritative amount");
        session.driveOnce();
        equal(session.h.json.valueToTree(settled), session.h.json.valueToTree(attempt(payment)), "Repeated processing preserves original compensation");
        assertFacts(fixture, 2, 2, 3, 1, 1, 0);
        done("late-success-protects-new-owner", fixture, true,
            "受控验证：旧单取消、新票档重新占位；旧SUCCESS只能REVERSED，补偿唯一且新订单/资格不受影响。");
    }

    private void failureRecovery() throws Exception {
        Fixture fixture = fresh("http-recover");
        Booking booking = hold(fixture, fixture.tier(), "recover-booking-key");
        Payment payment = pay(booking, "recover-payment-key", "SUCCESS", 100);
        PaymentService service = session.h.app.getBean(PaymentService.class);
        assertOwned(payment);
        session.setTime(instant(attempt(payment).get("planned_at")));
        service.generate(payment.attemptId());
        JsonNode immutableResult = authoritativeResult(payment);
        session.h.runtime.hooks.put("P2_AFTER_SETTLEMENT", id -> {
            if (id == payment.attemptId()) { throw new IllegalStateException("DEMO_CONTROLLED_SETTLEMENT_FAILURE"); }
        });
        try {
            for (int count = 1; count <= 6; count++) {
                session.setTime(instant(event(payment).get("next_attempt_at")));
                service.settle(payment.attemptId());
                equal((long) count, number(event(payment), "attempts"), "Real failure counter increased exactly once");
            }
        } finally {
            session.h.runtime.hooks.remove("P2_AFTER_SETTLEMENT");
        }
        equal("DEAD", event(payment).get("state"), "Six failures stop automatic recovery");
        equal("REVIEW", attempt(payment).get("settlement_status"), "Settlement requires explicit recovery");
        assertOrder(booking, "HOLD");
        assertFacts(fixture, 1, 1, 1, 0, 1, 0);
        save("recovery-before-http.json", session.h.snapshot(fixture));

        Reply failures = session.managementRequest("GET", MANAGEMENT + "/payment-failures?limit=50", null, null, "operatorA");
        requireStatus(failures, 200);
        boolean listed = false;
        for (JsonNode item : failures.body().path("items")) {
            if (requiredId(item.path("attemptId")) == payment.attemptId()) { listed = true; }
        }
        check(listed, "Failure appears in the scoped real HTTP management list");
        String route = MANAGEMENT + "/payment-attempts/" + payment.attemptId() + "/recover";
        String body = "{\"reason\":\"Controlled demo fault removed; replay original trusted result\"}";
        Reply accepted = session.managementRequest("POST", route, body, "recovery-operation-key", "operatorA");
        requireStatus(accepted, 202);
        requireText(accepted.body(), "status", "ACCEPTED");
        requireText(accepted.body(), "beforeSettlement", "REVIEW");
        requireText(accepted.body(), "beforeEvent", "DEAD");
        equal(6, accepted.body().path("beforeAttempts").asInt(), "Receipt preserves failed round facts");
        String operationId = accepted.body().path("operationId").asText();
        check(operationId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Operation ID comes from the actual response");
        String operationPath = MANAGEMENT + "/operations/" + operationId;
        equal(operationPath, accepted.body().path("statusUrl").asText(), "Operation URL is the expected local route");
        Reply duplicate = session.managementRequest("POST", route, body, "recovery-operation-key", "operatorA");
        requireStatus(duplicate, 202);
        equal(accepted.body(), duplicate.body(), "Same recovery key reuses the accepted operation");
        session.driveOnce();
        Reply completedOperation = session.managementRequest("GET", operationPath, null, null, "operatorA");
        requireStatus(completedOperation, 200);
        requireText(completedOperation.body(), "status", "COMPLETED");
        requireText(completedOperation.body(), "result", "APPLIED");
        assertPayment(payment, "SUCCESS", "APPLIED", "PAID");
        equal(immutableResult, authoritativeResult(payment), "Manual recovery never regenerates payment result/amount");
        equal(1L, number(event(payment), "recovery_generation"), "Exactly one new recovery generation");
        var audit = session.h.jdbc.queryForMap("SELECT * FROM sim_payment_operation WHERE operation_id=? AND attempt_id=?", operationId, payment.attemptId());
        equal("COMPLETED", audit.get("status"), "Audit completion is durable");
        equal("APPLIED", audit.get("result"), "Audit result agrees with payment facts");
        equal(1L, number(audit, "generation"), "Audit belongs to current recovery generation");
        check(audit.get("completed_at") != null, "Audit has a completion time");
        assertFacts(fixture, 1, 1, 1, 0, 1, 1);
        done("controlled-failure-and-authorized-recovery", fixture, true,
            "受控注入六次真实事务失败，形成DEAD/REVIEW；管理员HTTP恢复，重复键不增操作，原P2收敛为PAID并持久完成操作记录。");
    }

    private Fixture fresh(String name) throws Exception {
        session.setTime(null);
        session.plan("SUCCESS", 100);
        return session.fixture(name, 1);
    }

    private Booking hold(Fixture fixture, long tier, String key) throws Exception {
        Reply accepted = postBooking(fixture, tier, key);
        requireStatus(accepted, 202);
        long requestId = requiredId(accepted.body().path("requestId"));
        equal("/v1/booking-requests/" + requestId, accepted.body().path("statusUrl").asText(), "Request URL comes from accepted request");
        session.driveOnce();
        Reply query = request(fixture, "GET", "/v1/booking-requests/" + requestId, null, null, false, true);
        requireStatus(query, 200);
        requireText(query.body(), "status", "SUCCEEDED");
        requireText(query.body().path("order"), "orderStatus", "HOLD");
        long orderId = requiredId(query.body().path("order").path("orderId"));
        Booking booking = new Booking(fixture, requestId, orderId);
        assertOrder(booking, "HOLD");
        return booking;
    }

    private Reply postBooking(Fixture fixture, long tier, String key) throws Exception {
        String body = session.h.json.writeValueAsString(Map.of("sessionId", Long.toString(fixture.session()),
            "tierId", Long.toString(tier), "quantity", 1));
        return request(fixture, "POST", "/v1/booking-requests", body, key, false, true);
    }

    private Payment pay(Booking booking, String key, String outcome, long delay) throws Exception {
        session.plan(outcome, delay);
        Reply accepted = request(booking.fixture(), "POST", orderPath(booking) + "/payment-attempts", "{}", key, false, true);
        requireStatus(accepted, 202);
        check(accepted.body().path("simulated").isBoolean() && accepted.body().path("simulated").asBoolean(), "Payment is explicitly simulated");
        Payment payment = new Payment(booking, requiredId(accepted.body().path("attemptId")));
        equal(paymentPath(payment), accepted.body().path("statusUrl").asText(), "Payment URL is the actual returned attempt");
        assertOwned(payment);
        return payment;
    }

    private void awaitPayment(Payment payment, String settlement, String orderState) throws Exception {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < timeout) {
            session.driveOnce();
            Reply reply = request(payment.booking().fixture(), "GET", paymentPath(payment), null, null, false, true);
            requireStatus(reply, 200);
            if (settlement.equals(reply.body().path("settlementStatus").asText())) {
                requireText(reply.body(), "orderStatus", orderState);
                requireText(reply.body(), "resultStatus", "SUCCESS");
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Bounded payment polling timed out");
    }

    private void assertOwned(Payment payment) {
        Fixture f = payment.booking().fixture();
        equal(1, session.h.jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_attempt a JOIN booking_order o ON o.id=a.order_id AND o.user_id=a.user_id AND o.session_id=a.session_id JOIN booking_request r ON r.id=o.request_id AND r.order_id=o.id WHERE a.id=? AND a.order_id=? AND a.user_id=? AND a.session_id=? AND r.id=? AND o.tier_id IN (?,?)",
            Integer.class, payment.attemptId(), payment.booking().orderId(), f.userA(), f.session(), payment.booking().requestId(), f.tier(), f.otherTier()),
            "HTTP IDs resolve only to this server-owned fixture chain");
    }

    private void assertPayment(Payment payment, String result, String settlement, String orderStatus) throws Exception {
        assertOwned(payment);
        Reply reply = request(payment.booking().fixture(), "GET", paymentPath(payment), null, null, false, true);
        requireStatus(reply, 200);
        requireText(reply.body(), "resultStatus", result);
        requireText(reply.body(), "settlementStatus", settlement);
        requireText(reply.body(), "orderStatus", orderStatus);
        var row = attempt(payment);
        equal(result, row.get("result_status"), "SQL authoritative result");
        equal(settlement, row.get("settlement_status"), "SQL settlement result");
        equal("DONE", event(payment).get("state"), "SQL result event converges to DONE");
        equal(number(order(payment.booking()), "payable_cent"), number(row, "amount_cent"), "Payment uses the order price snapshot");
        equal("CNY", row.get("currency"), "Payment currency");
        assertOrder(payment.booking(), orderStatus);
    }

    private void assertOrder(Booking booking, String state) throws Exception {
        Reply reply = request(booking.fixture(), "GET", orderPath(booking), null, null, false, true);
        requireStatus(reply, 200);
        requireText(reply.body(), "orderStatus", state);
        equal(state, order(booking).get("status"), "HTTP and SQL order state agree");
    }

    private void assertFacts(Fixture fixture, int requests, int orders, int ledger, int standardAvailable, int attempts, int operations) {
        // This uses the independent IT application-account connection, not response-only assertions.
        session.h.assertCounts(fixture, requests, orders, ledger, standardAvailable);
        equal(attempts, session.h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE session_id=? AND user_id IN (?,?)",
            Integer.class, fixture.session(), fixture.userA(), fixture.userB()), "Exact fixture payment attempt count");
        equal(operations, session.h.jdbc.queryForObject("SELECT COUNT(*) FROM sim_payment_operation op JOIN payment_attempt a ON a.id=op.attempt_id WHERE a.session_id=? AND a.user_id IN (?,?)",
            Integer.class, fixture.session(), fixture.userA(), fixture.userB()), "Exact fixture management operation count");
        equal(0, session.h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt a JOIN booking_order o ON o.id=a.order_id WHERE a.session_id=? AND (a.user_id<>o.user_id OR a.session_id<>o.session_id OR a.amount_cent<>o.payable_cent OR a.currency<>o.currency)",
            Integer.class, fixture.session()), "No payment ownership, amount or currency mismatch");
    }

    private Map<String,Object> order(Booking booking) {
        return session.h.jdbc.queryForMap("SELECT * FROM booking_order WHERE id=? AND request_id=? AND user_id=? AND session_id=?",
            booking.orderId(), booking.requestId(), booking.fixture().userA(), booking.fixture().session());
    }
    private Map<String,Object> attempt(Payment payment) {
        return session.h.jdbc.queryForMap("SELECT * FROM payment_attempt WHERE id=? AND order_id=? AND user_id=?",
            payment.attemptId(), payment.booking().orderId(), payment.booking().fixture().userA());
    }
    private Map<String,Object> event(Payment payment) {
        return session.h.jdbc.queryForMap("SELECT * FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED'", payment.attemptId());
    }
    private JsonNode authoritativeResult(Payment payment) {
        var source = attempt(payment);
        var result = new LinkedHashMap<String,Object>();
        for (String key : List.of("result_id", "result_digest", "result_status", "amount_cent", "currency", "result_at", "result_reason", "planned_status", "planned_at", "resolution_deadline")) {
            result.put(key, source.get(key));
        }
        return session.h.json.valueToTree(result);
    }
    private JsonNode snapshot(Fixture fixture) { return session.h.json.valueToTree(session.h.snapshot(fixture)); }
    private Reply request(Fixture fixture, String method, String path, String body, String key, boolean second, boolean auth) throws Exception {
        return session.request(fixture, method, path, body, key, second, auth);
    }
    private void done(String name, Fixture fixture, boolean controlled, String notes) throws Exception {
        session.verifyPriorFactsUnchanged();
        save(name + "-facts.json", session.h.snapshot(fixture));
        var item = Map.<String,Object>of("name", name, "success", true, "controlledTimeOrFault", controlled,
            "notes", notes, "fixture", fixture, "facts", name + "-facts.json");
        completed.add(item);
        Files.writeString(session.evidence().resolve("scenario-progress.jsonl"), session.h.json.writeValueAsString(item) + System.lineSeparator(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private void save(String filename, Object body) throws Exception {
        Files.writeString(session.evidence().resolve(filename), session.h.json.writerWithDefaultPrettyPrinter().writeValueAsString(body),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static String orderPath(Booking booking) { return "/v1/orders/" + booking.orderId(); }
    private static String paymentPath(Payment payment) { return "/v1/payment-attempts/" + payment.attemptId(); }
    static long requiredId(JsonNode value) {
        check(value.isTextual() && value.asText().matches("[1-9][0-9]{0,18}"), "Response must contain a positive decimal string ID");
        try { return Long.parseLong(value.asText()); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("Response ID exceeds supported range"); }
    }
    static void requireStatus(Reply reply, int expected) { equal(expected, reply.status(), "Unexpected real HTTP response status"); }
    static void requireText(JsonNode node, String field, String expected) {
        check(node.path(field).isTextual() && expected.equals(node.path(field).asText()), "Unexpected response field: " + field);
    }
    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) { throw new IllegalStateException(label); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
