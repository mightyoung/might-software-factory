package com.mightyoung.softwarefactory.platformadmin;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationException.Reason.*;

@Repository
public final class JooqRuntimeLedgerCommandRepository implements RuntimeLedgerCommandRepository {
    private final DSLContext dsl;
    private final ObjectMapper json;
    public JooqRuntimeLedgerCommandRepository(DSLContext dsl,ObjectMapper json) { this.dsl=dsl; this.json=json; }

    @Override public LedgerConfigurationModels.Snapshot published(UUID tenant,String key) {
        scope(tenant);
        var row=dsl.fetchOne("select s.version,s.definition::text,s.saved_at,s.source_version,s.actor_member_id from public.ledger_configuration c join public.ledger_configuration_snapshot s on s.tenant_id=c.tenant_id and s.configuration_key=c.configuration_key and s.version=c.published_version where c.tenant_id=? and c.configuration_key=?",tenant,key);
        if(row==null) throw new LedgerConfigurationException(NOT_FOUND);
        long version=row.get(0,Long.class);
        return new LedgerConfigurationModels.Snapshot(key,version,version,json.readValue(row.get(1,String.class),LedgerConfigurationModels.Definition.class),row.get(2,OffsetDateTime.class).toInstant(),row.get(3,Long.class),row.get(4,UUID.class));
    }
    @Override public LedgerConfigurationModels.Definition definition(UUID tenant,String key,long version) {
        scope(tenant);
        String body=dsl.fetchOptional("select definition::text from public.ledger_configuration_snapshot where tenant_id=? and configuration_key=? and version=?",tenant,key,version).map(row -> row.get(0,String.class)).orElse(null);
        if(body==null) throw new LedgerConfigurationException(NOT_FOUND);
        return json.readValue(body,LedgerConfigurationModels.Definition.class);
    }
    @Override public RecordView lock(UUID tenant,String key,UUID id) {
        scope(tenant);
        Record row=dsl.fetchOne("select * from public.ordinary_ledger_record where tenant_id=? and configuration_key=? and id=? and not deleted for update",tenant,key,id);
        if(row==null) throw new LedgerConfigurationException(NOT_FOUND);
        return view(row);
    }
    @Override public RecordView replay(UUID tenant,UUID member,String key,UUID request,String operation,Object payload) {
        scope(tenant);
        Record row=dsl.fetchOne("select operation,member_id,request_payload=cast(? as jsonb) as matches,response_payload::text from public.ordinary_ledger_command where tenant_id=? and configuration_key=? and request_id=?",json.writeValueAsString(payload),tenant,key,request);
        if(row==null) return null;
        if(!operation.equals(row.get(0,String.class)) || !member.equals(row.get(1,UUID.class)) || !Boolean.TRUE.equals(row.get(2,Boolean.class))) throw new LedgerConfigurationException(REQUEST_CONFLICT);
        return json.readValue(row.get(3,String.class),RecordView.class);
    }
    @Override public RecordView create(UUID tenant,UUID member,String key,CreateRequest request) {
        scope(tenant);
        UUID id=UUID.randomUUID();
        dsl.execute("insert into public.ordinary_ledger_record(tenant_id,configuration_key,id,definition_version,organization_id,values_json,version,created_by_member_id,updated_by_member_id) values(?,?,?,?,?,cast(? as jsonb),1,?,?)",tenant,key,id,request.definitionVersion(),request.organizationId(),json.writeValueAsString(request.values()),member,member);
        RecordView result=lock(tenant,key,id);
        receipt(tenant,member,key,request.requestId(),"create",request,result);
        return result;
    }
    @Override public RecordView update(UUID tenant,UUID member,String key,UUID id,UpdateRequest request) {
        scope(tenant);
        int changed=dsl.execute("update public.ordinary_ledger_record set values_json=cast(? as jsonb),version=version+1,updated_by_member_id=?,updated_at=now() where tenant_id=? and configuration_key=? and id=? and version=? and not deleted and state='DRAFT'",json.writeValueAsString(request.values()),member,tenant,key,id,request.expectedVersion());
        if(changed!=1) throw new LedgerConfigurationException(VERSION_CONFLICT);
        RecordView result=lock(tenant,key,id);
        receipt(tenant,member,key,request.requestId(),"update:"+id,request,result);
        return result;
    }
    @Override public RecordView delete(UUID tenant,UUID member,String key,UUID id,DeleteRequest request) {
        scope(tenant);
        Record row=dsl.fetchOne("update public.ordinary_ledger_record set deleted=true,version=version+1,updated_by_member_id=?,updated_at=now() where tenant_id=? and configuration_key=? and id=? and version=? and not deleted and state='DRAFT' returning *",member,tenant,key,id,request.expectedVersion());
        if(row==null) throw new LedgerConfigurationException(VERSION_CONFLICT);
        RecordView result=view(row);
        receipt(tenant,member,key,request.requestId(),"delete:"+id,request,result);
        return result;
    }
    private void receipt(UUID tenant,UUID member,String key,UUID request,String operation,Object payload,RecordView result) {
        dsl.execute("insert into public.ordinary_ledger_command(tenant_id,configuration_key,request_id,member_id,operation,record_id,request_payload,response_payload) values(?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))",tenant,key,request,member,operation,result.id(),json.writeValueAsString(payload),json.writeValueAsString(result));
    }
    private RecordView view(Record row) {
        Map<String,Object> values=json.readValue(row.get("values_json",String.class),Map.class);
        return new RecordView(row.get("id",UUID.class),row.get("configuration_key",String.class),row.get("definition_version",Long.class),row.get("organization_id",UUID.class),values,row.get("version",Long.class),row.get("created_at",OffsetDateTime.class).toInstant(),row.get("updated_at",OffsetDateTime.class).toInstant());
    }
    private void scope(UUID tenant) { dsl.fetchValue("select set_config('app.tenant_id',?,true)",tenant.toString()); }
}
