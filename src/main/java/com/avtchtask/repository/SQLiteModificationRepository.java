package com.avtchtask.repository;

import com.avtchtask.db.DatabaseManager;

import java.util.Map;
import java.util.Set;

public class SQLiteModificationRepository implements ModificationRepository {
    private final DatabaseManager db;

    public SQLiteModificationRepository(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public void addModification(String userId, String timestamp) {
        db.execute(
                "INSERT INTO data_modifications(user_id, created_at) VALUES(?, ?)",
                userId, timestamp);
    }

    @Override
    public Map<String, Long> getModificationCountByUser() {
        return db.queryCountMap(
                "SELECT user_id, COUNT(*) FROM data_modifications GROUP BY user_id");
    }

    @Override
    public Set<String> getUsersWithModifications() {
        return db.queryStringSet(
                "SELECT DISTINCT user_id FROM data_modifications");
    }
}
