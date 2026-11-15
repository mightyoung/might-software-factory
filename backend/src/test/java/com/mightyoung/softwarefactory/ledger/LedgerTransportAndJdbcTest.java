package com.mightyoung.softwarefactory.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerTransportAndJdbcTest {
    @Test void normalizesBothDriverTimestampAndTypedTimestampToSameUtcInstant() {
        Instant instant=Instant.parse("2026-11-15T03:07:00.123456Z");
        assertThat(LedgerRepository.normalizeValue(Timestamp.from(instant))).isEqualTo(instant);
        assertThat(LedgerRepository.normalizeValue(OffsetDateTime.parse("2026-11-15T11:07:00.123456+08:00"))).isEqualTo(instant);
        UUID id=UUID.randomUUID();assertThat(LedgerRepository.normalizeValue(id)).isSameAs(id);
        assertThat(LedgerRepository.normalizeValue(12L)).isEqualTo(12L);
        assertThat(LedgerRepository.normalizeValue(null)).isNull();
    }
    @Test void daoProjectionPreservesAllSevenPublishedTypesAndSerializesDateAsUtcString() {
        var types=java.util.Map.of("text",LedgerDefinition.Type.TEXT,"notes",LedgerDefinition.Type.MULTILINE_TEXT,
                "identifier",LedgerDefinition.Type.UUID,"quantity",LedgerDefinition.Type.INTEGER,"observedAt",LedgerDefinition.Type.DATE_TIME,
                "state",LedgerDefinition.Type.ENUM,"reference",LedgerDefinition.Type.REFERENCE);
        var binding=mock(LedgerBinding.class);var definition=mock(LedgerDefinition.class);
        java.util.Map<String,LedgerDefinition.Field> fields=new java.util.LinkedHashMap<>();
        java.util.Map<String,String> columns=new java.util.LinkedHashMap<>();
        types.forEach((key,type)->{columns.put(key,key);fields.put(key,new LedgerDefinition.Field(key,type,false,java.util.Map.of()));});
        when(binding.definition()).thenReturn(definition);when(definition.fields()).thenReturn(fields);
        when(binding.storage()).thenReturn(new LedgerBinding.Storage("sample",columns,java.util.Map.of(),java.util.Set.of(),java.util.Set.of()));
        var selected=new java.util.ArrayList<org.jooq.Field<?>>();selected.add(org.jooq.impl.DSL.field("id",UUID.class));selected.add(org.jooq.impl.DSL.field("version",Long.class));
        columns.keySet().forEach(key->selected.add(org.jooq.impl.DSL.field(key,Object.class)));
        var record=org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES).newRecord(selected);
        UUID id=UUID.randomUUID(),reference=UUID.randomUUID();Instant time=Instant.parse("2026-11-15T03:07:00.123456Z");
        Object[] raw=new Object[selected.size()];raw[0]=id;raw[1]=2L;
        java.util.Map<String,Object> values=java.util.Map.of("text","tool","notes","first\nsecond","identifier",id,
                "quantity",42L,"observedAt",Timestamp.from(time),"state","NORMAL","reference",reference);
        for(int i=2;i<selected.size();i++)raw[i]=values.get(selected.get(i).getName());
        record.fromArray(raw);
        var row=LedgerRepository.row(binding,record);
        assertThat(row.id()).isEqualTo(id);assertThat(row.version()).isEqualTo(2);
        assertThat(row.values()).containsEntry("text","tool").containsEntry("notes","first\nsecond").containsEntry("identifier",id)
                .containsEntry("quantity",42L).containsEntry("observedAt",time).containsEntry("state","NORMAL").containsEntry("reference",reference);
        var json=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(row.values());
        assertThat(json.get("observedAt").stringValue()).isEqualTo("2026-11-15T03:07:00.123456Z");
    }
    @Test void bindsOnlyTransactionLocalTenantThenVerifiesActualContext() throws Exception {
        var connection=mock(Connection.class);var setter=mock(PreparedStatement.class);var verify=mock(PreparedStatement.class);
        var applied=mock(ResultSet.class);var observed=mock(ResultSet.class);UUID tenant=UUID.randomUUID();
        when(connection.prepareStatement("select set_config('app.tenant_id', ?, true)")).thenReturn(setter);
        when(connection.prepareStatement("select current_setting('app.tenant_id',true)")).thenReturn(verify);
        when(setter.executeQuery()).thenReturn(applied);when(verify.executeQuery()).thenReturn(observed);
        when(applied.next()).thenReturn(true,false);when(observed.next()).thenReturn(true,false);
        when(applied.getString(1)).thenReturn(tenant.toString());when(observed.getString(1)).thenReturn(tenant.toString());
        LedgerRuntime.bindTenant(connection,tenant);verify(setter).setString(1,tenant.toString());
        when(applied.next()).thenReturn(true,false);when(observed.next()).thenReturn(true,false);
        when(observed.getString(1)).thenReturn(UUID.randomUUID().toString());
        assertThatThrownBy(()->LedgerRuntime.bindTenant(connection,tenant)).isInstanceOf(LedgerException.class);
        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(()->LedgerRuntime.bindTenant(connection,tenant)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(()->LedgerRuntime.bindTenant(null,tenant)).isInstanceOf(LedgerException.class);
    }
    @Test void parserAcceptsTypedRequestAndRejectsDuplicatesUnknownPropertiesCoercionAndTrailingObjects() {
        var parser=new LedgerHttpParser();
        var accepted=parser.read(request("{\"definitionVersion\":\"v1\",\"expectedVersion\":1,\"values\":{\"name\":\"tool\"}}"),LedgerContracts.UpdateRequest.class);
        assertThat(accepted.expectedVersion()).isEqualTo(1L);
        for(String input:new String[]{"{\"definitionVersion\":\"a\",\"definitionVersion\":\"b\",\"values\":{}}",
                "{\"definitionVersion\":\"a\",\"unknown\":true,\"values\":{}}",
                "{\"definitionVersion\":\"a\",\"expectedVersion\":\"1\",\"values\":{}}",
                "{\"values\":{\"x\":1,\"x\":2}}","{} {}"}) {
            assertThatThrownBy(()->parser.read(request(input),LedgerContracts.UpdateRequest.class)).isInstanceOf(LedgerInputException.class);
        }
        assertThatThrownBy(()->parser.read(request(" ".repeat(65_537)),LedgerContracts.UpdateRequest.class)).isInstanceOf(LedgerException.class);
    }
    @Test void uniqueConstraintConflictsAreSafe409AndOtherStorageFailuresRemainUnavailable() {
        var handler=new LedgerExceptionHandler();
        var duplicate=handler.persistence(new org.jooq.exception.DataAccessException("SQL and values must not escape",new java.sql.SQLException("secret", "23505")));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().toString()).doesNotContain("secret","SQL and values");
        assertThat(handler.persistence(new org.jooq.exception.DataAccessException("secret",new java.sql.SQLException("secret","42501"))).getStatusCode().value()).isEqualTo(503);
    }
    private static MockHttpServletRequest request(String value) {
        var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));return request;
    }
}
