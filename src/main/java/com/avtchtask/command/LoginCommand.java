package com.avtchtask.command;

import com.avtchtask.service.UserSessionService;

public class LoginCommand implements Command {
    private final String userId;
    private final UserSessionService service;

    public LoginCommand(String userId, UserSessionService service) {
        this.userId = userId;
        this.service = service;
    }

    @Override
    public void execute() {
        boolean ok = service.login(userId);
        System.out.println(ok
            ? "[OK] LOGIN(" + userId + ")"
            : "[IGNORED] LOGIN(" + userId + "): already logged in");
    }
}
