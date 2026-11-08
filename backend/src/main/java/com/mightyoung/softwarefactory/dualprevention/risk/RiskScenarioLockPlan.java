package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Immutable precomputed NEW plan data. It is not an authority scope or a service-issued seal. */
final class RiskScenarioLockPlan {
    enum RowKind { HAZARD_SOURCE(60), RISK_SCENARIO(100), RISK_HAZARD(110);
        final int number; RowKind(int number){this.number=number;}
    }
    enum ChangeKind { NEW, END }
    record ActiveLink(UUID tenantId,UUID id,UUID scenarioId,UUID hazardId,long hazardVersionAtLink,long version,
            Instant linkedAt,UUID createdByActorId,UUID createdByMemberId,long lastScenarioVersion,
            UUID lastCommandId,UUID lastActorId,UUID lastMemberId,Instant lastOccurredAt) {
        ActiveLink {
            Objects.requireNonNull(tenantId);Objects.requireNonNull(id);Objects.requireNonNull(scenarioId);Objects.requireNonNull(hazardId);
            RiskScenarioContracts.version(hazardVersionAtLink);if(version!=1)throw invalid();
            instant(linkedAt);Objects.requireNonNull(createdByActorId);Objects.requireNonNull(createdByMemberId);
            RiskScenarioContracts.version(lastScenarioVersion);Objects.requireNonNull(lastCommandId);
            Objects.requireNonNull(lastActorId);Objects.requireNonNull(lastMemberId);instant(lastOccurredAt);
            if(!linkedAt.equals(lastOccurredAt))throw invalid();
        }
    }
    record Change(UUID id,UUID hazardId,long version,ChangeKind kind) {
        Change {Objects.requireNonNull(id);Objects.requireNonNull(hazardId);Objects.requireNonNull(kind);
            if(version!=(kind==ChangeKind.NEW?1:2))throw invalid();}
    }
    record RowLock(RowKind kind,UUID id) implements Comparable<RowLock> {
        RowLock {Objects.requireNonNull(kind);Objects.requireNonNull(id);}
        @Override public int compareTo(RowLock other){int c=Integer.compare(kind.number,other.kind.number);return c!=0?c:compareUuid(id,other.id);}
    }
    record Key(int family,byte[] canonicalBytes) implements Comparable<Key> {
        Key {if(!Set.of(20,30,40).contains(family))throw invalid();canonicalBytes=canonicalBytes.clone();}
        @Override public byte[] canonicalBytes(){return canonicalBytes.clone();}
        @Override public int compareTo(Key other){int c=Integer.compare(family,other.family);return c!=0?c:Arrays.compareUnsigned(canonicalBytes,other.canonicalBytes);}
        @Override public boolean equals(Object other){return other instanceof Key k&&family==k.family&&Arrays.equals(canonicalBytes,k.canonicalBytes);}
        @Override public int hashCode(){return 31*family+Arrays.hashCode(canonicalBytes);}
        @Override public String toString(){return "RiskMutationKey[REDACTED]";}
    }
    final UUID tenantId,scenarioId,commandId;
    final boolean create;
    final long expectedVersion,newVersion;
    final List<ActiveLink> oldLinks;
    final List<UUID> desiredHazards,unionHazards;
    final List<Change> changes;
    final List<RowLock> rows;
    final List<Key> mutationKeys;

    RiskScenarioLockPlan(UUID tenantId,UUID scenarioId,UUID commandId,boolean create,long expectedVersion,
            List<ActiveLink> oldLinks,List<UUID> desiredHazards,Map<UUID,UUID> newLinkIds){
        this.tenantId=Objects.requireNonNull(tenantId);this.scenarioId=Objects.requireNonNull(scenarioId);
        this.commandId=Objects.requireNonNull(commandId);this.create=create;
        if(create?(expectedVersion!=0||!oldLinks.isEmpty()):(expectedVersion<1||oldLinks.isEmpty()))throw invalid();
        this.expectedVersion=expectedVersion;this.newVersion=create?1:nextVersion(expectedVersion);
        this.desiredHazards=canonicalHazards(desiredHazards);this.oldLinks=canonicalOld(tenantId,scenarioId,oldLinks);
        if(!create&&this.oldLinks.stream().anyMatch(l->l.lastScenarioVersion()>expectedVersion))throw invalid();
        var oldHazards=new HashSet<UUID>();this.oldLinks.forEach(l->oldHazards.add(l.hazardId()));
        var desired=new HashSet<>(this.desiredHazards);var added=new HashSet<>(desired);added.removeAll(oldHazards);
        if(!added.equals(newLinkIds.keySet()))throw invalid();
        var usedIds=new HashSet<UUID>();this.oldLinks.forEach(l->usedIds.add(l.id()));
        var changed=new ArrayList<Change>();
        for(UUID hazard:this.desiredHazards)if(added.contains(hazard)){
            UUID link=Objects.requireNonNull(newLinkIds.get(hazard));if(!usedIds.add(link))throw invalid();
            changed.add(new Change(link,hazard,1,ChangeKind.NEW));
        }
        for(var old:this.oldLinks)if(!desired.contains(old.hazardId()))changed.add(new Change(old.id(),old.hazardId(),2,ChangeKind.END));
        changed.sort(Comparator.comparing(Change::id,RiskScenarioContracts::compareUuid));this.changes=List.copyOf(changed);
        var union=new TreeSet<UUID>(RiskScenarioContracts::compareUuid);union.addAll(oldHazards);union.addAll(desired);
        this.unionHazards=List.copyOf(union);
        var orderedRows=new ArrayList<RowLock>();this.unionHazards.forEach(h->orderedRows.add(new RowLock(RowKind.HAZARD_SOURCE,h)));
        if(!create)orderedRows.add(new RowLock(RowKind.RISK_SCENARIO,scenarioId));
        this.oldLinks.forEach(l->orderedRows.add(new RowLock(RowKind.RISK_HAZARD,l.id())));Collections.sort(orderedRows);this.rows=List.copyOf(orderedRows);
        var keys=new TreeSet<Key>();keys.add(mutationKey("risk_scenario",tenantId,scenarioId,null,null,null));
        keys.add(mutationKey("risk_scenario_revision",tenantId,scenarioId,newVersion,null,null));
        keys.add(mutationKey("risk_scenario_receipt",tenantId,null,null,commandId,null));
        for(var change:this.changes){keys.add(mutationKey("risk_hazard",tenantId,change.id(),null,null,null));
            keys.add(mutationKey("risk_hazard_revision",tenantId,change.id(),change.version(),null,null));}
        for(UUID hazard:this.desiredHazards)keys.add(mutationKey("risk_scenario_revision_hazard",tenantId,scenarioId,newVersion,null,hazard));
        this.mutationKeys=List.copyOf(keys);
    }
    static List<ActiveLink> canonicalOld(UUID tenant,UUID scenario,List<ActiveLink> links){
        Objects.requireNonNull(links);if(links.size()>100)throw invalid();var result=new ArrayList<>(links);
        var ids=new HashSet<UUID>();var hazards=new HashSet<UUID>();
        for(var link:result)if(link==null||!link.tenantId().equals(tenant)||!link.scenarioId().equals(scenario)
                ||!ids.add(link.id())||!hazards.add(link.hazardId()))throw invalid();
        result.sort(Comparator.comparing(ActiveLink::id,RiskScenarioContracts::compareUuid));return List.copyOf(result);
    }
    void requireStable(long parentVersion,List<ActiveLink> current){
        if(create||parentVersion!=expectedVersion||!oldLinks.equals(canonicalOld(tenantId,scenarioId,current)))throw new Failure(409);
    }
    List<Object> databasePlanBindings(){
        return List.of(tenantId,scenarioId,newVersion,commandId,uuidArray(changes.stream().map(Change::id).toList()),
                longArray(changes.stream().map(Change::version).toList()),uuidArray(desiredHazards));
    }
    static String uuidArray(Collection<UUID> ids){return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",","{","}"));}
    private static String longArray(Collection<Long> values){return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",","{","}"));}

    static byte[] naturalKey(UUID tenant,String code){
        return append(header("risk_scenario",tenant),field(5,RiskScenarioContracts.code(code).getBytes(StandardCharsets.UTF_8)));
    }
    static byte[] receiptKey(UUID tenant,UUID actor,RegisteredEntityAction action,UUID idempotency){
        if(action!=RegisteredEntityAction.RISK_SCENARIO_CREATE&&action!=RegisteredEntityAction.RISK_SCENARIO_UPDATE)throw invalid();
        return append(header("risk_scenario_receipt",tenant),field(6,new byte[]{1}),field(7,uuid(actor)),
                field(8,action.permissionCode().getBytes(StandardCharsets.UTF_8)),field(9,uuid(idempotency)));
    }
    private static Key mutationKey(String table,UUID tenant,UUID entity,Long version,UUID command,UUID hazard){
        byte[] bytes=header(table,tenant);int family;
        if(table.equals("risk_scenario_receipt")){family=40;bytes=append(bytes,field(6,new byte[]{4}),field(12,uuid(command)));}
        else {family=version==null?20:30;bytes=append(bytes,field(6,new byte[]{(byte)(version==null?2:3)}),field(10,uuid(entity)));
            if(version!=null)bytes=append(bytes,field(11,number(version,8)));if(hazard!=null)bytes=append(bytes,field(13,uuid(hazard)));}
        return new Key(family,bytes);
    }
    private static byte[] header(String table,UUID tenant){
        return append(field(1,number(1,4)),field(2,table.getBytes(StandardCharsets.UTF_8)),field(3,uuid(tenant)));
    }
    private static byte[] uuid(UUID id){Objects.requireNonNull(id);return append(number(id.getMostSignificantBits(),8),number(id.getLeastSignificantBits(),8));}
    private static byte[] number(long value,int width){
        var out=new ByteArrayOutputStream();try(var data=new DataOutputStream(out)){if(width==4)data.writeInt((int)value);else data.writeLong(value);}
        catch(IOException impossible){throw new IllegalStateException(impossible);}return out.toByteArray();
    }
    private static byte[] field(int tag,byte[] value){return append(new byte[]{(byte)tag},number(value.length,4),value);}
    private static byte[] append(byte[]... values){var out=new ByteArrayOutputStream();for(var value:values)out.writeBytes(value);return out.toByteArray();}
}
