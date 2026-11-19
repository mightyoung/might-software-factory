package com.mightyoung.softwarefactory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerProtocolTest {
    @Test void writeResultContainsOnlyCommandMetadataNotBusinessFieldsOrAuthority() {
        assertThat(Arrays.stream(LedgerContracts.CommandData.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("commandId","id","version","definitionVersion","eventId","occurredAt","replay");
    }
    @Test void recordAndFactKeepNullableSnapshotsWithoutSharingMutableMaps() {
        Map<String,Object> values=new LinkedHashMap<>(); values.put("description",null); values.put("code","before");
        UUID id=UUID.randomUUID();
        var record=new LedgerContracts.RecordData(id,1,"1",values,Set.of("sample.read"));
        var fact=new LedgerChangeFactV1(id,LedgerChangeFactV1.EVENT_TYPE,1,"app","sample",id,id,1,"1","CREATE",id,id,id,id,Instant.EPOCH,values);
        values.put("code","after");
        assertThat(record.values()).containsEntry("description",null).containsEntry("code","before");
        assertThat(fact.snapshot()).containsEntry("description",null).containsEntry("code","before");
        assertThatThrownBy(()->record.values().put("code","bad")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->fact.snapshot().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->record.effectiveActions().add("sample.update")).isInstanceOf(UnsupportedOperationException.class);
    }
}
