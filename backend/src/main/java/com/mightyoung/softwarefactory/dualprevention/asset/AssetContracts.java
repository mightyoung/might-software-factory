package com.mightyoung.softwarefactory.dualprevention.asset;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

/** Fixed foundation commands. Identity and authority never arrive in these DTOs. */
public final class AssetContracts {
    private AssetContracts() {}
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public enum Lifecycle { ACTIVE, ARCHIVED }
    public enum WorkUnitKind { STANDALONE, WORKSHOP, LINE, STAGE, EQUIPMENT }
    public enum OperationalState { UNKNOWN, IN_SERVICE, OUT_OF_SERVICE }
    public record WorkshopCreate(String code, String name, UUID organizationId, String boundaryDescription) {
        public WorkshopCreate { code=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.code(code); name=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.name(name); Objects.requireNonNull(organizationId); text(boundaryDescription,2000,true); }
    }
    public record WorkshopUpdate(long expectedVersion, String name, String boundaryDescription) {
        public WorkshopUpdate { version(expectedVersion); name=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.name(name); text(boundaryDescription,2000,true); }
    }
    public record ProductionLineCreate(String code,String name,UUID organizationId,UUID workshopId,String description) {
        public ProductionLineCreate { code=AssetContracts.code(code);name=AssetContracts.name(name);Objects.requireNonNull(organizationId);Objects.requireNonNull(workshopId);text(description,2000,true); }
    }
    public record ProductionLineUpdate(long expectedVersion,String name,String description) {
        public ProductionLineUpdate { version(expectedVersion);name=AssetContracts.name(name);text(description,2000,true); }
    }
    public record ProcessStageCreate(String code,String name,UUID organizationId,UUID lineId,int ordinal,String description) {
        public ProcessStageCreate { code=AssetContracts.code(code);name=AssetContracts.name(name);Objects.requireNonNull(organizationId);Objects.requireNonNull(lineId);AssetContracts.ordinal(ordinal);text(description,2000,true); }
    }
    public record ProcessStageUpdate(long expectedVersion,String name,int ordinal,String description) {
        public ProcessStageUpdate { version(expectedVersion);name=AssetContracts.name(name);AssetContracts.ordinal(ordinal);text(description,2000,true); }
    }
    public record EquipmentCreate(String code,String name,UUID organizationId,String typeDescription,String locationDescription,OperationalState operationalState) {
        public EquipmentCreate { code=AssetContracts.code(code);name=AssetContracts.name(name);Objects.requireNonNull(organizationId);typeDescription=AssetContracts.name(typeDescription);text(locationDescription,2000,true);Objects.requireNonNull(operationalState); }
    }
    public record EquipmentUpdate(long expectedVersion,String name,String typeDescription,String locationDescription,OperationalState operationalState) {
        public EquipmentUpdate { version(expectedVersion);name=AssetContracts.name(name);typeDescription=AssetContracts.name(typeDescription);text(locationDescription,2000,true);Objects.requireNonNull(operationalState); }
    }
    public record WorkUnitCreate(String code, String name, UUID organizationId, WorkUnitKind kind,
                                 UUID workshopId, UUID lineId, UUID stageId, UUID equipmentId, String boundaryDescription) {
        public WorkUnitCreate {
            code=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.code(code); name=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.name(name); Objects.requireNonNull(organizationId); Objects.requireNonNull(kind); text(boundaryDescription,2000,true);
            int supplied=(workshopId==null?0:1)+(lineId==null?0:1)+(stageId==null?0:1)+(equipmentId==null?0:1);
            boolean correct=switch(kind) { case STANDALONE -> supplied==0; case WORKSHOP -> supplied==1&&workshopId!=null;
                case LINE -> supplied==1&&lineId!=null; case STAGE -> supplied==1&&stageId!=null; case EQUIPMENT -> supplied==1&&equipmentId!=null; };
            if(!correct) throw invalid();
        }
    }
    public record WorkUnitUpdate(long expectedVersion, String name, String boundaryDescription) {
        public WorkUnitUpdate { version(expectedVersion); name=com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.name(name); text(boundaryDescription,2000,true); }
    }
    public enum Duty { OPERATIONS,MAINTENANCE,SAFETY_COORDINATION }
    public record LineWorkshopBind(long expectedLineVersion,UUID workshopId,Instant validFrom,Instant validTo) {
        public LineWorkshopBind { version(expectedLineVersion);Objects.requireNonNull(workshopId);period(validFrom,validTo); }
    }
    public record StageEquipmentBind(long expectedStageVersion,UUID equipmentId,Instant validFrom,Instant validTo) {
        public StageEquipmentBind { version(expectedStageVersion);Objects.requireNonNull(equipmentId);period(validFrom,validTo); }
    }
    public record ResponsibilityBind(long expectedWorkUnitVersion,UUID orgId,UUID positionId,Duty duty,Instant validFrom,Instant validTo) {
        public ResponsibilityBind { version(expectedWorkUnitVersion);Objects.requireNonNull(orgId);Objects.requireNonNull(duty);period(validFrom,validTo); }
    }
    public record EndBinding(long expectedParentVersion,long expectedBindingVersion,Instant endAt) {
        public EndBinding { version(expectedParentVersion);version(expectedBindingVersion);if(endAt!=null)instant(endAt); }
    }
    private static void period(Instant from,Instant to) {
        instant(from);if(to!=null&& !instant(to).isAfter(from))throw invalid();
    }
    public record Archive(long expectedVersion) { public Archive { version(expectedVersion); } }
    public record CommandResult(UUID commandId, String resourceType, UUID id, long version, Instant occurredAt) {
        public CommandResult { Objects.requireNonNull(commandId); Objects.requireNonNull(resourceType); Objects.requireNonNull(id); com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.version(version); instant(occurredAt); }
    }
    public record Workshop(UUID id, UUID organizationId, long version, String code, String name,
                           String boundaryDescription, Lifecycle lifecycle,Instant createdAt,Instant updatedAt) {}
    public record WorkUnit(UUID id, UUID organizationId, long version, String code, String name,
                           WorkUnitKind kind,@JsonInclude(JsonInclude.Include.NON_NULL) UUID workshopId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID lineId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID stageId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID equipmentId,
                           String boundaryDescription, Lifecycle lifecycle,Instant createdAt,Instant updatedAt) {}
    public record ProductionLine(UUID id,UUID organizationId,long version,String code,String name,UUID workshopId,
                                 String description,Lifecycle lifecycle,Instant createdAt,Instant updatedAt) {}
    public record ProcessStage(UUID id,UUID organizationId,long version,String code,String name,UUID lineId,int ordinal,
                               String description,Lifecycle lifecycle,Instant createdAt,Instant updatedAt) {}
    public record Equipment(UUID id,UUID organizationId,long version,String code,String name,String typeDescription,
                            String locationDescription,OperationalState operationalState,Lifecycle lifecycle,Instant createdAt,Instant updatedAt) {}
    public static int ordinal(int value) { if(value<0||value>1_000_000)throw invalid();return value; }
    public static long version(long value) { if(value<1||value>MAX_VERSION) throw invalid(); return value; }
    public static long nextVersion(long value) { version(value); if(value==MAX_VERSION)throw new AssetFoundationFailure(409); return value+1; }
    public static String code(String value) { if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw invalid(); return value; }
    public static String name(String value) {
        text(value,200,false);
        if(white(value.codePointAt(0))||white(value.codePointBefore(value.length()))||value.codePoints().anyMatch(cp->cp<=31||(cp>=127&&cp<=159)))throw invalid();
        return value;
    }
    public static String text(String value,int maximum,boolean optional) {
        if(value==null) { if(optional)return null; throw invalid(); }
        if(value.isEmpty()||value.codePointCount(0,value.length())>maximum)throw invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) { if(++i==value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid(); }
            else if(Character.isLowSurrogate(c))throw invalid();
            else if(c==0||(c<32&&c!='\t'&&c!='\n'&&c!='\r'))throw invalid();
        }
        return value;
    }
    public static Instant instant(Instant value) {
        if(value==null||value.getNano()%1000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||!value.isBefore(Instant.parse("+10000-01-01T00:00:00Z")))throw invalid();
        return value;
    }
    /** A server-owned database cut is accepted exactly; never round it into a different authority instant. */
    static Instant stageInstant(Instant value) {
        try { return instant(value); }
        catch(AssetFoundationFailure invalidCut) { throw new AssetFoundationFailure(503); }
    }
    /** PostgreSQL UUID / uuid_send byte ordering, including UUIDs with the high bit set. */
    static int compareUuid(UUID left,UUID right) {
        int high=Long.compareUnsigned(left.getMostSignificantBits(),right.getMostSignificantBits());
        return high!=0?high:Long.compareUnsigned(left.getLeastSignificantBits(),right.getLeastSignificantBits());
    }
    private static boolean white(int cp) { return cp>=9&&cp<=13||cp==32||cp==133||cp==160||cp==5760||cp>=8192&&cp<=8202||cp==8232||cp==8233||cp==8239||cp==8287||cp==12288; }
    static AssetFoundationFailure invalid() { return new AssetFoundationFailure(400); }
}
