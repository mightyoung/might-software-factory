package com.mightyoung.softwarefactory.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Clock;

/** No key or provider fallback. Enabling source alone cannot provision lifecycle resources. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="software-factory.identity.lifecycle",name="enabled",havingValue="true")
@EnableConfigurationProperties(IdentityLifecycleProperties.class)
class IdentityLifecycleConfiguration {
    @Bean(destroyMethod="close")
    IdentityLifecycleService identityLifecycleService(IdentityLifecycleStore store,IdentityLifecycleSecurityTransaction writer,
            IdentityLifecycleAuthorization authorization,InvitationMembershipPort memberships,IdentityLifecycleAudit audit,
            IdentityLifecycleSession sessions,CredentialCapabilityDeliveryPort delivery,IdentityLifecycleProperties policy,
            PasswordEncoder passwords,ObjectProvider<Clock> clocks) {
        return new IdentityLifecycleService(store,writer,authorization,memberships,audit,sessions,delivery,policy,passwords,clocks.getIfAvailable(Clock::systemUTC));
    }
    @Bean(destroyMethod="close")
    IdentityCapabilityDeadlineObserver identityCapabilityDeadlineObserver(IdentityLifecycleService service) {
        return new IdentityCapabilityDeadlineObserver(service);
    }
}
