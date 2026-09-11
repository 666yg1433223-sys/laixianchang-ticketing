package com.ticketing.booking.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.config.RuntimeServices;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestRuntime implements RuntimeServices {
    private static final TestRuntime INSTANCE = new TestRuntime();
    public static volatile boolean enabled=true;
    public final Map<String, LongConsumer> hooks = new ConcurrentHashMap<>();
    public final java.util.Set<String> observedRedisKeys=ConcurrentHashMap.newKeySet();
    public volatile boolean direct = true;
    public volatile boolean recovery = true;
    public volatile boolean simulation = true;
    public volatile SimulationPlan plan = new SimulationPlan("SUCCESS",100);
    public volatile Instant fixedTime;
    public volatile long userA = 1;
    public volatile long userB = 2;
    private final String tokenA;
    private final String tokenB;

    private TestRuntime() {
        try {
            var secrets = new ObjectMapper().readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
            tokenA = secrets.path("tokenA").asText();
            tokenB = secrets.path("tokenB").asText();
        } catch (Exception failure) { throw new IllegalStateException("Test identity unavailable"); }
    }
    public static TestRuntime instance() { return enabled ? INSTANCE : null; }
    public String token(boolean second) { return second ? tokenB : tokenA; }
    public void reset() { hooks.clear(); direct = true; recovery = false; fixedTime = null; simulation=true; plan=new SimulationPlan("SUCCESS",100); }
    @Override public boolean simulatorEnabled() { return simulation; }
    @Override public SimulationPlan simulationPlan() { return plan; }
    @Override public long authenticate(String header) {
        if (("Bearer " + tokenA).equals(header)) { return userA; }
        if (("Bearer " + tokenB).equals(header)) { return userB; }
        throw new IllegalArgumentException("UNAUTHENTICATED");
    }
    @Override public Instant afterLocks(JdbcTemplate jdbc) {
        Instant actual = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class).toInstant();
        at("CLOCK_AFTER_LOCKS", 0);
        return fixedTime == null ? actual : fixedTime;
    }
    @Override public void at(String boundary, long id) {
        LongConsumer action = hooks.get(boundary);
        if (action != null) { action.accept(id); }
    }
    @Override public boolean directEnabled() { return direct; }
    @Override public boolean recoveryEnabled() { return recovery; }
    @Override public void redisKeys(java.util.List<String> keys) { observedRedisKeys.addAll(keys); }
}
