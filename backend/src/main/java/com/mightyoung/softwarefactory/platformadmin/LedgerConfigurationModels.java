package com.mightyoung.softwarefactory.platformadmin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LedgerConfigurationModels {
    private LedgerConfigurationModels() {}
    public record Field(String key, String label, String type, boolean required) {}
    public record Definition(String name, List<Field> fields, List<String> listFields, List<String> queryFields) {}
    public record SaveRequest(UUID requestId, Long expectedVersion, Definition definition) {}
    public record RestoreRequest(UUID requestId, Long expectedVersion, Long sourceVersion) {}
    public record PublishRequest(Long version, Long expectedVersion, Long expectedPublishedVersion) {}
    public record Snapshot(String key, long version, Long publishedVersion, Definition definition,
                           Instant savedAt, Long sourceVersion, UUID savedBy) {}
    public record Item(String key, long latestVersion, Long publishedVersion, Definition definition,
                       Instant savedAt, Long sourceVersion, UUID savedBy) {}
    public record Items(List<Item> items) {}
    public record Versions(List<Snapshot> versions) {}
}
