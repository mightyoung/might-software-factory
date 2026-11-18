package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.definition.EffectiveEntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition;
import com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinitionCatalog;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.reference.ReferenceQueryContract;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
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
import org.springframework.stereotype.Service;

/** Authorization is resolved anew before constructing any response or semantic cache validator. */
@Service
public final class FindingDefinitionService {
    private final SessionContextResolver sessions;
    private final FindingDefinitionProvider provider;
    private final FindingDefinitionReadRepository reads;

    public FindingDefinitionService(SessionContextResolver sessions, FindingDefinitionProvider provider,
            FindingDefinitionReadRepository reads) {
        this.sessions = Objects.requireNonNull(sessions); this.provider = Objects.requireNonNull(provider);
        this.reads = Objects.requireNonNull(reads);
    }

    public DefinitionResult definition(EntityDefinition.Use use) {
        Objects.requireNonNull(use);
        var context = sessions.currentTenant();
        return reads.inSnapshot(context, snapshot -> {
            var authority = snapshot.authorize(use);
            var effective = project(provider.publication(), use);
            var versions = Map.of("tenantEpoch", Long.toString(authority.context().tenantAuthEpoch()),
                    "memberEpoch", Long.toString(authority.context().memberAuthEpoch()),
                    "credentialVersion", Long.toString(authority.context().credentialVersion()),
                    "applicationVersion", Long.toString(authority.applicationVersion()),
                    "policyVersion", "finding-definition-v1", "organizationScope", scopeChecksum(authority.organizationIds()),
                    "actor", authority.context().actorId().toString());
            var current = new EntityDefinitionCatalog.Context(authority.context().accountId(), authority.context().memberId(),
                    authority.context().tenantId(), versions);
            String checksum = EntityDefinitionCatalog.semanticChecksum(effective, current);
            snapshot.requireActive();
            return new DefinitionResult(effective, "W/\"" + checksum + "\"");
        });
    }

    public ReferenceQueryContract.Page organizations(ReferenceQueryContract.Request request) {
        Objects.requireNonNull(request);
        return reads.inSnapshot(sessions.currentTenant(), snapshot -> {
            var authority = snapshot.authorize(EntityDefinition.Use.CREATE);
            return snapshot.organizations(authority, request);
        });
    }

    static EffectiveEntityDefinition project(EntityDefinitionCatalog.Publication publication, EntityDefinition.Use use) {
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
        for (String key : definition.actions().get("finding.create").inputFields()) {
            var original = definition.fields().get(key);
            var edit = original.edit();
            Map<String, String> valueLabels = new LinkedHashMap<>();
            edit.allowedValues().forEach(value -> valueLabels.put(value, original.valueLabels().get(value)));
            var presentation = new EntityDefinition.Field(original.label(), original.uiKind(), original.history(),
                    original.referenceKey(), valueLabels, edit);
            var query = new FieldSchema(definition.querySchema().fields().get(key).type(), !edit.required(), false, false,
                    Set.of(), edit.maxLength(), new LinkedHashSet<>(edit.allowedValues()));
            fields.put(key, new EffectiveEntityDefinition.Field(query, presentation));
        }
        return new EffectiveEntityDefinition(definition.appKey(), definition.entityKey(), definition.definitionVersion(),
                definition.querySchema().schemaVersion(), use, definition.title(), publication.checksum(), fields,
                new EntityDefinition.Layout(List.of(), List.of(), definition.layout().create(), List.of()),
                definition.actions(), definition.references());
    }

    private static String scopeChecksum(Set<java.util.UUID> organizations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            organizations.stream().map(java.util.UUID::toString).sorted()
                    .forEach(id -> digest.update((id + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new FindingDefinitionUnavailableException(); }
    }

    public record DefinitionResult(EffectiveEntityDefinition data, String etag) { }
}
