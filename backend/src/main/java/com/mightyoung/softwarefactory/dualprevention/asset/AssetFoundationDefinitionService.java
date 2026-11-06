package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.definition.EffectiveEntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionCatalog;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;

/** Current authority precedes owner projection and semantic validator construction. */
public final class AssetFoundationDefinitionService {
    private final AssetFoundationReadService reads;
    private final AssetFoundationDefinitionProvider provider;
    public AssetFoundationDefinitionService(AssetFoundationReadService reads, AssetFoundationDefinitionProvider provider) {
        this.reads = Objects.requireNonNull(reads); this.provider = Objects.requireNonNull(provider);
    }
    EntitySchema schema(CapturedHumanRequest request, Root root) {
        return reads.inReadSnapshot(request, snapshot -> {
            snapshot.authorize(root.readAction());
            var schema = AssetFoundationEntitySchemas.schema(root);
            snapshot.requireActive();
            return schema;
        });
    }
    DefinitionResult definition(CapturedHumanRequest request, Root root, EntityDefinition.Use use) {
        Objects.requireNonNull(use);
        return reads.inReadSnapshot(request, snapshot -> {
            var scope = snapshot.authorize(use == EntityDefinition.Use.READ ? root.readAction() : root.createAction());
            var effective = project(provider.publication(root), use, root);
            var context = scope.context();
            var versions = Map.of("tenantEpoch", Long.toString(context.tenantAuthEpoch()),
                    "memberEpoch", Long.toString(context.memberAuthEpoch()),
                    "credentialVersion", Long.toString(context.credentialVersion()),
                    "applicationVersion", Long.toString(snapshot.applicationVersion(scope)),
                    "policyVersion", "asset-definition-v1", "organizationScope", scopeChecksum(scope.organizationIds()),
                    "actor", context.actorId().toString());
            var cacheContext = new EntityDefinitionCatalog.Context(context.accountId(), context.memberId(), context.tenantId(), versions);
            String checksum = EntityDefinitionCatalog.semanticChecksum(effective, cacheContext);
            snapshot.requireActive();
            return new DefinitionResult(effective, "W/\"" + checksum + "\"");
        });
    }

    static EffectiveEntityDefinition project(EntityDefinitionCatalog.Publication publication, EntityDefinition.Use use, Root root) {
        var definition = publication.definition();
        Map<String, EffectiveEntityDefinition.Field> fields = new LinkedHashMap<>();
        if (use == EntityDefinition.Use.READ) {
            definition.querySchema().fields().forEach((key, query) -> {
                var original = definition.fields().get(key);
                var presentation = new EntityDefinition.Field(original.label(),
                        original.uiKind() == EntityDefinition.UiKind.REFERENCE ? EntityDefinition.UiKind.TEXT : original.uiKind(),
                        original.history(), null, original.valueLabels(), null);
                fields.put(key, new EffectiveEntityDefinition.Field(query, presentation));
            });
            return new EffectiveEntityDefinition(definition.appKey(), definition.entityKey(), definition.definitionVersion(),
                    definition.querySchema().schemaVersion(), use, definition.title(), publication.checksum(), fields,
                    new EntityDefinition.Layout(definition.layout().list(), definition.layout().detail(), List.of(), definition.layout().query()),
                    Map.of(), Map.of());
        }
        var action = definition.actions().get(root.createAction().permissionCode());
        for (String key : action.inputFields()) {
            var original = definition.fields().get(key);
            var edit = original.edit();
            Map<String, String> labels = new LinkedHashMap<>();
            edit.allowedValues().forEach(value -> labels.put(value, original.valueLabels().get(value)));
            var presentation = new EntityDefinition.Field(original.label(), original.uiKind(), original.history(),
                    original.referenceKey(), labels, edit);
            var query = new FieldSchema(definition.querySchema().fields().get(key).type(), !edit.required(), false, false,
                    Set.of(), edit.maxLength(), new LinkedHashSet<>(edit.allowedValues()));
            fields.put(key, new EffectiveEntityDefinition.Field(query, presentation));
        }
        return new EffectiveEntityDefinition(definition.appKey(), definition.entityKey(), definition.definitionVersion(),
                definition.querySchema().schemaVersion(), use, definition.title(), publication.checksum(), fields,
                new EntityDefinition.Layout(List.of(), List.of(), definition.layout().create(), List.of()),
                Map.of("create", action), definition.references());
    }
    private static String scopeChecksum(Set<UUID> scope) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            scope.stream().map(UUID::toString).sorted().forEach(id -> digest.update((id + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new AssetFoundationFailure(503, unavailable); }
    }
    record DefinitionResult(EffectiveEntityDefinition data, String etag) { }
}
