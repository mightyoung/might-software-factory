package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.identity.AuthenticationEvents;
import jakarta.servlet.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Boot4.1.2 delegating factories/targets; isolated in-memory session and deny-all security, not HUMAN/PG proof. */
class RiskScenarioHttpConfigurationTest {
    private static WebApplicationContextRunner context(){return new WebApplicationContextRunner()
            .withUserConfiguration(RiskScenarioHttpConfiguration.class,ActualTargets.class,ActualAudits.class,FaultConfiguration.class,load("org.springframework.boot.session.autoconfigure.SessionRepositoryFilterConfiguration"))
            .withConfiguration(AutoConfigurations.of(load("org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration")));}
    @Test void oneRealProfileBoundaryTransportAndRegistrationShareTheIdenticalInstance(){
        context().run(c->{assertThat(c).hasNotFailed().hasSingleBean(RiskScenarioTomcatProfile.class).hasSingleBean(RiskScenarioHttpBoundary.class).hasSingleBean(RiskScenarioHttpTransport.class);
            var boundary=c.getBean(RiskScenarioHttpBoundary.class);var registration=c.getBean("riskScenarioHttpBoundaryRegistration",FilterRegistrationBean.class);assertSame(boundary,c.getBean(RiskScenarioHttpTransport.class));assertSame(boundary,registration.getFilter());
            assertEquals(SessionRepositoryFilter.DEFAULT_ORDER-3,registration.getOrder());assertEquals(Set.of("/api/v1/entities/risk_scenario/*","/api/v1/risk-scenarios/*"),new HashSet<>(registration.getUrlPatterns()));
            assertEquals(DelegatingFilterProxyRegistrationBean.class,c.getBean("sessionRepositoryFilterRegistration").getClass());assertEquals(DelegatingFilterProxyRegistrationBean.class,c.getBean("securityFilterChainRegistration").getClass());
            assertThrows(IllegalStateException.class,c.getBean(RiskScenarioTomcatProfile.class)::getAsInt);
        });
    }
    @Test void actualServletRegistrationUsesReviewedDispatcherTypesAndOrderWithoutDisablingOtherAsyncRoutes(){
        context().run(c->{var registration=c.getBean("riskScenarioHttpBoundaryRegistration",FilterRegistrationBean.class);var servlet=mock(ServletContext.class);var dynamic=mock(FilterRegistration.Dynamic.class);var boundary=c.getBean(RiskScenarioHttpBoundary.class);
            when(servlet.addFilter("riskScenarioHttpBoundary",boundary)).thenReturn(dynamic);registration.onStartup(servlet);
            verify(dynamic).setAsyncSupported(true);verify(dynamic).addMappingForUrlPatterns(eq(EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR)),eq(false),eq("/api/v1/entities/risk_scenario/*"),eq("/api/v1/risk-scenarios/*"));
        });
    }
    @Test void safeCustomSessionOrderUsesActualBoundSessionAndSecurityRegistrations(){
        context().withPropertyValues("spring.session.servlet.filter-order=-9000").run(c->{assertThat(c).hasNotFailed();assertEquals(-9003,c.getBean("riskScenarioHttpBoundaryRegistration",FilterRegistrationBean.class).getOrder());assertEquals(-9000,c.getBean("sessionRepositoryFilterRegistration",DelegatingFilterProxyRegistrationBean.class).getOrder());});
    }
    @Test void unsafeActualSessionOrSecurityOrderIsRejectedRatherThanTrustingDerivedRootOrder(){
        context().withPropertyValues("spring.session.servlet.filter-order=0").run(c->assertThat(c).hasFailed());
        context().withPropertyValues("spring.security.filter.order="+Integer.MIN_VALUE).run(c->assertThat(c).hasFailed());
        for(String fault:List.of("session-order","security-order","audit-order"))context().withPropertyValues("risk.test.fault="+fault).run(c->assertThat(c).hasFailed());
    }
    @Test void sessionOrderUnderflowRefusesRegistration(){
        for(int value:new int[]{Integer.MIN_VALUE,Integer.MIN_VALUE+1,Integer.MIN_VALUE+2})context().withPropertyValues("spring.session.servlet.filter-order="+value).run(c->assertThat(c).hasFailed());
    }
    @Test void targetNameSpoofWrongContextDisabledOrNarrowMappingsCannotPassAsActualChain(){
        for(String fault:List.of("spoof-target","wrong-context","disabled-session","narrow-security","wrong-dispatch"))context().withPropertyValues("risk.test.fault="+fault).run(c->assertThat(c).hasFailed());
    }
    @Test void secondTransportOrDuplicateFilterRegistrationCannotHideBehindPreferredBeans(){
        context().withBean("unexpectedTransport",RiskScenarioHttpTransport.class,()->(request,response,status,headers,length,body)->{throw new java.io.IOException("unexpected test transport");}).run(c->assertThat(c).hasFailed());
        context().withUserConfiguration(DuplicateRegistration.class).run(c->assertThat(c).hasFailed());
        context().withUserConfiguration(DuplicateSecurityRegistration.class).run(c->assertThat(c).hasFailed());
    }
    @Test void missingActualAuditOrSessionRegistrationIsUnsupported(){
        new WebApplicationContextRunner().withUserConfiguration(RiskScenarioHttpConfiguration.class,ActualTargets.class,load("org.springframework.boot.session.autoconfigure.SessionRepositoryFilterConfiguration"))
                .withConfiguration(AutoConfigurations.of(load("org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration"))).run(c->assertThat(c).hasFailed());
        new WebApplicationContextRunner().withUserConfiguration(RiskScenarioHttpConfiguration.class,ActualTargets.class,ActualAudits.class)
                .withConfiguration(AutoConfigurations.of(load("org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration"))).run(c->assertThat(c).hasFailed());
    }
    @Test void nonWebContextDoesNotCreateAPretendInitializedContainer(){
        new ApplicationContextRunner().withUserConfiguration(RiskScenarioHttpConfiguration.class).run(c->{assertThat(c).hasNotFailed();assertTrue(c.getBeansOfType(RiskScenarioTomcatProfile.class).isEmpty());assertTrue(c.getBeansOfType(RiskScenarioHttpTransport.class).isEmpty());});
    }
    @Test void prefixRegistrationPassesCommandsAndUnrelatedPathsButReadRequiresServerReadiness(){
        context().run(c->{var boundary=c.getBean(RiskScenarioHttpBoundary.class);for(String path:new String[]{"/api/v1/risk-scenarios","/api/v1/risk-scenarios/id/update","/api/v1/entities/workshop/id"}){var called=new AtomicInteger();boundary.doFilter(new MockHttpServletRequest("POST",path),new MockHttpServletResponse(),(req,res)->called.incrementAndGet());assertEquals(1,called.get());}
            assertThrows(java.io.IOException.class,()->boundary.doFilter(new MockHttpServletRequest("GET","/api/v1/entities/risk_scenario/id"),new MockHttpServletResponse(),(req,res)->fail("profile is not initialized")));
        });
    }
    @Test void initialRootNameMappingsDispatcherAndAsyncSettingsMustMatchReviewedBoundary(){
        for(String fault:List.of("root-mapping","root-dispatch","root-name","root-match-after","root-async","root-disabled"))context().withPropertyValues("risk.test.fault="+fault).run(c->assertThat(c).hasFailed());
    }
    @Test void bootAutomaticFilterBeansAreCheckedAndOnlyKnownExactNeutralTypesAreAccepted(){
        context().withUserConfiguration(KnownDirectFilters.class).run(c->{assertThat(c).hasNotFailed();
            var actual=new org.springframework.boot.web.servlet.ServletContextInitializerBeans(c.getBeanFactory());
            for(String name:List.of("testEncoding","testForm","testRequestContext")){Filter filter=c.getBean(name,Filter.class);var found=new java.util.ArrayList<FilterRegistrationBean<?>>();for(var initializer:actual)if(initializer instanceof FilterRegistrationBean<?> registration&&registration.getFilter()==filter)found.add(registration);assertEquals(1,found.size());assertEquals(((org.springframework.core.Ordered)filter).getOrder(),found.getFirst().getOrder());}
        });
        context().withBean("unknownDirectFilter",Filter.class,()->(request,response,chain)->chain.doFilter(request,response)).run(c->assertThat(c).hasFailed());
        context().withUserConfiguration(UnknownEarlyRegistration.class).run(c->assertThat(c).hasFailed());
    }
    private static Class<?> load(String name){try{return Class.forName(name);}catch(ClassNotFoundException failure){throw new AssertionError("Required Boot4.1.2 class is absent",failure);}}
    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity static class ActualTargets {
        @Bean SessionRepositoryFilter<MapSession> springSessionRepositoryFilter(){return new SessionRepositoryFilter<>(new MapSessionRepository(new ConcurrentHashMap<>()));}
        @Bean SecurityFilterChain actualDenyAllSecurity(HttpSecurity http)throws Exception{http.authorizeHttpRequests(authorize->authorize.anyRequest().denyAll());return http.build();}
    }
    @Configuration(proxyBeanMethods=false) static class ActualAudits {
        @Bean FilterRegistrationBean<Filter> loginAuditBoundary(Environment environment)throws Exception{return audit("LoginAuditFilter",2,environment);}
        @Bean FilterRegistrationBean<Filter> loginSessionPersistenceFailureBoundary(Environment environment)throws Exception{return audit("LoginSessionPersistenceFailureFilter",1,environment);}
        @Bean FilterRegistrationBean<Filter> lifecycleSessionPersistenceFailureBoundary(Environment environment)throws Exception{return audit("IdentityLifecycleSessionPersistenceFilter",1,environment);}
        private static FilterRegistrationBean<Filter> audit(String name,int offset,Environment environment)throws Exception{
            Class<?> type=load("com.mightyoung.softwarefactory.identity."+name);java.lang.reflect.Constructor<?> constructor;
            Object[] arguments;if(name.equals("LoginAuditFilter")){constructor=type.getDeclaredConstructor(AuthenticationEvents.class,Clock.class,int.class);arguments=new Object[]{mock(AuthenticationEvents.class),Clock.systemUTC(),8};}else{constructor=type.getDeclaredConstructor();arguments=new Object[0];}
            assertTrue(constructor.trySetAccessible());var registration=new FilterRegistrationBean<>((Filter)constructor.newInstance(arguments));registration.setOrder(environment.getProperty("spring.session.servlet.filter-order",Integer.class,SessionRepositoryFilter.DEFAULT_ORDER)-offset);registration.setDispatcherTypes(DispatcherType.REQUEST);return registration;
        }
    }
    @Configuration(proxyBeanMethods=false) static class DuplicateRegistration {
        @Bean FilterRegistrationBean<RiskScenarioHttpBoundary> duplicateRiskBoundaryRegistration(RiskScenarioHttpBoundary boundary){return new FilterRegistrationBean<>(boundary);}
    }
    @Configuration(proxyBeanMethods=false) static class DuplicateSecurityRegistration {
        @Bean DelegatingFilterProxyRegistrationBean duplicateSecurityRegistration(){var bean=new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");bean.setOrder(-100);return bean;}
    }
    @Configuration(proxyBeanMethods=false) static class KnownDirectFilters {
        @Bean Filter testEncoding()throws Exception{return (Filter)load("org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter").getConstructor().newInstance();}
        @Bean Filter testForm()throws Exception{return (Filter)load("org.springframework.boot.servlet.filter.OrderedFormContentFilter").getConstructor().newInstance();}
        @Bean Filter testRequestContext()throws Exception{return (Filter)load("org.springframework.boot.servlet.filter.OrderedRequestContextFilter").getConstructor().newInstance();}
    }
    @Configuration(proxyBeanMethods=false) static class UnknownEarlyRegistration {
        @Bean FilterRegistrationBean<Filter> unknownEarly(){var registration=new FilterRegistrationBean<Filter>((request,response,chain)->chain.doFilter(request,response));registration.setOrder(Integer.MIN_VALUE);return registration;}
    }
    @Configuration(proxyBeanMethods=false) static class FaultConfiguration {
        @Bean static BeanPostProcessor actualRegistrationFault(Environment environment){return new Fault(environment.getProperty("risk.test.fault",""));}
    }
    private static final class Fault implements BeanPostProcessor,ApplicationContextAware {
        private final String mode;private ApplicationContext context;Fault(String mode){this.mode=mode;}
        @Override public void setApplicationContext(ApplicationContext context)throws BeansException{this.context=context;}
        @Override public Object postProcessAfterInitialization(Object bean,String name)throws BeansException{
            if(name.equals("securityFilterChainRegistration")&&bean instanceof DelegatingFilterProxyRegistrationBean security){
                if(mode.equals("spoof-target")){var wrong=new DelegatingFilterProxyRegistrationBean("decoyTarget");wrong.setName("springSecurityFilterChain");wrong.setApplicationContext(context);wrong.setOrder(security.getOrder());return wrong;}
                if(mode.equals("wrong-context"))security.setApplicationContext(new StaticWebApplicationContext());
                if(mode.equals("security-order"))security.setOrder(Integer.MIN_VALUE);if(mode.equals("narrow-security"))security.setUrlPatterns(List.of("/elsewhere/*"));if(mode.equals("wrong-dispatch"))security.setDispatcherTypes(DispatcherType.ERROR);
            }
            if(name.equals("sessionRepositoryFilterRegistration")&&bean instanceof DelegatingFilterProxyRegistrationBean session){if(mode.equals("session-order"))session.setOrder(0);if(mode.equals("disabled-session"))session.setEnabled(false);}
            if(name.equals("loginAuditBoundary")&&bean instanceof FilterRegistrationBean<?> audit&&mode.equals("audit-order"))audit.setOrder(Integer.MIN_VALUE);
            if(name.equals("riskScenarioHttpBoundaryRegistration")&&bean instanceof FilterRegistrationBean<?> root){
                switch(mode){case "root-mapping"->root.setUrlPatterns(List.of("/not-risk/*"));case "root-dispatch"->root.setDispatcherTypes(DispatcherType.ERROR);case "root-name"->root.setName("wrong");case "root-match-after"->root.setMatchAfter(true);case "root-async"->root.setAsyncSupported(false);case "root-disabled"->root.setEnabled(false);default->{}}
            }
            return bean;
        }
    }
}
