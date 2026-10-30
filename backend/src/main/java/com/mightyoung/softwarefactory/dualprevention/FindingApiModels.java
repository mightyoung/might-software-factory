package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Metadata;

import java.util.List;
import java.util.Map;

/** Wire response records for the finding read endpoints. */
public final class FindingApiModels {

    private FindingApiModels() {
    }

    public record SchemaResponse(EntitySchema data, Metadata meta) {
    }

    public record ListResponse(ListData data, Metadata meta) {
    }

    public record ListData(List<Map<String, Object>> items, PageMetadata page) {
    }

    public record PageMetadata(int number, int size, long total, long pageCount) {
    }

    public record DetailResponse(Map<String, Object> data, Metadata meta) {
    }
}
