package com.avtchtask;

import com.avtchtask.db.DatabaseManager;
import com.avtchtask.repository.SQLiteModificationRepository;
import com.avtchtask.repository.SQLiteUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that run against a real in-memory SQLite database.
 * Covers DatabaseManager, SQLiteUserRepository and SQLiteModificationRepository.
 */
class DatabaseIntegrationTest {

    private DatabaseManager db;
    private SQLiteUserRepository userRepo;
    private SQLiteModificationRepository modRepo;

    @BeforeEach
    void setUp() throws SQLException {
        // ":memory:" creates a fresh, private in-memory database for each test
        db = new DatabaseManager(":memory:");
        db.initSchema();
        userRepo = new SQLiteUserRepository(db);
        modRepo  = new SQLiteModificationRepository(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // -------------------------------------------------------------------------
    // Schema / DatabaseManager
    // -------------------------------------------------------------------------

    @Test
    void initSchema_createsBothTables() throws SQLException {
        // If the schema was not created, the queries below would throw.
        // Simply querying an empty result proves the tables exist.
        Set<String> users = userRepo.getLoggedInUsers();
        assertNotNull(users);
        assertTrue(users.isEmpty());

        Map<String, Long> mods = modRepo.getModificationCountByUser();
        assertNotNull(mods);
        assertTrue(mods.isEmpty());
    }

    // -------------------------------------------------------------------------
    // SQLiteUserRepository – login / logout
    // -------------------------------------------------------------------------

    @Test
    void login_persistsUserAsLoggedIn() {
        userRepo.login("alice");
        assertTrue(userRepo.isLoggedIn("alice"));
    }

    @Test
    void login_unknownUser_isNotLoggedIn() {
        assertFalse(userRepo.isLoggedIn("nobody"));
    }

    @Test
    void logout_setsUserAsLoggedOut() {
        userRepo.login("alice");
        userRepo.logout("alice");
        assertFalse(userRepo.isLoggedIn("alice"));
    }

    @Test
    void login_twice_doesNotDuplicateRecord() {
        userRepo.login("alice");
        userRepo.login("alice"); // upsert — must not throw
        assertTrue(userRepo.isLoggedIn("alice"));
    }

    @Test
    void getLoggedInUsers_returnsOnlyLoggedInUsers() {
        userRepo.login("alice");
        userRepo.login("bob");
        userRepo.login("carol");
        userRepo.logout("bob");

        Set<String> loggedIn = userRepo.getLoggedInUsers();
        assertEquals(2, loggedIn.size());
        assertTrue(loggedIn.contains("alice"));
        assertTrue(loggedIn.contains("carol"));
        assertFalse(loggedIn.contains("bob"));
    }

    @Test
    void getLoggedInUsers_emptyWhenNoneLoggedIn() {
        userRepo.login("alice");
        userRepo.logout("alice");
        assertTrue(userRepo.getLoggedInUsers().isEmpty());
    }

    // -------------------------------------------------------------------------
    // SQLiteModificationRepository
    // -------------------------------------------------------------------------

    @Test
    void addModification_persistsRecord() {
        modRepo.addModification("alice", "2026-01-01T10:00:00");
        Set<String> users = modRepo.getUsersWithModifications();
        assertTrue(users.contains("alice"));
    }

    @Test
    void getModificationCountByUser_countsCorrectly() {
        modRepo.addModification("alice", "2026-01-01T10:00:00");
        modRepo.addModification("alice", "2026-01-01T11:00:00");
        modRepo.addModification("bob",   "2026-01-01T12:00:00");

        Map<String, Long> counts = modRepo.getModificationCountByUser();
        assertEquals(2L, counts.get("alice"));
        assertEquals(1L, counts.get("bob"));
    }

    @Test
    void getUsersWithModifications_returnsDistinctUsers() {
        modRepo.addModification("alice", "2026-01-01T10:00:00");
        modRepo.addModification("alice", "2026-01-01T11:00:00");
        modRepo.addModification("bob",   "2026-01-01T12:00:00");

        Set<String> users = modRepo.getUsersWithModifications();
        assertEquals(2, users.size());
        assertTrue(users.contains("alice"));
        assertTrue(users.contains("bob"));
    }

    @Test
    void getUsersWithModifications_emptyWhenNoModifications() {
        assertTrue(modRepo.getUsersWithModifications().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Cross-repository: login state does not interfere with modifications
    // -------------------------------------------------------------------------

    @Test
    void modificationPersistedRegardlessOfLoginState() {
        // Record modification, then log user out — modification must still be stored
        modRepo.addModification("alice", "2026-01-01T10:00:00");
        userRepo.login("alice");
        userRepo.logout("alice");

        assertFalse(userRepo.isLoggedIn("alice"));
        assertTrue(modRepo.getUsersWithModifications().contains("alice"));
    }
}
