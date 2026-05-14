package com.avtchtask.command;

import com.avtchtask.service.ModificationService;
import com.avtchtask.service.UserSessionService;

public class DataModifyCommand implements Command {
    private final String userId;
    private final UserSessionService sessionService;
    private final ModificationService modService;

    public DataModifyCommand(String userId, UserSessionService sessionService,
                             ModificationService modService) {
        this.userId = userId;
        this.sessionService = sessionService;
        this.modService = modService;
    }

    @Override
    public void execute() {
        if (!sessionService.isLoggedIn(userId)) {
            System.out.println("[IGNORED] DATA_MODIFY(" + userId + "): user not logged in");
            return;
        }
        modService.record(userId);
        System.out.println("[OK] DATA_MODIFY(" + userId + ")");
    }
}

