package com.mightyoung.softwarefactory.tenancy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantLifecycleStateTest {
    @Test void bothPersistedStatesAndZeroEpochAreValidFactsWithoutHumanMembership(){
        var tenant=UUID.randomUUID();for(String status:new String[]{"ACTIVE","SUSPENDED"}){
            var state=new TenantLifecycleState(tenant,status,0);assertThat(state.tenantId()).isEqualTo(tenant);assertThat(state.status()).isEqualTo(status);assertThat(state.authEpoch()).isZero();
        }
        assertThat(new TenantLifecycleState(tenant,"ACTIVE",Long.MAX_VALUE).authEpoch()).isEqualTo(Long.MAX_VALUE);
    }
    @Test void missingIdentityUnknownStatusAndNegativeEpochAreRejected(){
        assertThatThrownBy(()->new TenantLifecycleState(null,"ACTIVE",0)).isExactlyInstanceOf(IllegalArgumentException.class);
        for(String status:new String[]{null,"","active","DISABLED","UNKNOWN"})assertThatThrownBy(()->new TenantLifecycleState(UUID.randomUUID(),status,0)).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TenantLifecycleState(UUID.randomUUID(),"ACTIVE",-1)).isExactlyInstanceOf(IllegalArgumentException.class);
    }
}
