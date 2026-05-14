package com.avtchtask.command;

import com.avtchtask.service.ModificationService;
import com.avtchtask.service.StatsReportService;
import com.avtchtask.service.UserSessionService;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses raw input strings into Command instances; contains no business logic. */
public class CommandParser {
    private static final Pattern LOGIN_PATTERN       = Pattern.compile("^LOGIN\\(([^)]+)\\)$");
    private static final Pattern LOGOUT_PATTERN      = Pattern.compile("^LOGOUT\\(([^)]+)\\)$");
    private static final Pattern DATA_MODIFY_PATTERN = Pattern.compile("^DATA_MODIFY\\(([^)]+)\\)$");
    private static final Pattern STATS_PATTERN       = Pattern.compile("^STATS\\(\\)$");
    private static final Pattern EXIT_PATTERN        = Pattern.compile("^EXIT\\(\\)$");

    private final UserSessionService sessionService;
    private final ModificationService modService;
    private final StatsReportService  statsReportService;
    private final CommandProcessor processor;

    public CommandParser(UserSessionService sessionService, ModificationService modService,
                         StatsReportService statsReportService, CommandProcessor processor) {
        this.sessionService    = sessionService;
        this.modService        = modService;
        this.statsReportService = statsReportService;
        this.processor         = processor;
    }

    public Command parse(String input) {
        if (input == null || input.isEmpty()) {
            return new InvalidCommand(input);
        }

        Matcher m;

        m = LOGIN_PATTERN.matcher(input);
        if (m.matches()) return new LoginCommand(m.group(1), sessionService);

        m = LOGOUT_PATTERN.matcher(input);
        if (m.matches()) return new LogoutCommand(m.group(1), sessionService);

        m = DATA_MODIFY_PATTERN.matcher(input);
        if (m.matches()) return new DataModifyCommand(m.group(1), sessionService, modService);

        m = STATS_PATTERN.matcher(input);
        if (m.matches()) return new StatsCommand(statsReportService);

        m = EXIT_PATTERN.matcher(input);
        if (m.matches()) return new ExitCommand(processor);

        return new InvalidCommand(input);
    }
}

