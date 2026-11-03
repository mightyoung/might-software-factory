package com.mightyoung.softwarefactory.workflow;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FlowableWorkflowDefinitionLookupTest {
    private static final UUID TENANT=UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING=UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Test void definitionLookupLocksTheBindingBeforeReadingWithoutRequiringBindingUpdatePrivileges() {
        List<String> operations=new ArrayList<>();
        var lock=mock(WorkflowBindingTransactionLock.class);
        doAnswer(call->{operations.add("binding-lock");return null;}).when(lock).acquire(TENANT,"FINDING",FINDING);
        var values=DSL.using(SQLDialect.POSTGRES);
        var dsl=DSL.using(new MockConnection(context->{
            operations.add("binding-select");
            assertThat(operations).containsExactly("binding-lock","binding-select");
            assertThat(context.sql().toLowerCase(java.util.Locale.ROOT)).doesNotContain("for update","for share");
            var revision=DSL.field("business_revision",Integer.class);var definition=DSL.field("definition_id",String.class);
            var rows=values.newResult(revision,definition);var row=values.newRecord(revision,definition);
            row.set(revision,1);row.set(definition,"pinned-definition");rows.add(row);
            return new MockResult[]{new MockResult(1,rows)};
        }),SQLDialect.POSTGRES);
        var repository=mock(RepositoryService.class);
        var query=mock(ProcessDefinitionQuery.class,RETURNS_SELF);
        var definition=mock(ProcessDefinition.class);
        when(repository.createProcessDefinitionQuery()).thenReturn(query);when(query.singleResult()).thenReturn(definition);
        when(definition.getTenantId()).thenReturn(TENANT.toString());when(definition.getKey()).thenReturn("findingRemediationV2");
        var facade=new FlowableWorkflowFacade(mock(RuntimeService.class),mock(TaskService.class),repository,dsl,lock,mock(javax.sql.DataSource.class),mock(org.flowable.engine.ManagementService.class),mock(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard.class));

        assertThat(facade.processDefinitionKey(TENANT,"FINDING",FINDING)).contains("findingRemediationV2");
        verify(query).processDefinitionId("pinned-definition");
        when(definition.getTenantId()).thenReturn(UUID.randomUUID().toString());operations.clear();
        assertThat(facade.processDefinitionKey(TENANT,"FINDING",FINDING)).isEmpty();
    }

    @Test void rejectedBindingLockPreventsAnyDatabaseOrEngineLookup() {
        var lock=mock(WorkflowBindingTransactionLock.class);var dsl=mock(org.jooq.DSLContext.class);
        var repository=mock(RepositoryService.class);
        doThrow(new IllegalStateException("Missing managed transaction")).when(lock).acquire(TENANT,"FINDING",FINDING);
        var facade=new FlowableWorkflowFacade(mock(RuntimeService.class),mock(TaskService.class),repository,dsl,lock,mock(javax.sql.DataSource.class),mock(org.flowable.engine.ManagementService.class),mock(com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard.class));
        assertThatThrownBy(()->facade.processDefinitionKey(TENANT,"FINDING",FINDING)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(dsl,repository);
    }
}
