package com.mightyoung.softwarefactory.applicationregistry.definition;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.mightyoung.softwarefactory.applicationregistry.definition.EntityDefinition.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityDefinitionCatalogTest {
    private static final CommandBinding CREATE = new CommandBinding(Method.POST, "finding.create", "/api/v1/findings");

    @Test
    void checksumIgnoresMapAndSetInsertionOrderButPreservesPresentationOrder() {
        var first = definition(false);
        var second = definition(true);
        assertThat(publication(first).checksum()).matches("[0-9a-f]{64}")
                .isEqualTo(publication(second).checksum());
        var differentLayout = copy(first, first.fields(), new Layout(List.of("title", "id"), List.of("id", "title"),
                List.of("title"), List.of("title")), first.actions(), first.references());
        assertThat(publication(differentLayout).checksum()).isNotEqualTo(publication(first).checksum());
    }

    @Test
    void publicationCopiesNestedQueryCollectionsAndPresentationInputs() {
        var operators = new LinkedHashSet<>(List.of(FilterOperator.EQ));
        var sourceFields = new LinkedHashMap<String, FieldSchema>();
        sourceFields.put("id", new FieldSchema(FieldType.UUID, false, true, true, operators, 0, Set.of()));
        sourceFields.put("title", new FieldSchema(FieldType.TEXT, false, true, false, Set.of(FilterOperator.CONTAINS), 100, Set.of()));
        var defaults = new ArrayList<>(List.of("id", "title"));
        var original = definition(false);
        var immutable = new EntityDefinition("safety", "finding", "1", "Finding",
                new EntitySchema("finding", "5", "id", defaults, sourceFields), original.fields(), original.layout(), original.actions(), Map.of());
        operators.clear(); defaults.clear(); sourceFields.clear();
        assertThat(immutable.querySchema().fields()).hasSize(2);
        assertThat(immutable.querySchema().fields().get("id").operators()).containsExactly(FilterOperator.EQ);
        assertThat(immutable.querySchema().defaultFields()).containsExactly("id", "title");
        assertThatThrownBy(() -> immutable.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> immutable.querySchema().fields().get("id").operators().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicatePublicationAndUnknownVersion() {
        var owner = provider(definition(false));
        assertThatThrownBy(() -> new EntityDefinitionCatalog(List.of(owner, owner))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityDefinitionCatalog(List.of(owner))
                .require(new EntityDefinitionCatalog.Key("safety", "finding", "2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownLayoutFieldAndDuplicateLayoutPosition() {
        var value = definition(false);
        for (var list : List.of(List.of("unknown"), List.of("id", "id"))) {
            var invalid = copy(value, value.fields(), new Layout(list, List.of(), List.of("title"), List.of()), value.actions(), Map.of());
            assertThatThrownBy(() -> publication(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnknownReferenceAndMissingActualOwnerBinding() {
        var value = definition(false);
        var fields = new LinkedHashMap<>(value.fields());
        fields.put("title", new Field("Title", UiKind.REFERENCE, History.CURRENT, "report-organization", Map.of(),
                new Edit(true, 100, LengthUnit.UTF16_CODE_UNITS, List.of())));
        assertThatThrownBy(() -> publication(copy(value, fields, value.layout(), value.actions(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityDefinitionCatalog(List.of(new EntityDefinitionProvider() {
            public List<EntityDefinition> definitions() { return List.of(value); }
            public Set<CommandBinding> commandBindings() { return Set.of(); }
            public Set<ReferenceBinding> referenceBindings() { return Set.of(); }
        }))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsAcceptedInputHintsOutsideRenderedCreateLayoutButRejectsInvalidEnumSubset() {
        var value = definition(false);
        var unrendered = publication(copy(value, value.fields(), new Layout(List.of("id"), List.of(), List.of(), List.of()),
                value.actions(), Map.of()));
        assertThat(unrendered.definition().actions().get("finding.create").inputFields()).containsExactly("title");
        assertThat(unrendered.definition().layout().create()).isEmpty();
        var fields = new LinkedHashMap<>(value.fields());
        fields.put("title", new Field("Title", UiKind.TEXT, History.SNAPSHOT, null, Map.of(),
                new Edit(true, 100, LengthUnit.UTF16_CODE_UNITS, List.of("INVENTED"))));
        assertThatThrownBy(() -> publication(copy(value, fields, value.layout(), value.actions(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownActionInputOrKnownFieldWithoutAcceptedInputHints() {
        var value = definition(false);
        for (String input : List.of("id", "unknown")) {
            var action = new Action("Create", Position.CREATE, CREATE, false, true, List.of(input));
            assertThatThrownBy(() -> publication(copy(value, value.fields(), value.layout(), Map.of("finding.create", action), Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void effectiveChecksumSeparatesUseIdentityAndAuthorityEvenWithEqualVisibleFields() {
        var source = definition(false);
        var publication = publication(source);
        var context = new EntityDefinitionCatalog.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("global", "1", "tenant", "2"));
        var read = effective(source, publication.checksum(), Use.READ);
        var create = effective(source, publication.checksum(), Use.CREATE);
        var checksum = EntityDefinitionCatalog.semanticChecksum(read, context);
        assertThat(checksum).isNotEqualTo(EntityDefinitionCatalog.semanticChecksum(create, context));
        assertThat(checksum).isNotEqualTo(EntityDefinitionCatalog.semanticChecksum(read,
                new EntityDefinitionCatalog.Context(UUID.randomUUID(), context.memberId(), context.tenantId(), context.authorityVersions())));
        assertThat(checksum).isNotEqualTo(EntityDefinitionCatalog.semanticChecksum(read,
                new EntityDefinitionCatalog.Context(context.accountId(), context.memberId(), context.tenantId(), Map.of("global", "2", "tenant", "2"))));
        assertThat(checksum).isNotEqualTo(EntityDefinitionCatalog.semanticChecksum(read,
                new EntityDefinitionCatalog.Context(context.accountId(), context.memberId(), UUID.randomUUID(), context.authorityVersions())));
    }

    @Test
    void effectiveFieldDefensivelyCopiesQuerySets() {
        var operators = new LinkedHashSet<>(List.of(FilterOperator.EQ));
        var field = new EffectiveEntityDefinition.Field(new FieldSchema(FieldType.UUID, false, true, true, operators, 0, Set.of()),
                definition(false).fields().get("id"));
        operators.clear();
        assertThat(field.query().operators()).containsExactly(FilterOperator.EQ);
    }

    private static EffectiveEntityDefinition effective(EntityDefinition value, String checksum, Use use) {
        Map<String, EffectiveEntityDefinition.Field> fields = new LinkedHashMap<>();
        value.fields().forEach((key, field) -> fields.put(key, new EffectiveEntityDefinition.Field(value.querySchema().fields().get(key), field)));
        return new EffectiveEntityDefinition(value.appKey(), value.entityKey(), value.definitionVersion(), "5", use, value.title(), checksum,
                fields, value.layout(), value.actions(), value.references());
    }

    private static EntityDefinitionCatalog.Publication publication(EntityDefinition definition) {
        return new EntityDefinitionCatalog(List.of(provider(definition)))
                .require(new EntityDefinitionCatalog.Key("safety", "finding", "1"));
    }
    private static EntityDefinitionProvider provider(EntityDefinition definition) {
        return new EntityDefinitionProvider() {
            public List<EntityDefinition> definitions() { return List.of(definition); }
            public Set<CommandBinding> commandBindings() { return Set.of(CREATE); }
            public Set<ReferenceBinding> referenceBindings() { return Set.of(); }
        };
    }
    private static EntityDefinition definition(boolean reverse) {
        var queryFields = new LinkedHashMap<String, FieldSchema>();
        queryFields.put("id", new FieldSchema(FieldType.UUID, false, true, true,
                new LinkedHashSet<>(reverse ? List.of(FilterOperator.IN, FilterOperator.EQ) : List.of(FilterOperator.EQ, FilterOperator.IN)), 0, Set.of()));
        queryFields.put("title", new FieldSchema(FieldType.TEXT, false, true, false, Set.of(FilterOperator.CONTAINS), 100, Set.of()));
        Map<String, Field> fields = new LinkedHashMap<>();
        var id = new Field("ID", UiKind.TEXT, History.SNAPSHOT, null, Map.of(), null);
        var title = new Field("Title", UiKind.TEXT, History.SNAPSHOT, null, Map.of(), new Edit(true, 100, LengthUnit.UTF16_CODE_UNITS, List.of()));
        if (reverse) { fields.put("title", title); fields.put("id", id); }
        else { fields.put("id", id); fields.put("title", title); }
        return new EntityDefinition("safety", "finding", "1", "Finding",
                new EntitySchema("finding", "5", "id", List.of("id", "title"), queryFields), fields,
                new Layout(List.of("id", "title"), List.of("id", "title"), List.of("title"), List.of("title")),
                Map.of("finding.create", new Action("Create", Position.CREATE, CREATE, false, true, List.of("title"))), Map.of());
    }
    private static EntityDefinition copy(EntityDefinition original, Map<String, Field> fields, Layout layout,
                                         Map<String, Action> actions, Map<String, Reference> references) {
        return new EntityDefinition(original.appKey(), original.entityKey(), original.definitionVersion(), original.title(),
                original.querySchema(), fields, layout, actions, references);
    }
}
