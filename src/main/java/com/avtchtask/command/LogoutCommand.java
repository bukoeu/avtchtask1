package com.avtchtask.command;

import com.avtchtask.service.UserSessionService;

public class LogoutCommand implements Command {
    private final String userId;
    private final UserSessionService service;

    public LogoutCommand(String userId, UserSessionService service) {
        this.userId = userId;
        this.service = service;
    }

    @Override
    public void execute() {
        boolean ok = service.logout(userId);
        System.out.println(ok
            ? "[OK] LOGOUT(" + userId + ")"
            : "[IGNORED] LOGOUT(" + userId + "): not logged in");
    }
}
