package com.avtchtask;

import com.avtchtask.db.DatabaseManager;
import com.avtchtask.repository.SQLiteModificationRepository;
import com.avtchtask.repository.SQLiteUserRepository;
import com.avtchtask.service.ModificationService;
import com.avtchtask.service.UserSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency test: alle USER_COUNT threads loggen sich zuerst alle ein (Phase 1),
 * dann schreiben alle gleichzeitig ihre Modifikationen (Phase 2),
 * dann loggen sich alle aus (Phase 3).
 * CountDownLatch-Barrieren erzwingen die Phasengrenzen.
 */
class ConcurrentUsersTest {

    private static final int USER_COUNT        = 1000;
    private static final int MODS_PER_USER     = 500;
    private static final int TIMEOUT_SECONDS   = 600;

    private DatabaseManager              db;
    private SQLiteUserRepository         userRepo;
    private SQLiteModificationRepository modRepo;
    private UserSessionService           sessionService;
    private ModificationService          modService;

    @BeforeEach
    void setUp() throws SQLException, java.io.FileNotFoundException {
        db             = new DatabaseManager(":memory:");
        db.metrics.setLogFile("target/write-log.txt");
        db.metrics.verboseLogging = true;
        db.metrics.recordSamples  = true;
        db.initSchema();
        userRepo       = new SQLiteUserRepository(db);
        modRepo        = new SQLiteModificationRepository(db);
        sessionService = new UserSessionService(userRepo);
        modService     = new ModificationService(modRepo);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void hundredUsers_eachInOwnThread_allOperationsSucceed() throws Exception {
        long startTimeMs = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(USER_COUNT);
        // Phase barriers: all threads must complete each phase before the next begins
        CountDownLatch startGate   = new CountDownLatch(1);          // release all at once
        CountDownLatch loginsDone  = new CountDownLatch(USER_COUNT);  // phase 1 → 2
        CountDownLatch modsDone    = new CountDownLatch(USER_COUNT);  // phase 2 → 3
        CountDownLatch doneLatch   = new CountDownLatch(USER_COUNT);  // all done

        AtomicInteger loginOk    = new AtomicInteger();
        AtomicInteger modOk      = new AtomicInteger();
        AtomicInteger logoutOk   = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>(USER_COUNT);

        for (int i = 0; i < USER_COUNT; i++) {
            final String userId = "user-" + (i + 1);
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();   // wait until all threads are ready

                    // ── PHASE 1: LOGIN (all threads login before any mod runs) ──
                    sessionService.login(userId);
                    loginOk.incrementAndGet();
                    loginsDone.countDown();
                    loginsDone.await();   // wait until every user is logged in

                    // ── PHASE 2: DATA_MODIFY (all users logged in) ──
                    for (int m = 0; m < MODS_PER_USER; m++) {
                        if (sessionService.isLoggedIn(userId)) {
                            modService.record(userId);
                            modOk.incrementAndGet();
                        }
                    }
                    modsDone.countDown();
                    modsDone.await();     // wait until every user has finished their mods

                    // ── PHASE 3: LOGOUT (all mods done before any logout runs) ──
                    sessionService.logout(userId);
                    logoutOk.incrementAndGet();

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("[TEST ERROR] " + userId + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        System.out.printf("[PHASE] Releasing %d threads…%n", USER_COUNT);
        startGate.countDown();
        loginsDone.await();
        System.out.printf("[PHASE] All %d logins done — starting mods%n", loginOk.get());
        modsDone.await();
        System.out.printf("[PHASE] All mods done — starting logouts%n");
        boolean finished = doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        System.out.printf("[PHASE] All %d logouts done%n", logoutOk.get());
        pool.shutdown();

        // ── Assertions ────────────────────────────────────────────────────────

        assertTrue(finished,
                "Timed out — not all threads finished within " + TIMEOUT_SECONDS + "s");

        assertEquals(0, errorCount.get(),
                "Some threads threw exceptions");

        assertEquals(USER_COUNT, loginOk.get(),
                "Not all logins succeeded");

        assertEquals((long) USER_COUNT * MODS_PER_USER, modOk.get(),
                "Not all modifications were recorded by the service");

        assertEquals(USER_COUNT, logoutOk.get(),
                "Not all logouts succeeded");

        // ── Verify database state ─────────────────────────────────────────────

        // All users logged out → no logged-in users remain
        Set<String> stillLoggedIn = userRepo.getLoggedInUsers();
        assertEquals(0, stillLoggedIn.size(),
                "Expected 0 logged-in users after all logouts, but found: " + stillLoggedIn);

        // Every user has exactly MODS_PER_USER modifications in the DB
        Map<String, Long> counts = modRepo.getModificationCountByUser();
        assertEquals(USER_COUNT, counts.size(),
                "Expected " + USER_COUNT + " distinct users in modifications table");

        for (int i = 0; i < USER_COUNT; i++) {
            String userId = "user-" + (i + 1);
            assertEquals(MODS_PER_USER, counts.getOrDefault(userId, 0L),
                    "Wrong modification count for " + userId);
        }

        // Total modification rows = USER_COUNT × MODS_PER_USER
        long totalMods = counts.values().stream().mapToLong(Long::longValue).sum();
        assertEquals((long) USER_COUNT * MODS_PER_USER, totalMods,
                "Total modifications in DB should be " + (USER_COUNT * MODS_PER_USER));

        // ── Final STATS printout ──────────────────────────────────────────────
        long durationMs = System.currentTimeMillis() - startTimeMs;
        Set<String> loggedInAfter   = userRepo.getLoggedInUsers();
        Set<String> usersWithMods   = modRepo.getUsersWithModifications();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║         CONCURRENT TEST — FINAL STATS    ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf( "║  Threads (users)       : %6d           ║%n", USER_COUNT);
        System.out.printf( "║  Modifications/user    : %6d           ║%n", MODS_PER_USER);
        System.out.printf( "║  Logins executed       : %6d           ║%n", loginOk.get());
        System.out.printf( "║  Modifications written : %6d           ║%n", modOk.get());
        System.out.printf( "║  Logouts executed      : %6d           ║%n", logoutOk.get());
        System.out.printf( "║  Errors                : %6d           ║%n", errorCount.get());
        System.out.printf( "║  Users still logged in : %6d           ║%n", loggedInAfter.size());
        System.out.printf( "║  Users with mods in DB : %6d           ║%n", usersWithMods.size());
        System.out.printf( "║  Total mod rows in DB  : %6d           ║%n", totalMods);
        System.out.printf( "║  Total duration        : %5d ms         ║%n", durationMs);
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf( "║  DB Write Timing  batch=%-3d (writes/txn) ║%n", db.getBatchSize());
        System.out.printf( "║  %-40s║%n", db.getLockStats());
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Detailed Write Statistics               ║");
        for (String line : db.metrics.getDetailedSummary(durationMs).split("\\r?\\n")) {
            System.out.printf("║  %-40s║%n", line);
        }
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
        generateTimingChart();
    }

    private void generateTimingChart() throws IOException {
        StringBuilder waitJs  = new StringBuilder();
        StringBuilder writeJs = new StringBuilder();
        StringBuilder labelsJs = new StringBuilder();
        // Sample every Nth write to keep chart readable (max 2000 points)
        List<long[]> samples = new ArrayList<>(db.metrics.writeSamples);
        int step = Math.max(1, samples.size() / 2000);
        int pointCount = 0;
        for (int i = 0; i < samples.size(); i += step) {
            long[] s = samples.get(i);
            if (waitJs.length() > 0) { waitJs.append(','); writeJs.append(','); labelsJs.append(','); }
            waitJs.append(s[1]);   // waitMs
            writeJs.append(s[2]);  // writeMs
            labelsJs.append(i + 1);
            pointCount++;
        }
        int n = samples.size();
        long avgWait  = samples.stream().mapToLong(s -> s[1]).sum() / Math.max(1, n);
        long avgWrite = samples.stream().mapToLong(s -> s[2]).sum() / Math.max(1, n);
        long maxWait  = samples.stream().mapToLong(s -> s[1]).max().orElse(0);

        // Load Chart.js from test resources (works offline, no CDN needed)
        String chartJs = "";
        try (java.io.InputStream cjs = getClass().getClassLoader().getResourceAsStream("chartjs.min.js")) {
            if (cjs != null) chartJs = new String(cjs.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("^\uFEFF", "")   // strip UTF-8 BOM if present
                    .replace("</script>", "<\\/script>");
        }

        String html = "<!DOCTYPE html>\n"
            + "<html lang='en'><head><meta charset='UTF-8'>"
            + "<title>DB Write Timing per Operation</title>\n"
            + "<script>" + chartJs + "</script>\n"
            + "<style>body{background:#0f1117;color:#e0e0e0;font-family:'Segoe UI',sans-serif;padding:24px;}"
            + "h1{color:#58a6ff;font-size:20px;margin-bottom:4px;}"
            + ".sub{color:#8b949e;font-size:12px;margin-bottom:20px;}"
            + ".cards{display:flex;gap:14px;margin-bottom:24px;}"
            + ".card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:14px 20px;flex:1;}"
            + ".card h3{font-size:11px;text-transform:uppercase;color:#8b949e;margin-bottom:8px;}"
            + ".card .val{font-size:26px;font-weight:700;color:#58a6ff;}"
            + ".card .unit{font-size:12px;color:#8b949e;margin-left:4px;}"
            + ".box{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:18px 20px;margin-bottom:20px;}"
            + ".box h2{font-size:14px;color:#c9d1d9;margin-bottom:14px;}"
            + ".note{font-size:11px;color:#6e7681;margin-top:8px;}"
            + "</style></head><body>\n"
            + "<h1>DB Write Timing — per Write Operation</h1>\n"
            + "<div class='sub'>" + USER_COUNT + " Threads &middot; " + MODS_PER_USER
            + " Mods/User &middot; " + n + " DB-Writes total &middot; ReentrantLock(fair=true)</div>\n"
            + "<div class='cards'>"
            + "<div class='card'><h3>Total Writes</h3><span class='val'>" + n + "</span></div>"
            + "<div class='card'><h3>Avg Lock Wait</h3><span class='val'>" + avgWait + "</span><span class='unit'>ms</span></div>"
            + "<div class='card'><h3>Max Lock Wait</h3><span class='val'>" + maxWait + "</span><span class='unit'>ms</span></div>"
            + "<div class='card'><h3>Avg SQL Time</h3><span class='val'>" + avgWrite + "</span><span class='unit'>ms</span></div>"
            + "</div>\n"
            + "<div class='box'><h2>Lock Wait Time per Write (ms) &mdash; in chronological order</h2>\n"
            + "<canvas id='wait' height='120'></canvas>"
            + "<p class='note'>x-axis: write number (chronological) &middot; y-axis: time in lock.lock() in ms &middot; High = high contention</p></div>\n"
            + "<div class='box'><h2>SQL Execution Time per Write (ms)</h2>\n"
            + "<canvas id='sql' height='100'></canvas>"
            + "<p class='note'>x-axis: write number &middot; y-axis: PreparedStatement.executeUpdate() in ms &middot; Should stay near 0</p></div>\n"
            + "<script>\n"
            + "const g={responsive:true,animation:{duration:0},elements:{point:{radius:0},line:{borderWidth:1}},"
            + "plugins:{legend:{display:false}},"
            + "scales:{x:{display:true,ticks:{color:'#8b949e',maxTicksLimit:10},grid:{color:'rgba(48,54,61,0.8)'}},"
            + "y:{ticks:{color:'#8b949e'},grid:{color:'rgba(48,54,61,0.8)'}}}}; \n"
            + "new Chart(document.getElementById('wait'),{type:'line',"
            + "data:{labels:[" + labelsJs + "],datasets:[{data:[" + waitJs + "],borderColor:'rgba(88,166,255,0.8)',backgroundColor:'rgba(88,166,255,0.08)',fill:true}]},"
            + "options:{...g,scales:{...g.scales,y:{...g.scales.y,title:{display:true,text:'ms (Lock Wait)',color:'#8b949e'}}}}});\n"
            + "new Chart(document.getElementById('sql'),{type:'line',"
            + "data:{labels:[" + labelsJs + "],datasets:[{data:[" + writeJs + "],borderColor:'rgba(86,211,100,0.8)',backgroundColor:'rgba(86,211,100,0.08)',fill:true}]},"
            + "options:{...g,scales:{...g.scales,y:{...g.scales.y,title:{display:true,text:'ms (SQL)',color:'#8b949e'}}}}});\n"
            + "</script></body></html>\n";

        String path = "target/write-timing.html";
        Files.write(Paths.get(path), html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("[CHART] Diagram gespeichert: " + Paths.get(path).toAbsolutePath());
    }
}
