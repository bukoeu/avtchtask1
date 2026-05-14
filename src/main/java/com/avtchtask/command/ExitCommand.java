package com.avtchtask.command;

/** Signals graceful shutdown via CommandProcessor; does NOT block the executor thread. */
public class ExitCommand implements Command {
    private final CommandProcessor processor;

    public ExitCommand(CommandProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void execute() {
        System.out.println("[EXIT] Initiating graceful shutdown...");
        processor.signalExit();
    }
}
