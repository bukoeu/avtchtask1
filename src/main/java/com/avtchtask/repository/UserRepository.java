package com.avtchtask.repository;

import java.util.Set;

/** Repository for user session state. */
public interface UserRepository {
    void login(String userId);
    void logout(String userId);
    boolean isLoggedIn(String userId);
    Set<String> getLoggedInUsers();
}
