package com.mightyoung.softwarefactory.platformadmin;

import java.util.List;
import java.util.UUID;
import com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.Definition;
import com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.Field;
import com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.RecordView;

public final class RuntimeLedgerReadModels {
    private RuntimeLedgerReadModels() {}
    public record Published(String key, long definitionVersion, Definition definition) {}
    public record Capabilities(boolean read, boolean create, boolean update, boolean delete) {}
    public record Organization(UUID id, String name) {}
    /** Runtime definitions expose ordinary record configuration, not administrator-only metadata. */
    public record PublicDefinition(String name, List<Field> fields, List<String> listFields, List<String> queryFields) {
        static PublicDefinition from(Definition definition) {
            return new PublicDefinition(definition.name(), definition.fields(), definition.listFields(), definition.queryFields());
        }
    }
    public record Item(String key, long definitionVersion, PublicDefinition definition, Capabilities capabilities,
                       List<Organization> organizations) {}
    public record Items(List<Item> items) {}
    public record Filter(String field, String operator, Object value) {}
    public record Sort(String field, String direction) {}
    public record Query(Integer page, Integer size, List<Filter> filters, Sort sort) {}
    public record Page(List<RecordView> records, int page, int size, long total, boolean hasMore) {}
    public record Detail(RecordView record, PublicDefinition definition, Capabilities capabilities) {}
}
