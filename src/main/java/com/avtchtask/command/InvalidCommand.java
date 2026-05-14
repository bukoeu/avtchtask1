package com.avtchtask.command;

public class InvalidCommand implements Command {
    private final String raw;

    public InvalidCommand(String raw) {
        this.raw = raw;
    }

    @Override
    public void execute() {
        System.out.println("[IGNORED] Invalid or unrecognized command: " + raw);
    }
}
