package com.mightyoung.softwarefactory.support;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.mightyoung.softwarefactory.support.RiskJdbcTransactionOwnerTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Bound datasource refusals are sticky even before JDBC operation entry. */
class RiskJdbcAliasRefusalTest {
    enum Refusal { CREDENTIAL_CONNECTION, UNWRAP }

    @ParameterizedTest @EnumSource(Refusal.class)
    void swallowedAliasRefusalCannotResumeSqlOrCommit(Refusal refusal) throws Exception {
        Fixture fixture=fixture(Fault.NONE);
        var catalog=RiskPublicationSqlCatalogTest.catalog();
        var template=fixture.owner().template(catalog,Set.of(String.class),getClass().getClassLoader());
        var operation=catalog.insertOperation();
        PreparedStatement statement=mock(PreparedStatement.class);
        doReturn(statement).when(fixture.connection()).prepareStatement(operation.sql());
        when(statement.executeUpdate()).thenReturn(1);
        var failure=failure(()->fixture.owner().executeOwned(()->{
            assertThatThrownBy(()->{
                DataSource alias=template.getDataSource();
                if(refusal==Refusal.CREDENTIAL_CONNECTION)alias.getConnection("ignored","ignored");
                else alias.unwrap(DataSource.class);
            }).isInstanceOf(SQLException.class).hasNoCause();
            return template.update(operation.sql(),UUID.randomUUID(),String.class.getName(),
                    "risk-scenario-draft-change",Timestamp.from(Instant.now()),"\"value\"");
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verify(fixture.connection(),never()).commit();
        verify(fixture.connection()).rollback();
        assertUnbound(fixture);
    }
}
