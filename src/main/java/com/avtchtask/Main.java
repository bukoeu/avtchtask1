package com.avtchtask;

import com.avtchtask.command.Command;
import com.avtchtask.command.CommandParser;
import com.avtchtask.command.CommandProcessor;
import com.avtchtask.command.ExitCommand;
import com.avtchtask.db.DatabaseManager;
import com.avtchtask.repository.ModificationRepository;
import com.avtchtask.repository.SQLiteModificationRepository;
import com.avtchtask.repository.SQLiteUserRepository;
import com.avtchtask.repository.UserRepository;
import com.avtchtask.service.ModificationService;
import com.avtchtask.service.StatsReportService;
import com.avtchtask.service.UserSessionService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

public class Main {

    public static void main(String[] args) throws Exception {
        Properties config = new Properties();
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (in != null) config.load(in);
        }
        int threads   = Integer.parseInt(config.getProperty("command.processor.threads", "4"));
        int batchSize = Integer.parseInt(config.getProperty("db.write.batch.size",     "100"));

        DatabaseManager db = new DatabaseManager("avtchtask.db", batchSize);
        db.initSchema();

        UserRepository userRepo = new SQLiteUserRepository(db);
        ModificationRepository modRepo = new SQLiteModificationRepository(db);

        UserSessionService sessionService = new UserSessionService(userRepo);
        ModificationService modService = new ModificationService(modRepo);
        StatsReportService statsReportService = new StatsReportService(userRepo, modRepo);
        CommandProcessor processor = new CommandProcessor(threads);
        CommandParser parser = new CommandParser(sessionService, modService, statsReportService, processor);

        System.out.println("Ready. Enter commands (LOGIN, LOGOUT, DATA_MODIFY, STATS, EXIT):");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                Command cmd = parser.parse(trimmed);
                processor.submit(cmd);
                if (cmd instanceof ExitCommand) break;
            }
        }

        processor.gracefulShutdown();
        db.close();
    }
}