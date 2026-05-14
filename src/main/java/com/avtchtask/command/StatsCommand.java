package com.avtchtask.command;

import com.avtchtask.service.StatsReportService;

public class StatsCommand implements Command {
    private final StatsReportService statsReportService;

    public StatsCommand(StatsReportService statsReportService) {
        this.statsReportService = statsReportService;
    }

    @Override
    public void execute() {
        statsReportService.printStats();
    }
}

