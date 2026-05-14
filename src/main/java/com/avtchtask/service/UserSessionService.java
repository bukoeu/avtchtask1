package com.avtchtask.service;

import com.avtchtask.repository.UserRepository;

import java.util.Set;

public class UserSessionService {
    private final UserRepository userRepo;

    public UserSessionService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public boolean login(String userId) {
        if (userRepo.isLoggedIn(userId)) return false; // already logged in
        userRepo.login(userId);
        return true;
    }

    public boolean logout(String userId) {
        if (!userRepo.isLoggedIn(userId)) return false; // not logged in
        userRepo.logout(userId);
        return true;
    }

    public boolean isLoggedIn(String userId) {
        return userRepo.isLoggedIn(userId);
    }
}
