package com.avtchtask.service;

import com.avtchtask.repository.ModificationRepository;
import com.avtchtask.repository.UserRepository;

import java.util.Map;
import java.util.Set;

/** Single responsibility: reads and prints session/modification statistics. */
public class StatsReportService {
    private final UserRepository userRepo;
    private final ModificationRepository modRepo;

    public StatsReportService(UserRepository userRepo, ModificationRepository modRepo) {
        this.userRepo = userRepo;
        this.modRepo = modRepo;
    }

    public void printStats() {
        Set<String> loggedInUsers = userRepo.getLoggedInUsers();
        Map<String, Long> modCounts = modRepo.getModificationCountByUser();
        Set<String> usersWithMods = modRepo.getUsersWithModifications();

        System.out.println("=== STATS ===");
        System.out.println("Logged-in users       : " + loggedInUsers.size());
        System.out.println("Users modifying data  : " + usersWithMods.size());
        System.out.println("Modifications per user:");
        if (modCounts.isEmpty()) {
            System.out.println("  (none)");
        } else {
            modCounts.forEach((uid, count) ->
                    System.out.println("  " + uid + " -> " + count));
        }
        System.out.println("=============");
    }
}
