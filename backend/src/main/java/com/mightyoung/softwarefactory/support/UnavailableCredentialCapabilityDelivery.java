package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.identity.CredentialCapabilityDeliveryPort;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.DeliveryOutcome;
import com.mightyoung.softwarefactory.identity.IdentityLifecycleException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** An unsupported/missing provider never produces a delivery-success assertion. */
@Configuration(proxyBeanMethods=false)
public class UnavailableCredentialCapabilityDelivery {
    @Bean @ConditionalOnMissingBean(CredentialCapabilityDeliveryPort.class)
    CredentialCapabilityDeliveryPort credentialCapabilityDeliveryPort() {
        return new CredentialCapabilityDeliveryPort() {
            @Override public Readiness readiness(){throw new IdentityLifecycleException(IdentityLifecycleException.Reason.UNAVAILABLE);}
            @Override public DeliveryOutcome dispatch(PreparedDelivery delivery,DispatchBudget budget){throw new IdentityLifecycleException(IdentityLifecycleException.Reason.UNAVAILABLE);}
        };
    }
}
