package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;

/** Flat, finite token schemas: coercion, duplicate keys and unknown nullable fields are rejected. */
public final class AssetFoundationRequestParser {
    private final ObjectMapper json;
    public AssetFoundationRequestParser(ObjectMapper json) { this.json=Objects.requireNonNull(json); }
    public WorkshopCreate workshopCreate(HttpServletRequest request) {
        var f=fields(request,Set.of("code","name","organizationId"),Set.of("boundaryDescription"),Set.of());
        return new WorkshopCreate(f.get("code"),f.get("name"),uuid(f.get("organizationId")),f.get("boundaryDescription"));
    }
    public WorkshopUpdate workshopUpdate(HttpServletRequest request) {
        var f=fields(request,Set.of("name"),Set.of("boundaryDescription"),Set.of("expectedVersion"));
        return new WorkshopUpdate(number(f,"expectedVersion"),f.get("name"),f.get("boundaryDescription"));
    }
    public ProductionLineCreate productionLineCreate(HttpServletRequest request) {
        var f=fields(request,Set.of("code","name","organizationId","workshopId"),Set.of("description"),Set.of());
        return new ProductionLineCreate(f.get("code"),f.get("name"),uuid(f.get("organizationId")),uuid(f.get("workshopId")),f.get("description"));
    }
    public ProductionLineUpdate productionLineUpdate(HttpServletRequest request) {
        var f=fields(request,Set.of("name"),Set.of("description"),Set.of("expectedVersion"));
        return new ProductionLineUpdate(number(f,"expectedVersion"),f.get("name"),f.get("description"));
    }
    public ProcessStageCreate processStageCreate(HttpServletRequest request) {
        var f=fields(request,Set.of("code","name","organizationId","lineId"),Set.of("description"),Set.of("ordinal"));
        return new ProcessStageCreate(f.get("code"),f.get("name"),uuid(f.get("organizationId")),uuid(f.get("lineId")),Integer.parseInt(f.get("ordinal")),f.get("description"));
    }
    public ProcessStageUpdate processStageUpdate(HttpServletRequest request) {
        var f=fields(request,Set.of("name"),Set.of("description"),Set.of("expectedVersion","ordinal"));
        return new ProcessStageUpdate(number(f,"expectedVersion"),f.get("name"),Integer.parseInt(f.get("ordinal")),f.get("description"));
    }
    public EquipmentCreate equipmentCreate(HttpServletRequest request) {
        var f=fields(request,Set.of("code","name","organizationId","typeDescription","operationalState"),Set.of("locationDescription"),Set.of());
        return new EquipmentCreate(f.get("code"),f.get("name"),uuid(f.get("organizationId")),f.get("typeDescription"),f.get("locationDescription"),enumeration(OperationalState.class,f.get("operationalState")));
    }
    public EquipmentUpdate equipmentUpdate(HttpServletRequest request) {
        var f=fields(request,Set.of("name","typeDescription","operationalState"),Set.of("locationDescription"),Set.of("expectedVersion"));
        return new EquipmentUpdate(number(f,"expectedVersion"),f.get("name"),f.get("typeDescription"),f.get("locationDescription"),enumeration(OperationalState.class,f.get("operationalState")));
    }
    public WorkUnitCreate workUnitCreate(HttpServletRequest request) {
        var f=fields(request,Set.of("code","name","organizationId","kind"),Set.of("workshopId","lineId","stageId","equipmentId","boundaryDescription"),Set.of());
        WorkUnitKind kind=enumeration(WorkUnitKind.class,f.get("kind"));
        String source=switch(kind) { case STANDALONE->""; case WORKSHOP->"workshopId"; case LINE->"lineId"; case STAGE->"stageId"; case EQUIPMENT->"equipmentId"; };
        for(String key:List.of("workshopId","lineId","stageId","equipmentId")) {
            if(!key.equals(source)&&f.containsKey(key))throw invalid();
            if(key.equals(source)&&f.get(key)==null)throw invalid();
        }
        return new WorkUnitCreate(f.get("code"),f.get("name"),uuid(f.get("organizationId")),kind,
                optionalUuid(f.get("workshopId")),optionalUuid(f.get("lineId")),optionalUuid(f.get("stageId")),optionalUuid(f.get("equipmentId")),f.get("boundaryDescription"));
    }
    public WorkUnitUpdate workUnitUpdate(HttpServletRequest request) {
        var f=fields(request,Set.of("name"),Set.of("boundaryDescription"),Set.of("expectedVersion"));
        return new WorkUnitUpdate(number(f,"expectedVersion"),f.get("name"),f.get("boundaryDescription"));
    }
    public HazardSourceContracts.Create hazardCreate(HttpServletRequest request) {
        var f=fields(request,Set.of("code","organizationId","workUnitId","description","identifiedAt","existenceState"),Set.of(),Set.of());
        return new HazardSourceContracts.Create(f.get("code"),uuid(f.get("organizationId")),uuid(f.get("workUnitId")),f.get("description"),time(f.get("identifiedAt")),enumeration(HazardSourceContracts.ExistenceState.class,f.get("existenceState")));
    }
    public HazardSourceContracts.Update hazardUpdate(HttpServletRequest request) {
        var f=fields(request,Set.of("description","identifiedAt","existenceState"),Set.of(),Set.of("expectedVersion"));
        return new HazardSourceContracts.Update(number(f,"expectedVersion"),f.get("description"),time(f.get("identifiedAt")),enumeration(HazardSourceContracts.ExistenceState.class,f.get("existenceState")));
    }
    public LineWorkshopBind lineWorkshopBind(HttpServletRequest request) {
        var f=fields(request,Set.of("workshopId","validFrom"),Set.of("validTo"),Set.of("expectedLineVersion"));
        return new LineWorkshopBind(number(f,"expectedLineVersion"),uuid(f.get("workshopId")),time(f.get("validFrom")),optionalTime(f.get("validTo")));
    }
    public StageEquipmentBind stageEquipmentBind(HttpServletRequest request) {
        var f=fields(request,Set.of("equipmentId","validFrom"),Set.of("validTo"),Set.of("expectedStageVersion"));
        return new StageEquipmentBind(number(f,"expectedStageVersion"),uuid(f.get("equipmentId")),time(f.get("validFrom")),optionalTime(f.get("validTo")));
    }
    public ResponsibilityBind responsibilityBind(HttpServletRequest request) {
        var f=fields(request,Set.of("orgId","duty","validFrom"),Set.of("positionId","validTo"),Set.of("expectedWorkUnitVersion"));
        return new ResponsibilityBind(number(f,"expectedWorkUnitVersion"),uuid(f.get("orgId")),optionalUuid(f.get("positionId")),
                enumeration(Duty.class,f.get("duty")),time(f.get("validFrom")),optionalTime(f.get("validTo")));
    }
    public EndBinding endBinding(HttpServletRequest request) {
        var f=fields(request,Set.of(),Set.of("endAt"),Set.of("expectedParentVersion","expectedBindingVersion"));
        return new EndBinding(number(f,"expectedParentVersion"),number(f,"expectedBindingVersion"),optionalTime(f.get("endAt")));
    }
    private static java.time.Instant optionalTime(String value) { return value==null?null:time(value); }
    public Archive archive(HttpServletRequest request) { return new Archive(number(fields(request,Set.of(),Set.of(),Set.of("expectedVersion")),"expectedVersion")); }
    public UUID idempotencyKey(HttpServletRequest request) {
        var headers=Collections.list(request.getHeaders("Idempotency-Key"));
        if(headers.size()!=1)throw invalid();
        return uuid(headers.getFirst());
    }
    public static UUID uuid(String value) {
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
        return UUID.fromString(value);
    }
    private static UUID optionalUuid(String value) { return value==null?null:uuid(value); }
    private static long number(Map<String,String> fields,String key) {
        try { return version(Long.parseLong(fields.get(key))); } catch(NumberFormatException e) { throw invalid(); }
    }
    private static java.time.Instant time(String value) {
        if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})"))throw invalid();
        try { return instant(OffsetDateTime.parse(value).toInstant()); } catch(java.time.DateTimeException e) { throw invalid(); }
    }
    private static <E extends Enum<E>> E enumeration(Class<E> type,String value) {
        try { return Enum.valueOf(type,value); } catch(IllegalArgumentException|NullPointerException e) { throw invalid(); }
    }
    private Map<String,String> fields(HttpServletRequest request,Set<String> required,Set<String> optional,Set<String> numbers) {
        if(request.getQueryString()!=null)throw invalid();
        var headers=Collections.list(request.getHeaders("Content-Type"));
        if(headers.size()!=1||!headers.getFirst().matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*"))throw new AssetFoundationFailure(415);
        try {
            byte[] bytes=request.getInputStream().readNBytes(32769);
            if(bytes.length>32768)throw new AssetFoundationFailure(413);
            String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(body.startsWith("\uFEFF"))throw invalid();
            var result=new HashMap<String,String>();
            try(var parser=json.createParser(body)) {
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw invalid();
                while(parser.nextToken()!=JsonToken.END_OBJECT) {
                    if(parser.currentToken()!=JsonToken.PROPERTY_NAME)throw invalid();
                    String key=parser.currentName();
                    if(result.containsKey(key)||!required.contains(key)&&!optional.contains(key)&&!numbers.contains(key))throw invalid();
                    var token=parser.nextToken();
                    if(numbers.contains(key)) {
                        if(token!=JsonToken.VALUE_NUMBER_INT)throw invalid();
                        String value=parser.getString();
                        if(key.equals("ordinal")) {
                            if(!value.matches("0|[1-9][0-9]{0,6}"))throw invalid();
                            ordinal(Integer.parseInt(value));
                        } else {
                            if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();
                            version(Long.parseLong(value));
                        }
                        result.put(key,value);
                    } else if(token==JsonToken.VALUE_NULL&&optional.contains(key)) result.put(key,null);
                    else {
                        if(token!=JsonToken.VALUE_STRING)throw invalid();
                        String value=parser.getString(); text(value,20000,false); result.put(key,value);
                    }
                }
                if(parser.nextToken()!=null||!result.keySet().containsAll(required)||!result.keySet().containsAll(numbers))throw invalid();
            }
            return result;
        } catch(tools.jackson.core.JacksonException|java.io.IOException|NumberFormatException e) { throw invalid(); }
    }
}
