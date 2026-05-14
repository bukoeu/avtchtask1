package com.avtchtask.service;

import com.avtchtask.repository.ModificationRepository;

import java.time.LocalDateTime;

/** Single responsibility: records a data modification for a user. No output. */
public class ModificationService {
    private final ModificationRepository modRepo;

    public ModificationService(ModificationRepository modRepo) {
        this.modRepo = modRepo;
    }

    public void record(String userId) {
        modRepo.addModification(userId, LocalDateTime.now().toString());
    }
}
