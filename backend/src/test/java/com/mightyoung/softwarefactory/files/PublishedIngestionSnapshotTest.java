package com.mightyoung.softwarefactory.files;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mightyoung.softwarefactory.dualprevention.FindingEvidenceContentReadService;
import com.mightyoung.softwarefactory.dualprevention.FindingReadAuthorizer;
import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring transaction manager and content service; JDBC facts are explicit controlled fixtures. */
class PublishedIngestionSnapshotTest {
    @TempDir Path root;
    static final class Jdbc {
        final DataSource source=mock(DataSource.class);
        final Connection connection=mock(Connection.class);
        final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        final AtomicBoolean readOnly=new AtomicBoolean(),autoCommit=new AtomicBoolean(true);
        String sqlIsolation,sqlReadOnly;
        UUID tenant=UUID.randomUUID();
        Jdbc()throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(i->autoCommit.get());
            doAnswer(i->{autoCommit.set(i.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenAnswer(i->isolation.get());
            doAnswer(i->{isolation.set(i.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
            when(connection.isReadOnly()).thenAnswer(i->readOnly.get());
            doAnswer(i->{readOnly.set(i.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
            when(connection.prepareStatement(anyString())).thenAnswer(i->{
                var statement=mock(PreparedStatement.class);var row=mock(ResultSet.class);
                when(statement.executeQuery()).thenReturn(row);when(row.next()).thenReturn(true,false);
                when(row.getString(1)).thenAnswer(x->tenant.toString());
                when(row.getString(2)).thenAnswer(x->sqlIsolation!=null?sqlIsolation:isolation.get()==Connection.TRANSACTION_REPEATABLE_READ?"repeatable read":"read committed");
                when(row.getString(3)).thenAnswer(x->sqlReadOnly!=null?sqlReadOnly:readOnly.get()?"on":"off");return statement;
            });
        }
        TransactionTemplate transaction(boolean readOnly,int isolation){
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setReadOnly(readOnly);tx.setIsolationLevel(isolation);return tx;
        }
    }
    @Test void actualSubmittedContentServiceUsesSamePhysicalWritableRepeatableRead()throws Exception {
        SecureLocalStorageTestSupport.requireOrAssumeSecureStorage(root);
        var fixture=new PublishedIngestionContentReaderTest();fixture.root=root;
        var publication=fixture.publication(true);var snapshot=fixture.snapshot(publication);
        var jdbc=new Jdbc();jdbc.tenant=publication.tenantId();var calls=new AtomicInteger();
        var reader=new PublishedIngestionContentReader((tenant,file,receipt)->{
            assertThat(PublishedIngestionContentReader.requireSnapshot(jdbc.source,tenant).getConnection()).isSameAs(jdbc.connection);
            assertThat(jdbc.readOnly).isFalse();assertThat(jdbc.isolation).hasValue(Connection.TRANSACTION_REPEATABLE_READ);
            calls.incrementAndGet();return Optional.of(publication);
        },fixture.storage(),fixture.f.codec,fixture.legacy,fixture.legacyRead);
        var resolver=mock(SessionContextResolver.class);var authorizer=mock(FindingReadAuthorizer.class);
        var identity=new CurrentTenantContext(publication.ownerActorId(),UUID.randomUUID(),publication.tenantId(),publication.ownerMemberId(),1,1,1);
        when(resolver.currentTenant()).thenReturn(identity);
        when(authorizer.requireReadScope(identity)).thenReturn(new FindingReadScope(identity.actorId(),identity.tenantId(),identity.memberId(),Set.of(UUID.randomUUID())));
        FindingEvidenceContentReadRepository snapshots=(scope,finding,round,item)->Optional.of(snapshot);
        var policy=new FindingEvidenceFileAccessPolicy(mock(FileEvidenceMetadataReadRepository.class),mock(EvidenceFileReferenceReadRepository.class),reader);
        var service=new FindingEvidenceContentReadService(resolver,authorizer,snapshots,policy,reader,
                new StaticListableBeanFactory(Map.of("transactions",new DataSourceTransactionManager(jdbc.source))).getBeanProvider(PlatformTransactionManager.class));
        try(var content=service.openSubmittedEvidence(publication.findingId(),1,publication.evidenceItemId())){
            var output=new ByteArrayOutputStream();content.transferTo(output);assertThat(output.toByteArray()).isEqualTo(fixture.bytes);
        }
        assertThat(calls).hasValue(2);verify(jdbc.connection).commit();verify(jdbc.connection,never()).setReadOnly(true);
    }
    @Test void readonlyRepeatableReadAndWritableReadCommittedRemainSupported()throws Exception {
        for(boolean readOnly:new boolean[]{true,false}){
            var jdbc=new Jdbc();int level=readOnly?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED;
            jdbc.transaction(readOnly,level).execute(status->{assertThat(PublishedIngestionContentReader.requireSnapshot(jdbc.source,jdbc.tenant).getConnection()).isSameAs(jdbc.connection);return null;});
        }
    }
    @Test void readonlyReadCommittedAndContradictoryPhysicalFactsFailClosed()throws Exception {
        var jdbc=new Jdbc();
        assertThatThrownBy(()->jdbc.transaction(true,TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status->PublishedIngestionContentReader.requireSnapshot(jdbc.source,jdbc.tenant))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        jdbc.sqlReadOnly="on";
        assertThatThrownBy(()->jdbc.transaction(false,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->PublishedIngestionContentReader.requireSnapshot(jdbc.source,jdbc.tenant))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        jdbc.sqlReadOnly=null;jdbc.sqlIsolation="read committed";
        assertThatThrownBy(()->jdbc.transaction(false,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->PublishedIngestionContentReader.requireSnapshot(jdbc.source,jdbc.tenant))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        jdbc.sqlIsolation=null;
        assertThatThrownBy(()->jdbc.transaction(false,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->{jdbc.readOnly.set(true);return PublishedIngestionContentReader.requireSnapshot(jdbc.source,jdbc.tenant);})).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        jdbc.readOnly.set(false);
        assertThatThrownBy(()->jdbc.transaction(false,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->{jdbc.isolation.set(Connection.TRANSACTION_READ_COMMITTED);return PublishedIngestionContentReader.requireSnapshot(jdbc.source,jdbc.tenant);})).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->jdbc.transaction(false,TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->PublishedIngestionContentReader.requireSnapshot(jdbc.source,UUID.randomUUID()))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
}
