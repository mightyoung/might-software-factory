package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** SQL/port source evidence; real grant/RLS/lock-wait acceptance remains a separate gate. */
class PersistentIdentityLifecycleAuthorizationTest {
    final DataSource source=mock(DataSource.class);
    final IdentityLifecycleSecurityTransaction writer=mock(IdentityLifecycleSecurityTransaction.class);
    final AccountStatusReadRepository accounts=mock(AccountStatusReadRepository.class);
    final PrincipalStatusReadRepository principals=mock(PrincipalStatusReadRepository.class);
    final TenantMembershipReadRepository members=mock(TenantMembershipReadRepository.class);
    final TenantApplicationReadRepository apps=mock(TenantApplicationReadRepository.class);
    final UUID tenant=UUID.randomUUID();
    final IssuerBinding issuer=new IssuerBinding(UUID.randomUUID(),UUID.randomUUID(),tenant,UUID.randomUUID(),1,0,0);
    final IdentityLifecycleSecurityTransaction.Scope scope=IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_INVITATION,UUID.randomUUID(),tenant);
    final AtomicBoolean granted=new AtomicBoolean(true);
    final List<MockExecuteContext> statements=new ArrayList<>();
    PersistentIdentityLifecycleAuthorization authorization;
    @BeforeEach void setup()throws Exception {
        when(source.getConnection()).thenAnswer(call->new MockConnection(execution->{
            statements.add(execution);DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<Boolean> field=DSL.field("exists",Boolean.class);
            Result<Record1<Boolean>> result=db.newResult(field);result.add(db.newRecord(field).value1(granted.get()));return new MockResult[]{new MockResult(1,result)};
        }));
        when(accounts.findState(issuer.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",9)));
        when(principals.findByActorId(issuer.actorId())).thenReturn(Optional.of(new PrincipalStatus(issuer.actorId(),issuer.accountId(),"HUMAN","ACTIVE")));
        when(members.findCurrentMembership(tenant,issuer.accountId(),issuer.memberId())).thenReturn(Optional.of(new TenantMembershipState("ACTIVE","ACTIVE",8,7)));
        when(apps.findCurrent(tenant,"system")).thenReturn(Optional.of(new TenantApplicationState(true,4)));
        authorization=new PersistentIdentityLifecycleAuthorization(source,writer,accounts,principals,members,apps);
    }
    @Test void issuerVersionsRemainImmutableProvenanceWhenCurrentAuthorityStillExists(){
        authorization.requireIssuerStillAuthorized(scope,issuer,IdentityLifecycleAuthorization.Action.CREATE);
        assertThat(issuer.credentialVersion()).isEqualTo(1);assertThat(issuer.tenantAuthEpoch()).isZero();assertThat(issuer.memberAuthEpoch()).isZero();
        assertThat(statements).hasSize(1);assertThat(statements.getFirst().bindings()).contains("identity.invitation.create","create");
        verify(writer,atLeast(2)).requireSqlConnection(eq(scope),eq(source),any());
    }
    @Test void revokedPermissionOrSystemEntitlementCannotBeReplayed(){
        granted.set(false);assertThatThrownBy(()->authorization.requireIssuerStillAuthorized(scope,issuer,IdentityLifecycleAuthorization.Action.CREATE)).isInstanceOf(IdentityLifecycleException.class);
        when(apps.findCurrent(tenant,"system")).thenReturn(Optional.of(new TenantApplicationState(false,5)));granted.set(true);statements.clear();
        assertThatThrownBy(()->authorization.requireIssuerStillAuthorized(scope,issuer,IdentityLifecycleAuthorization.Action.CREATE)).isInstanceOf(IdentityLifecycleException.class);assertThat(statements).isEmpty();
    }
    @Test void missingDisabledOrWrongIdentityBindingsDenyBeforeEffectiveGrantQuery(){
        when(principals.findByActorId(issuer.actorId())).thenReturn(Optional.of(new PrincipalStatus(issuer.actorId(),UUID.randomUUID(),"HUMAN","ACTIVE")));
        assertThatThrownBy(()->authorization.requireIssuerStillAuthorized(scope,issuer,IdentityLifecycleAuthorization.Action.CREATE)).isInstanceOf(IdentityLifecycleException.class);assertThat(statements).isEmpty();
        assertThatThrownBy(()->authorization.requireIssuerStillAuthorized(IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_INVITATION,scope.challengeId(),UUID.randomUUID()),issuer,IdentityLifecycleAuthorization.Action.CREATE)).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void authorizationReadsStatementTimeAfterGuardRatherThanTransactionStartOrStaleDirectoryIds(){
        String sql=PersistentIdentityLifecycleAuthorization.TENANT_CAPABILITY_SQL;
        assertThat(sql).doesNotContain("current_timestamp","transaction_timestamp","= any").contains("sr.kind='IDENTITY_TENANT'","sr.definition='{}'::jsonb","not exists(select 1 from public.scope_org","p.resource_type='account-invitation'");
        for(String alias:List.of("rg","mo","mp","gm"))assertThat(sql).contains(alias+".valid_from<=statement_timestamp()",alias+".valid_to>statement_timestamp()");
        // Conceptual txnBegin < grantExpiry < guardAcquired: only a fresh statement-time proof may grant.
        java.time.Instant expiry=java.time.Instant.parse("2026-11-03T12:00:01Z"),guardTime=expiry.plusSeconds(1);
        granted.set(guardTime.isBefore(expiry));
        assertThatThrownBy(()->authorization.requireIssuerStillAuthorized(scope,issuer,IdentityLifecycleAuthorization.Action.CREATE)).isInstanceOf(IdentityLifecycleException.class);
    }
}
