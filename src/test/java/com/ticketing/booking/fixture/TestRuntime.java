package com.ticketing.booking.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.config.RuntimeServices;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestRuntime implements RuntimeServices,com.ticketing.booking.demo.DemoIdentityFilter.Access {
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
    private final Map<String,ManagementPrincipal> operators = new HashMap<>();
    private final Map<String,String> operatorTokens = new HashMap<>();
    private final Set<Long> allowedAttempts = ConcurrentHashMap.newKeySet();
    private final List<BookingHarness.Fixture> managementFixtures = new ArrayList<>();
    private String managementRun;
    private Path managementManifest;
    private volatile boolean demoMode;
    private final Map<String,Long> demoUsers = new ConcurrentHashMap<>();
    private final Map<Long,BookingHarness.Fixture> demoFixtures = new ConcurrentHashMap<>();

    private TestRuntime() {
        try {
            var secrets = new ObjectMapper().readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
            tokenA = secrets.path("tokenA").asText();
            tokenB = secrets.path("tokenB").asText();
        } catch (Exception failure) { throw new IllegalStateException("Test identity unavailable"); }
    }
    public static TestRuntime instance() { return enabled ? INSTANCE : null; }
    public String token(boolean second) { return second ? tokenB : tokenA; }
    public synchronized String operatorToken(String identity) {
        String token=operatorTokens.get(identity);
        if(token==null) throw new IllegalStateException("Management fixture unavailable");
        return token;
    }
    public synchronized void disableManagement() {
        operators.clear();operatorTokens.clear();allowedAttempts.clear();managementFixtures.clear();managementRun=null;managementManifest=null;
    }
    /** Only the test fixture can initialize this adapter; no production entry point calls it. */
    public synchronized void configureManagement(String runId,JdbcTemplate jdbc) throws Exception {
        configureManagementAt(runId,jdbc,Bootstrap.ROOT.resolve(".local/biz04-operators.json"),Bootstrap.ROOT.resolve(".local/biz04").resolve(runId));
    }
    public void enterDemo() { demoMode=true; direct=false; recovery=false; fixedTime=null; demoUsers.clear();demoFixtures.clear(); }
    public void leaveDemo() { demoMode=false;demoUsers.clear();demoFixtures.clear();disableManagement(); }
    public boolean demoMode() { return demoMode; }
    public synchronized void configureDemoManagement(String runId,JdbcTemplate jdbc,Path directory) throws Exception {
        if(!demoMode || !directory.toAbsolutePath().normalize().equals(Bootstrap.ROOT.resolve(".local/demo").resolve(runId)))
            throw new IllegalArgumentException("Invalid demo directory");
        verifyPrivate(directory);
        configureManagementAt(runId,jdbc,directory.resolve("operators.json"),directory);
    }
    public synchronized Map<String,String> registerDemoFixture(JdbcTemplate jdbc,BookingHarness.Fixture fixture) throws Exception {
        if(!demoMode) throw new IllegalStateException("Demo is disabled");
        verifyFixture(jdbc,fixture);
        var tokens=new LinkedHashMap<String,String>();
        for(var entry:Map.of("userA",fixture.userA(),"userB",fixture.userB()).entrySet()) {
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            demoUsers.put("Bearer "+token,entry.getValue());tokens.put(entry.getKey(),token);
        }
        demoFixtures.put(fixture.session(),fixture);
        return Map.copyOf(tokens);
    }
    public synchronized boolean demoAuthorization(String header) { return header!=null && (demoUsers.containsKey(header)||operators.containsKey(header)); }
    public boolean demoBookingScope(String header,long session,long tier) {
        var fixture=demoFixtures.get(session);var user=demoUsers.get(header);
        return fixture!=null && user!=null && (user==fixture.userA()||user==fixture.userB()) && (tier==fixture.tier()||tier==fixture.otherTier());
    }
    private void configureManagementAt(String runId,JdbcTemplate jdbc,Path credentials,Path directory) throws Exception {
        disableManagement();
        if(!runId.matches("[a-z0-9-]{8,50}")) throw new IllegalArgumentException("Invalid fixture run");
        var json=new ObjectMapper();
        if(!Files.exists(credentials,LinkOption.NOFOLLOW_LINKS)) {
            var random=new SecureRandom();var values=new LinkedHashMap<String,Object>();
            String[] names={"operatorA","operatorB","roleOnly","capabilityOnly"};
            for(int i=0;i<names.length;i++) {
                byte[] bytes=new byte[32];random.nextBytes(bytes);
                values.put(names[i],Map.of("token",Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                    "operatorId",10001L+i,"admin",i!=3,"operator",i!=2));
            }
            try { secureCreate(credentials,json.writeValueAsBytes(values)); }
            catch(FileAlreadyExistsException concurrentCreation) { /* Read the existing credential set below. */ }
        }
        verifyPrivate(credentials);
        var values=json.readTree(Files.readString(credentials));
        var seen=new HashSet<Long>();
        for(String name:List.of("operatorA","operatorB","roleOnly","capabilityOnly")) {
            var identity=values.path(name);String token=identity.path("token").asText();long id=identity.path("operatorId").asLong();
            boolean admin=identity.path("admin").asBoolean(),operator=identity.path("operator").asBoolean();
            if(!token.matches("[A-Za-z0-9_-]{43}") || id<=0 || !seen.add(id)
                || admin==name.equals("capabilityOnly") || operator==name.equals("roleOnly"))
                throw new IllegalStateException("Invalid management identity fixture");
            if(operators.put("Bearer "+token,new ManagementPrincipal(id,admin,operator))!=null)
                throw new IllegalStateException("Duplicate management identity fixture");
            operatorTokens.put(name,token);
        }
        secureDirectory(directory.getParent());secureDirectory(directory);
        managementRun=runId;managementManifest=directory.resolve("attempt-manifest.json");
        if(Files.exists(managementManifest,LinkOption.NOFOLLOW_LINKS)) {
            verifyPrivate(managementManifest);var saved=json.readTree(Files.readString(managementManifest));
            if(!runId.equals(saved.path("runId").asText())) throw new IllegalStateException("Fixture run mismatch");
            for(var node:saved.path("fixtures")) {
                var f=json.treeToValue(node,BookingHarness.Fixture.class);verifyFixture(jdbc,f);managementFixtures.add(f);
            }
            Set<Long> owned=ownedAttempts(jdbc);
            for(var node:saved.path("attemptIds")) {
                long id=node.asLong();if(id<=0 || !owned.contains(id)) throw new IllegalStateException("Unowned management fixture attempt");
                allowedAttempts.add(id);
            }
        } else { persistManagementManifest(); }
    }
    /** The manifest enrolls only rows queried through a verified, server-created fixture. */
    public synchronized void enrollManagementFixture(JdbcTemplate jdbc,BookingHarness.Fixture fixture) throws Exception {
        if(managementRun==null) return;
        verifyFixture(jdbc,fixture);
        boolean changed=!managementFixtures.contains(fixture);
        if(changed) managementFixtures.add(fixture);
        changed=allowedAttempts.addAll(ownedAttempts(jdbc))||changed;
        if(changed) persistManagementManifest();
    }
    public synchronized void refreshManagementAttempts(JdbcTemplate jdbc) throws Exception {
        if(managementRun==null) throw new IllegalStateException("Management fixture unavailable");
        allowedAttempts.addAll(ownedAttempts(jdbc));persistManagementManifest();
    }
    private void verifyFixture(JdbcTemplate jdbc,BookingHarness.Fixture f) {
        if(!Objects.equals(managementRun+":"+f.name(),jdbc.queryForObject("SELECT title FROM event WHERE id=?",String.class,f.event()))
            || !Objects.equals(managementRun+":"+f.name()+":a",jdbc.queryForObject("SELECT subject_key FROM app_user WHERE id=?",String.class,f.userA()))
            || !Objects.equals(managementRun+":"+f.name()+":b",jdbc.queryForObject("SELECT subject_key FROM app_user WHERE id=?",String.class,f.userB()))
            || jdbc.queryForObject("SELECT COUNT(*) FROM event_session WHERE id=? AND event_id=?",Integer.class,f.session(),f.event())!=1)
            throw new IllegalStateException("Unowned management fixture");
    }
    private Set<Long> ownedAttempts(JdbcTemplate jdbc) {
        Set<Long> ids=new HashSet<>();
        for(var f:managementFixtures) {
            verifyFixture(jdbc,f);
            ids.addAll(jdbc.queryForList("SELECT a.id FROM payment_attempt a JOIN booking_order o ON o.id=a.order_id AND o.user_id=a.user_id AND o.session_id=a.session_id WHERE a.session_id=? AND a.user_id IN (?,?) AND o.tier_id IN (?,?)",Long.class,f.session(),f.userA(),f.userB(),f.tier(),f.otherTier()));
        }
        return ids;
    }
    private void persistManagementManifest() throws Exception {
        var json=new ObjectMapper();byte[] bytes=json.writeValueAsBytes(Map.of("runId",managementRun,"fixtures",managementFixtures,"attemptIds",new TreeSet<>(allowedAttempts)));
        Path staged=managementManifest.resolveSibling("manifest-"+UUID.randomUUID()+".tmp");secureCreate(staged,bytes);
        Files.move(staged,managementManifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static UserPrincipal currentUser(Path path) throws Exception {
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }
    private static FileAttribute<?> privateAttribute(Path path,boolean directory) throws Exception {
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("acl")) {
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(currentUser(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<List<AclEntry>>() { public String name(){return "acl:acl";} public List<AclEntry> value(){return List.of(entry);} };
        }
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
    private static void secureDirectory(Path path) throws Exception {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            try {Files.createDirectory(path,privateAttribute(path,true));} catch(FileAlreadyExistsException raced) { }
        }
        verifyPrivate(path);
    }
    private static void secureCreate(Path path,byte[] bytes) throws Exception {
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),privateAttribute(path,false))) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);
        }
        verifyPrivate(path);
    }
    private static void verifyPrivate(Path path) throws Exception {
        if(Files.isSymbolicLink(path)) throw new IllegalStateException("Linked management fixture rejected");
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl!=null) {
            UserPrincipal current=currentUser(path);
            if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(current)) throw new IllegalStateException("Management fixture owner mismatch");
            for(var entry:acl.getAcl()) if(entry.type()==AclEntryType.ALLOW && !entry.principal().equals(current))
                throw new IllegalStateException("Management fixture permissions too broad");
        } else {
            for(var permission:Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS))
                if(permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
                    throw new IllegalStateException("Management fixture permissions too broad");
        }
    }
    @Override public synchronized ManagementPrincipal authenticateManagement(String header) {
        var principal=operators.get(header);if(principal!=null) return principal;
        return new ManagementPrincipal(authenticate(header),false,false);
    }
    @Override public Set<Long> managementAttempts() { return Set.copyOf(allowedAttempts); }
    @Override public synchronized boolean managementReady() { return managementRun!=null; }
    public void reset() { hooks.clear(); direct = true; recovery = false; fixedTime = null; simulation=true; plan=new SimulationPlan("SUCCESS",100); }
    @Override public boolean simulatorEnabled() { return simulation; }
    @Override public SimulationPlan simulationPlan() { return plan; }
    @Override public long authenticate(String header) {
        Long demo=demoUsers.get(header==null?"":header);
        if(demo!=null) return demo;
        if (("Bearer " + tokenA).equals(header)) { return userA; }
        if (("Bearer " + tokenB).equals(header)) { return userB; }
        throw new IllegalArgumentException("UNAUTHENTICATED");
    }
    @Override public Instant afterLocks(com.ticketing.booking.domain.DatabaseTimeSource databaseTime) {
        Instant actual = databaseTime.now();
        at("CLOCK_AFTER_LOCKS", 0);
        return fixedTime == null ? actual : fixedTime;
    }
    @Override public void at(String boundary, long id) {
        LongConsumer action = hooks.get(boundary);
        if (action != null) { action.accept(id); }
    }
    @Override public boolean directEnabled() { return direct && !demoMode; }
    @Override public boolean recoveryEnabled() { return recovery && !demoMode; }
    @Override public void redisKeys(java.util.List<String> keys) { observedRedisKeys.addAll(keys); }
}
