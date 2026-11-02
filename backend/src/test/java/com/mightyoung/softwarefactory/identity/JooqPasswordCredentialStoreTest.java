package com.mightyoung.softwarefactory.identity;

import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JooqPasswordCredentialStoreTest {
    private final IdentitySecurityTransaction transaction=mock(IdentitySecurityTransaction.class);
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,4);
    private final PasswordCredentialState expected=new PasswordCredentialState(4,"old-encoded-password-value");
    private final List<String> statements=new ArrayList<>();
    private final List<List<Object>> bindings=new ArrayList<>();
    private final Queue<Integer> results=new ArrayDeque<>();
    private final Settings parentSettings=new Settings().withExecuteLogging(true);
    private JooqPasswordCredentialStore store() {
        var dsl=DSL.using(new MockConnection(call->{statements.add(call.sql());bindings.add(Arrays.asList(call.bindings()));return new MockResult[]{new MockResult(results.isEmpty()?1:results.remove())};}),SQLDialect.POSTGRES,parentSettings);
        return new JooqPasswordCredentialStore(dsl,transaction);
    }
    @Test void markerMustBeActiveBeforeAnySql() {
        doThrow(new IllegalStateException("No writer marker")).when(transaction).requireActive(context);
        assertThatThrownBy(()->store().replace(context,expected,"new-encoded-password-value",Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(statements).isEmpty();
    }
    @Test void firstCasFailureNeverAttemptsSecondWrite() {
        results.add(0);
        assertThatThrownBy(()->store().replace(context,expected,"new-encoded-password-value",Instant.now())).isInstanceOf(PasswordChangeException.class);
        assertThat(statements).hasSize(1);
    }
    @Test void secondCasFailureEscapesSoTheEarlierCredentialWriteCannotCommit() {
        results.add(1);results.add(0);
        assertThatThrownBy(()->store().replace(context,expected,"new-encoded-password-value",Instant.now())).isInstanceOf(PasswordChangeException.class);
        assertThat(statements).hasSize(2);
    }
    @Test void exactHashVersionAccountPrincipalAndUtcTimestampAreBoundWithoutGlobalSettingMutation() {
        Instant changed=Instant.parse("2026-11-02T00:00:00Z");store().replace(context,expected,"new-encoded-password-value",changed);
        verify(transaction).requireActive(context);
        assertThat(statements.getFirst()).contains("update public.credential", "changed_at=cast(? as timestamptz)");
        assertThat(bindings.getFirst()).containsExactly("new-encoded-password-value","2026-11-02 00:00:00+00:00",context.accountId(),expected.encodedPassword(),4L,context.actorId());
        assertThat(statements.getLast()).contains("update public.account set credential_version=credential_version+1");
        assertThat(bindings.getLast()).containsExactly(context.accountId(),4L);
        assertThat(parentSettings.isExecuteLogging()).isTrue();
        var isolated=(org.jooq.DSLContext)org.springframework.test.util.ReflectionTestUtils.getField(store(),"dsl");
        assertThat(isolated.configuration().settings().isExecuteLogging()).isFalse();
    }
}
