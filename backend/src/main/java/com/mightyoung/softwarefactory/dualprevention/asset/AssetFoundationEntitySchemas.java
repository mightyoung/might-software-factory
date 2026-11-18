package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.util.*;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.*;

/** One immutable publication of the six finite asset query schemas. */
public final class AssetFoundationEntitySchemas {
    private AssetFoundationEntitySchemas() {}
    public enum Root {
        WORKSHOP("workshop"), PRODUCTION_LINE("production_line"), PROCESS_STAGE("process_stage"),
        EQUIPMENT("equipment"), WORK_UNIT("work_unit"), HAZARD_SOURCE("hazard_source");
        private final String key;
        Root(String key) { this.key=key; }
        public String key() { return key; }
        public RegisteredEntityAction readAction() { return RegisteredEntityAction.valueOf(name()+"_READ"); }
        public RegisteredEntityAction createAction() { return RegisteredEntityAction.valueOf(name()+"_CREATE"); }
    }
    private static final Map<Root,EntitySchema> SCHEMAS=build();
    public static EntitySchema schema(Root root) { return SCHEMAS.get(Objects.requireNonNull(root)); }
    public static List<EntitySchema> schemas() { return Arrays.stream(Root.values()).map(SCHEMAS::get).toList(); }

    private static Map<Root,EntitySchema> build() {
        var result=new EnumMap<Root,EntitySchema>(Root.class);
        for(var root:Root.values()) {
            String keys=switch(root) {
                case WORKSHOP -> "id version code name organizationId boundaryDescription createdAt updatedAt lifecycle";
                case PRODUCTION_LINE -> "id version code name organizationId workshopId description createdAt updatedAt lifecycle";
                case PROCESS_STAGE -> "id version code name organizationId lineId ordinal description createdAt updatedAt lifecycle";
                case EQUIPMENT -> "id version code name organizationId typeDescription operationalState locationDescription createdAt updatedAt lifecycle";
                case WORK_UNIT -> "id version code name organizationId kind boundaryDescription createdAt updatedAt lifecycle workshopId lineId stageId equipmentId";
                case HAZARD_SOURCE -> "id version code organizationId workUnitId description identifiedAt existenceState createdAt updatedAt factorCodeRef factorVersion";
            };
            var fields=new LinkedHashMap<String,FieldSchema>();
            for(String key:keys.split(" ")) fields.put(key,field(root,key));
            result.put(root,new EntitySchema(root.key(),"1","id",List.copyOf(fields.keySet()),Collections.unmodifiableMap(fields)));
        }
        return Collections.unmodifiableMap(result);
    }
    private static Set<String> values(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }
    private static FieldSchema field(Root root,String key) {
        boolean nullable=switch(key) {
            case "boundaryDescription","locationDescription","factorCodeRef","factorVersion" -> true;
            case "description" -> root!=Root.HAZARD_SOURCE;
            case "workshopId","lineId","stageId","equipmentId" -> root==Root.WORK_UNIT;
            default -> false;
        };
        FieldType type;
        int max=0;
        Set<String> values=Set.of();
        if(key.equals("id")||key.endsWith("Id")) type=FieldType.UUID;
        else if(key.equals("version")||key.equals("ordinal")) type=FieldType.NUMBER;
        else if(key.endsWith("At")) type=FieldType.DATETIME;
        else if(Set.of("lifecycle","kind","operationalState","existenceState").contains(key)) {
            type=FieldType.ENUM;
            values=switch(key) {
                case "lifecycle" -> values("ACTIVE","ARCHIVED");
                case "kind" -> values("STANDALONE","WORKSHOP","LINE","STAGE","EQUIPMENT");
                case "operationalState" -> values("UNKNOWN","IN_SERVICE","OUT_OF_SERVICE");
                default -> values("UNKNOWN","PRESENT","NOT_PRESENT");
            };
        } else {
            type=FieldType.TEXT;
            max=switch(key) {
                case "code","factorCodeRef","factorVersion" -> 64;
                case "name","typeDescription" -> 200;
                case "description" -> root==Root.HAZARD_SOURCE?20000:2000;
                default -> 2000;
            };
        }
        Set<FilterOperator> ops=new LinkedHashSet<>(switch(type) {
            case UUID, ENUM -> List.of(EQ,IN);
            case NUMBER,DATETIME -> List.of(EQ,GT,GTE,LT,LTE,BETWEEN);
            case TEXT -> List.of(EQ,CONTAINS,IS_EMPTY,IS_NOT_EMPTY);
            default -> throw new IllegalStateException("Unregistered asset field type");
        });
        if(nullable) { ops.add(IS_NULL);ops.add(IS_NOT_NULL); }
        return new FieldSchema(type,nullable,true,true,Collections.unmodifiableSet(ops),max,values);
    }
}
