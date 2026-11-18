package com.mightyoung.softwarefactory.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Startup validation of code-owned bindings; client values never participate in these identifiers. */
public final class LedgerBindingValidator {
    private static final Set<String> USES = Set.of("CREATE","EDIT");
    private static final Set<String> CORE_COLUMNS = Set.of("tenant_id","id","version","created_at","updated_at","created_by_actor_id","updated_by_actor_id");
    public void validate(LedgerBinding binding) {
        require(binding!=null && binding.definition()!=null && binding.storage()!=null && binding.scopePolicy()!=null,"Incomplete binding");
        var definition=binding.definition(); var storage=binding.storage();
        key(definition.appKey()); key(definition.entityKey());
        require(definition.definitionVersion()!=null && definition.definitionVersion().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"),"Invalid definition version");
        require(definition.title()!=null && !definition.title().isBlank(),"Missing title");
        require(!definition.fields().isEmpty() && definition.fields().size()<=64 && definition.inputFields().size()<=64,"Field count limit");
        require(!definition.fields().containsKey("id") && !storage.fieldColumns().containsKey("id"),"ID is reserved record metadata");
        require(storage.table()!=null && storage.table().matches("[a-z][a-z0-9_]{0,62}"),"Invalid registered table");
        for(var column:storage.fieldColumns().entrySet()) {
            fieldKey(column.getKey());
            require(column.getValue()!=null && column.getValue().matches("[a-z][a-z0-9_]{0,62}"),"Invalid registered column");
        }
        require(new HashSet<>(storage.fieldColumns().values()).size()==storage.fieldColumns().size(),"Duplicate column mappings");
        require(storage.fieldColumns().keySet().containsAll(definition.fields().keySet()),"Displayed field missing mapping");
        require(storage.fieldColumns().keySet().containsAll(storage.createDefaults().keySet())
                && storage.fieldColumns().keySet().containsAll(storage.derivedFields())
                && storage.fieldColumns().keySet().containsAll(storage.protectedFields()),"Server field missing mapping");
        for(String name:storage.createDefaults().keySet()) require(!LedgerInputValidator.CORE_PROTECTED.contains(name)
                && !CORE_COLUMNS.contains(storage.fieldColumns().get(name)),"Core default forbidden");
        for(String name:storage.derivedFields()) require(!LedgerInputValidator.CORE_PROTECTED.contains(name)
                && !CORE_COLUMNS.contains(storage.fieldColumns().get(name)),"Core derivation forbidden");
        for(var entry:definition.fields().entrySet()) {
            fieldKey(entry.getKey()); require(entry.getValue()!=null && entry.getValue().type()!=null
                    && entry.getValue().label()!=null && !entry.getValue().label().isBlank(),"Invalid display field");
        }
        for(var entry:definition.inputFields().entrySet()) {
            String name=entry.getKey(); fieldKey(name); var field=entry.getValue();
            require(field!=null && field.type()!=null && field.label()!=null && !field.label().isBlank(),"Invalid input field");
            require(field.maxLength()>=0 && field.maxLength()<=4000,"Input length limit");
            require(!field.uses().isEmpty() && field.uses().stream().allMatch(use->USES.contains(use.name())),"Input use is not writable");
            require(storage.fieldColumns().containsKey(name),"Input missing mapping");
            require(!LedgerInputValidator.CORE_PROTECTED.contains(name) && !storage.createDefaults().containsKey(name)
                    && !storage.derivedFields().contains(name) && !storage.protectedFields().contains(name)
                    && !CORE_COLUMNS.contains(storage.fieldColumns().get(name)),"Protected field accepted as input");
            require(field.type()!=LedgerDefinition.Type.ENUM || !field.allowedValues().isEmpty(),"Enum has no allowed values");
            require(field.type()!=LedgerDefinition.Type.REFERENCE || field.referenceKey()!=null
                    && definition.references().containsKey(field.referenceKey()),"Reference provider missing");
        }
        require(definition.querySchema()!=null && definition.querySchema().key().equals(definition.entityKey()),"Query schema identity mismatch");
        require("id".equals(definition.querySchema().idField()),"Query ID must be reserved root.id");
        var id=definition.querySchema().fields().get("id");
        require(id!=null && id.type()==com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.UUID
                && !id.nullable() && id.selectable() && id.sortable(),"Invalid reserved query ID");
        for(var field:definition.querySchema().fields().values()) require(!field.operators().contains(
                com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IN_SUBTREE),"Subtree queries are not supported by the standard ledger");
        var queryFields=new HashSet<>(definition.querySchema().fields().keySet()); queryFields.remove("id");
        require(definition.fields().keySet().containsAll(queryFields),"Query field not registered for display");
        for(String name:queryFields) {
            var type=definition.fields().get(name).type();
            var expected=switch(type) {
                case TEXT,MULTILINE_TEXT -> com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.TEXT;
                case UUID,REFERENCE -> com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.UUID;
                case INTEGER -> com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.NUMBER;
                case DATE_TIME -> com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.DATETIME;
                case ENUM -> com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType.ENUM;
            };
            require(definition.querySchema().fields().get(name).type()==expected,"Query/display type mismatch");
        }
        require(definition.querySchema().fields().keySet().containsAll(definition.querySchema().defaultFields()),"Default query field unknown");
        new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator().validate(
                new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Request(null,null,
                        new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.PageRequest(1,null),20,null),definition.querySchema());
        require(definition.layout()!=null && definition.actions()!=null,"Missing layout/actions");
        var layout=definition.layout();
        requireFields(layout.list(),definition.fields().keySet()); requireFields(layout.detail(),definition.fields().keySet());
        requireFields(layout.query(),definition.querySchema().fields().keySet());
        requireInputs(layout.create(),definition.inputFields(),LedgerDefinition.Use.CREATE);
        requireInputs(layout.edit(),definition.inputFields(),LedgerDefinition.Use.EDIT);
        var actions=definition.actions();
        action(actions.read(),definition.entityKey()+".read"); action(actions.create(),definition.entityKey()+".create"); action(actions.update(),definition.entityKey()+".update");
        require(binding.referenceProviders().keySet().equals(definition.references().keySet()),"Reference provider registry mismatch");
        for(var registration:binding.referenceProviders().values()) {
            require(!registration.allowedUses().isEmpty(),"Reference has no allowed use");
            for(String name:registration.contextFields()) {
                require(definition.inputFields().containsKey(name) && !LedgerInputValidator.CORE_PROTECTED.contains(name)
                        && !storage.protectedFields().contains(name) && !storage.derivedFields().contains(name)
                        && !storage.createDefaults().containsKey(name),"Reference context accepts server field");
                for(var use:registration.allowedUses()) require(definition.inputFields().get(name).uses().contains(use),"Reference context/use mismatch");
            }
        }
    }
    private static void requireInputs(List<String> keys,Map<String,LedgerDefinition.InputField> fields,LedgerDefinition.Use use) {
        requireFields(keys,fields.keySet());
        for(String key:keys) require(fields.get(key).uses().contains(use),"Layout input not accepted for use");
    }
    private static void requireFields(List<String> keys,Set<String> allowed) {
        require(keys!=null && keys.size()==new HashSet<>(keys).size() && allowed.containsAll(keys),"Unknown/duplicate layout fields");
    }
    private static void action(LedgerDefinition.Action action,String permission) { require(action!=null && permission.equals(action.permission()) && action.label()!=null && !action.label().isBlank(),"Action identity mismatch"); }
    private static void key(String key) { require(key!=null && key.matches("[a-z][a-z0-9_]{0,63}"),"Invalid registration identity"); }
    private static void fieldKey(String key) { require(key!=null && key.matches("[a-z][a-zA-Z0-9_]{0,63}"),"Invalid field key"); }
    private static void require(boolean accepted,String message) { if(!accepted) throw new IllegalArgumentException(message); }
}
