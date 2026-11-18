package com.mightyoung.softwarefactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real settings classification with controlled leases; no database or query activation proof. */
class TenantAuditPostgresDataSourceTest {
    @Test void bothOverloadsReturnTheSameVerifiedLeaseWithoutClosingItOrThePool() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->{
            for(boolean credentials:new boolean[]{false,true}){
                var f=new Fixture(context.getBean(JdbcRepositorySettings.class));
                assertSame(f.connection,f.borrow(credentials));verify(f.connection,never()).close();
                verify(f.metadata).getDatabaseProductName();f.verifyBorrow(credentials);verify(f.pool,never()).close();
            }
        });
    }
    @Test void nonPostgresSettingsRejectBothOverloadsBeforeAnyBorrow() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false,"H2").run(context->{
            for(boolean credentials:new boolean[]{false,true}){
                var f=new Fixture(context.getBean(JdbcRepositorySettings.class));clearInvocations(f.pool);
                assertThrows(SQLException.class,()->f.borrow(credentials));verifyNoInteractions(f.pool);verify(f.connection,never()).close();
            }
        });
    }
    @Test void nonPostgresOrMissingMetadataReleasesEachRejectedLeaseExactlyOnce() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->{
            for(boolean credentials:new boolean[]{false,true})for(String fault:new String[]{"H2","postgresql","nullProduct","nullMetadata"}){
                var f=new Fixture(context.getBean(JdbcRepositorySettings.class));
                if(fault.equals("nullMetadata"))when(f.connection.getMetaData()).thenReturn(null);
                else when(f.metadata.getDatabaseProductName()).thenReturn(fault.equals("nullProduct")?null:fault);
                assertThrows(SQLException.class,()->f.borrow(credentials));verify(f.connection,times(1)).close();f.verifyBorrow(credentials);verify(f.pool,never()).close();
            }
        });
    }
    @Test void metadataFailuresRetainTheirIdentityAndSuppressedCleanupFailure() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->{
            for(boolean credentials:new boolean[]{false,true})for(boolean sql:new boolean[]{false,true}){
                var f=new Fixture(context.getBean(JdbcRepositorySettings.class));var close=new SQLException("synthetic close");doThrow(close).when(f.connection).close();
                Throwable failure=sql?new SQLException("synthetic metadata"):new IllegalStateException("synthetic metadata");
                when(f.connection.getMetaData()).thenThrow(failure);
                Class<? extends Exception> expected=sql?SQLException.class:RuntimeException.class;
                var actual=assertThrows(expected,()->f.borrow(credentials));assertSame(failure,actual);assertArrayEquals(new Throwable[]{close},actual.getSuppressed());
                verify(f.connection,times(1)).close();verify(f.pool,never()).close();
            }
        });
    }
    @Test void productInspectionFailureAndErrorsAlsoReleaseTheLease() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->{
            var f=new Fixture(context.getBean(JdbcRepositorySettings.class));var failure=new SQLException("synthetic product");when(f.metadata.getDatabaseProductName()).thenThrow(failure);
            assertSame(failure,assertThrows(SQLException.class,()->f.borrow(false)));verify(f.connection,times(1)).close();
            var g=new Fixture(context.getBean(JdbcRepositorySettings.class));var error=new AssertionError("synthetic metadata");var cleanup=new AssertionError("synthetic cleanup");
            when(g.connection.getMetaData()).thenThrow(error);doThrow(cleanup).when(g.connection).close();
            assertSame(error,assertThrows(AssertionError.class,()->g.borrow(true)));assertArrayEquals(new Throwable[]{cleanup},error.getSuppressed());verify(g.connection,times(1)).close();
        });
    }
    @Test void poolBorrowFailurePropagatesWithoutClosingThePoolOrAnUnborrowedConnection() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->{
            for(boolean credentials:new boolean[]{false,true}){
                var f=new Fixture(context.getBean(JdbcRepositorySettings.class));var failure=new SQLException("synthetic borrow");
                if(credentials)when(f.pool.getConnection("synthetic-user","synthetic-password")).thenThrow(failure);else when(f.pool.getConnection()).thenThrow(failure);
                assertSame(failure,assertThrows(SQLException.class,()->f.borrow(credentials)));verify(f.connection,never()).getMetaData();verify(f.connection,never()).close();verify(f.pool,never()).close();
            }
        });
    }
    @Test void everyBorrowChecksMetadataAgainAndSelfSuppressionCannotReplaceTheOriginalFailure() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->{
            for(boolean credentials:new boolean[]{false,true}){
                var f=new Fixture(context.getBean(JdbcRepositorySettings.class));
                assertSame(f.connection,f.borrow(credentials));verify(f.connection,never()).close();
                when(f.metadata.getDatabaseProductName()).thenReturn("H2");
                assertThrows(SQLException.class,()->f.borrow(credentials));verify(f.connection,times(1)).close();
                verify(f.metadata,times(2)).getDatabaseProductName();verify(f.pool,never()).close();
                var g=new Fixture(context.getBean(JdbcRepositorySettings.class));var failure=new SQLException("synthetic shared failure");
                when(g.connection.getMetaData()).thenThrow(failure);doThrow(failure).when(g.connection).close();
                assertSame(failure,assertThrows(SQLException.class,()->g.borrow(credentials)));
                assertEquals(0,failure.getSuppressed().length);verify(g.connection,times(1)).close();verify(g.pool,never()).close();
            }
        });
    }
    private static final class Fixture {
        interface Pool extends DataSource,AutoCloseable { @Override void close(); }
        final Pool pool=mock(Pool.class);final Connection connection=mock(Connection.class);final DatabaseMetaData metadata=mock(DatabaseMetaData.class);
        final TenantAuditPostgresDataSource source;
        Fixture(JdbcRepositorySettings settings)throws SQLException {
            when(pool.getConnection()).thenReturn(connection);when(pool.getConnection("synthetic-user","synthetic-password")).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");source=new TenantAuditPostgresDataSource(pool,settings);
        }
        Connection borrow(boolean credentials)throws SQLException{return credentials?source.getConnection("synthetic-user","synthetic-password"):source.getConnection();}
        void verifyBorrow(boolean credentials)throws SQLException{
            if(credentials){verify(pool).getConnection("synthetic-user","synthetic-password");verify(pool,never()).getConnection();}
            else{verify(pool).getConnection();verify(pool,never()).getConnection(anyString(),anyString());}
        }
    }
}
