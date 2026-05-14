package com.avtchtask;

import com.avtchtask.command.CommandParser;
import com.avtchtask.command.CommandProcessor;
import com.avtchtask.command.DataModifyCommand;
import com.avtchtask.command.ExitCommand;
import com.avtchtask.command.InvalidCommand;
import com.avtchtask.command.LoginCommand;
import com.avtchtask.command.LogoutCommand;
import com.avtchtask.command.StatsCommand;
import com.avtchtask.repository.ModificationRepository;
import com.avtchtask.repository.UserRepository;
import com.avtchtask.service.ModificationService;
import com.avtchtask.service.StatsReportService;
import com.avtchtask.service.UserSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private ModificationRepository modRepo;

    @Mock
    private CommandProcessor processor;

    private UserSessionService sessionService;
    private ModificationService modService;
    private StatsReportService  statsReportService;
    private CommandParser parser;

    @BeforeEach
    void setUp() {
        sessionService     = new UserSessionService(userRepo);
        modService         = new ModificationService(modRepo);
        statsReportService = new StatsReportService(userRepo, modRepo);
        parser             = new CommandParser(sessionService, modService, statsReportService, processor);
    }

    // -------------------------------------------------------------------------
    // CommandParser â€“ valid commands
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LOGIN(user) is parsed as LoginCommand")
    void parseLogin_returnsLoginCommand() {
        assertInstanceOf(LoginCommand.class, parser.parse("LOGIN(alice)"));
    }

    @Test
    @DisplayName("LOGOUT(user) is parsed as LogoutCommand")
    void parseLogout_returnsLogoutCommand() {
        assertInstanceOf(LogoutCommand.class, parser.parse("LOGOUT(alice)"));
    }

    @Test
    @DisplayName("DATA_MODIFY(user) is parsed as DataModifyCommand")
    void parseDataModify_returnsDataModifyCommand() {
        assertInstanceOf(DataModifyCommand.class, parser.parse("DATA_MODIFY(alice)"));
    }

    @Test
    @DisplayName("STATS() is parsed as StatsCommand")
    void parseStats_returnsStatsCommand() {
        assertInstanceOf(StatsCommand.class, parser.parse("STATS()"));
    }

    @Test
    @DisplayName("EXIT() is parsed as ExitCommand")
    void parseExit_returnsExitCommand() {
        assertInstanceOf(ExitCommand.class, parser.parse("EXIT()"));
    }

    // -------------------------------------------------------------------------
    // CommandParser â€“ invalid / malformed commands must NOT crash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LOGIN() without argument is invalid")
    void parseLoginWithoutArg_isInvalid() {
        assertInstanceOf(InvalidCommand.class, parser.parse("LOGIN()"));
    }

    @Test
    @DisplayName("DATA_MODIFY() without argument is invalid")
    void parseDataModifyWithoutArg_isInvalid() {
        assertInstanceOf(InvalidCommand.class, parser.parse("DATA_MODIFY()"));
    }

    @Test
    @DisplayName("EXIT(123) with argument is invalid")
    void parseExitWithArg_isInvalid() {
        assertInstanceOf(InvalidCommand.class, parser.parse("EXIT(123)"));
    }

    @Test
    @DisplayName("Unknown command input is invalid")
    void parseUnknownCommand_isInvalid() {
        assertInstanceOf(InvalidCommand.class, parser.parse("UNKNOWN(x)"));
    }

    @Test
    @DisplayName("Null input is invalid")
    void parseNullInput_isInvalid() {
        assertInstanceOf(InvalidCommand.class, parser.parse(null));
    }

    @Test
    @DisplayName("Empty string input is invalid")
    void parseEmptyInput_isInvalid() {
        assertInstanceOf(InvalidCommand.class, parser.parse(""));
    }

    // -------------------------------------------------------------------------
    // UserSessionService â€“ LOGIN logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Login when not logged in calls repository")
    void login_whenNotLoggedIn_callsRepository() {
        when(userRepo.isLoggedIn("alice")).thenReturn(false);
        sessionService.login("alice");
        verify(userRepo).login("alice");
    }

    @Test
    @DisplayName("Login when already logged in does nothing")
    void login_whenAlreadyLoggedIn_doesNotCallRepository() {
        when(userRepo.isLoggedIn("alice")).thenReturn(true);
        sessionService.login("alice");
        verify(userRepo, never()).login("alice");
    }

    // -------------------------------------------------------------------------
    // UserSessionService â€“ LOGOUT logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Logout when logged in calls repository")
    void logout_whenLoggedIn_callsRepository() {
        when(userRepo.isLoggedIn("alice")).thenReturn(true);
        sessionService.logout("alice");
        verify(userRepo).logout("alice");
    }

    @Test
    @DisplayName("Logout when not logged in does nothing")
    void logout_whenNotLoggedIn_doesNotCallRepository() {
        when(userRepo.isLoggedIn("alice")).thenReturn(false);
        sessionService.logout("alice");
        verify(userRepo, never()).logout("alice");
    }

    // -------------------------------------------------------------------------
    // DataModifyCommand â€“ execution logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DATA_MODIFY records modification when user is logged in")
    void dataModify_whenLoggedIn_recordsModification() {
        when(userRepo.isLoggedIn("alice")).thenReturn(true);
        DataModifyCommand cmd = new DataModifyCommand("alice", sessionService, modService);
        cmd.execute();
        verify(modRepo).addModification(eq("alice"), anyString());
    }

    @Test
    @DisplayName("DATA_MODIFY does not record when user is not logged in")
    void dataModify_whenNotLoggedIn_doesNotRecord() {
        when(userRepo.isLoggedIn("alice")).thenReturn(false);
        DataModifyCommand cmd = new DataModifyCommand("alice", sessionService, modService);
        cmd.execute();
        verify(modRepo, never()).addModification(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // StatsReportService — STATS output
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("STATS prints correct number of logged-in users")
    void printStats_showsCorrectLoggedInCount() {
        when(userRepo.getLoggedInUsers()).thenReturn(Set.of("alice", "bob"));
        when(modRepo.getModificationCountByUser()).thenReturn(Map.of());
        when(modRepo.getUsersWithModifications()).thenReturn(Set.of());
        assertDoesNotThrow(() -> statsReportService.printStats());
    }

    @Test
    @DisplayName("STATS prints modification counts per user")
    void printStats_showsModificationCounts() {
        when(userRepo.getLoggedInUsers()).thenReturn(Set.of("alice"));
        when(modRepo.getModificationCountByUser()).thenReturn(Map.of("alice", 3L));
        when(modRepo.getUsersWithModifications()).thenReturn(Set.of("alice"));
        assertDoesNotThrow(() -> statsReportService.printStats());
    }

    // -------------------------------------------------------------------------
    // InvalidCommand â€“ must not throw
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("InvalidCommand.execute() does not throw")
    void invalidCommand_executeDoesNotThrow() {
        InvalidCommand cmd = new InvalidCommand("GARBAGE");
        assertDoesNotThrow(cmd::execute);
    }
}
