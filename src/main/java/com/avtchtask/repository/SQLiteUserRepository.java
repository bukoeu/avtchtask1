package com.avtchtask.repository;

import com.avtchtask.db.DatabaseManager;

import java.util.Set;

public class SQLiteUserRepository implements UserRepository {
    private final DatabaseManager db;

    public SQLiteUserRepository(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public void login(String userId) {
        String sql = "INSERT INTO user_sessions(user_id, logged_in) VALUES(?, 1) " +
                     "ON CONFLICT(user_id) DO UPDATE SET logged_in = 1";
        db.execute(sql, userId);
    }

    @Override
    public void logout(String userId) {
        db.execute("UPDATE user_sessions SET logged_in = 0 WHERE user_id = ?", userId);
    }

    @Override
    public boolean isLoggedIn(String userId) {
        return db.queryBoolean(
                "SELECT logged_in FROM user_sessions WHERE user_id = ?", userId);
    }

    @Override
    public Set<String> getLoggedInUsers() {
        return db.queryStringSet(
                "SELECT user_id FROM user_sessions WHERE logged_in = 1");
    }
}
