package com.avtchtask.repository;

import java.util.Map;
import java.util.Set;

/** Repository for data modification records. */
public interface ModificationRepository {
    void addModification(String userId, String timestamp);
    Map<String, Long> getModificationCountByUser();
    Set<String> getUsersWithModifications();
}
