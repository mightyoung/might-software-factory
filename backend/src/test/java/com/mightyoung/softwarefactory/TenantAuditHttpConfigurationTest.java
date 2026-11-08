package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.*;
import com.mightyoung.softwarefactory.identity.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Root composition and live topology sampling only; no initialized container, DB, key material or HUMAN proof. */
class TenantAuditHttpConfigurationTest {
    private static final String FLAG="msf.audit-query.http.enabled";
    private static final Set<String> RISK=Set.of("/api/v1/entities/risk_scenario/*","/api/v1/risk-scenarios/*");
    private static WebApplicationContextRunner base(){return new WebApplicationContextRunner()
            .withUserConfiguration(RiskScenarioHttpConfiguration.class,TenantAuditQueryController.class,
                    RiskScenarioHttpConfigurationTest.ActualTargets.class,RiskScenarioHttpConfigurationTest.ActualAudits.class,
                    load("org.springframework.boot.session.autoconfigure.SessionRepositoryFilterConfiguration"))
            .withConfiguration(AutoConfigurations.of(load("org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration")));}
    private static WebApplicationContextRunner enabled(){return base().withPropertyValues(FLAG+"=true")
            .withBean("canonicalDataSource",DataSource.class,()->mock(DataSource.class))
            .withBean("canonicalKeys",SessionSensitiveKeyProvider.class,()->mock(SessionSensitiveKeyProvider.class))
            .withBean("canonicalSettings",JdbcRepositorySettings.class,()->mock(JdbcRepositorySettings.class))
            .withBean("canonicalRequests",SessionSensitiveRequests.class,()->new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)));}
    @Test void defaultAndExplicitFalseKeepTheOldTwoPatternsAndHaveNoAuditGraphOrDependencies(){
        for(String flag:List.of("missing","false","FALSE")){var runner=base();if(!flag.equals("missing"))runner=runner.withPropertyValues(FLAG+"="+flag);
            runner.run(context->{assertThat(context).hasNotFailed();assertThat(patterns(context)).isEqualTo(RISK);
                for(String name:List.of("tenantAuditQueryGraph","tenantAuditQueryService","tenantAuditQueryParser","tenantAuditHttpTransport"))assertThat(context.containsBean(name)).isFalse();
                assertThat(context.getBeansOfType(TenantAuditQueryController.class)).isEmpty();assertThat(context.getBeansOfType(TenantAuditQueryService.class)).isEmpty();assertThat(context.getBeansOfType(TenantAuditQueryParser.class)).isEmpty();assertThat(context.getBeansOfType(TenantAuditHttpTransport.class)).isEmpty();
                assertThat(context.getBeansOfType(DataSource.class)).isEmpty();assertThat(context.getBeansOfType(SessionSensitiveKeyProvider.class)).isEmpty();sample(context).run();
            });
        }
    }
    @Test void onlyCaseInsensitiveTrueAndFalseAreAccepted(){
        enabled().withPropertyValues(FLAG+"=TRUE").run(context->assertThat(context).hasNotFailed());
        for(String value:List.of("yes","on","1","0",""," true ","unknown"))base().withInitializer(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rawFlag",Map.of(FLAG,value)))).run(context->assertThat(context).hasFailed());
    }
    @Test void enabledExportsOnlyCanonicalGraphObjectsAndNoGlobalWrapperOrExtraBoundary()throws Exception {
        enabled().run(context->{assertThat(context).hasNotFailed();var urls=new HashSet<>(RISK);urls.add("/api/v1/audit-events/query");assertThat(patterns(context)).isEqualTo(urls);
            assertThat(context.getBeansOfType(RiskScenarioHttpBoundary.class)).hasSize(1);assertThat(context.getBeansOfType(TenantAuditHttpTransport.class)).hasSize(1);
            var boundary=context.getBean(RiskScenarioHttpBoundary.class);var registration=context.getBean("riskScenarioHttpBoundaryRegistration",FilterRegistrationBean.class);assertThat(registration.getFilter()).isSameAs(boundary);
            var graph=context.getBean("tenantAuditQueryGraph");var service=context.getBean(TenantAuditQueryService.class);var parser=context.getBean(TenantAuditQueryParser.class);var requests=context.getBean(SessionSensitiveRequests.class);var transport=context.getBean(TenantAuditHttpTransport.class);
            assertThat(graphValue(graph,"service")).isSameAs(service);assertThat(graphValue(graph,"parser")).isSameAs(parser);assertThat(graphValue(graph,"transport")).isSameAs(transport);
            assertThat(graphValue(graph,"original")).isSameAs(context.getBean(DataSource.class));assertThat(graphValue(graph,"keys")).isSameAs(context.getBean(SessionSensitiveKeyProvider.class));
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);assertThat(context.getBeansOfType(TenantAuditPostgresDataSource.class)).isEmpty();assertThat(graphValue(graph,"source")).isInstanceOf(TenantAuditPostgresDataSource.class);
            assertThat(context.getBean(TenantAuditQueryController.class).usesDependencies(service,parser,requests,transport)).isTrue();
            sample(context).run();verifyNoInteractions(context.getBean(DataSource.class),context.getBean(SessionSensitiveKeyProvider.class),context.getBean(JdbcRepositorySettings.class));
        });
    }
    @Test void everyEnabledDependencyIsRequiredAndPrimaryCannotHideDuplicates(){
        base().withPropertyValues(FLAG+"=true").run(context->assertThat(context).hasFailed());
        for(String missing:List.of("source","keys","settings","requests")){
            var runner=base().withPropertyValues(FLAG+"=true");
            if(!missing.equals("source"))runner=runner.withBean(DataSource.class,()->mock(DataSource.class));if(!missing.equals("keys"))runner=runner.withBean(SessionSensitiveKeyProvider.class,()->mock(SessionSensitiveKeyProvider.class));
            if(!missing.equals("settings"))runner=runner.withBean(JdbcRepositorySettings.class,()->mock(JdbcRepositorySettings.class));if(!missing.equals("requests"))runner=runner.withBean(SessionSensitiveRequests.class,()->new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)));
            runner.run(context->assertThat(context).hasFailed());
        }
        enabled().withBean("extraSource",DataSource.class,()->mock(DataSource.class),definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
        enabled().withBean("extraKeys",SessionSensitiveKeyProvider.class,()->mock(SessionSensitiveKeyProvider.class),definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
        enabled().withBean("extraSettings",JdbcRepositorySettings.class,()->mock(JdbcRepositorySettings.class),definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
        enabled().withBean("extraRequests",SessionSensitiveRequests.class,()->new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)),definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
        enabled().withBean("extraParser",TenantAuditQueryParser.class,TenantAuditQueryParser::new,definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
        enabled().withBean("extraService",TenantAuditQueryService.class,()->mock(TenantAuditQueryService.class),definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
        enabled().withBean("extraTransport",TenantAuditHttpTransport.class,()->(a,b,c,d,e,f)->{},definition->definition.setPrimary(true)).run(context->assertThat(context).hasFailed());
    }
    @Test void disabledModeRejectsStrayAuditComponentsInsteadOfPublishingAPartialGraph(){
        base().withBean("strayParser",TenantAuditQueryParser.class,TenantAuditQueryParser::new).run(context->assertThat(context).hasFailed());
        base().withBean("strayService",TenantAuditQueryService.class,()->mock(TenantAuditQueryService.class)).run(context->assertThat(context).hasFailed());
        base().withBean("strayTransport",TenantAuditHttpTransport.class,()->(a,b,c,d,e,f)->{}).run(context->assertThat(context).hasFailed());
    }
    @Test void postProcessedGraphComponentsAndControllerSubstitutionAreRejected(){
        for(String fault:List.of("service","parser","transport","graph","controller","boundary"))enabled().withPropertyValues("audit.config.test.fault="+fault)
                .withUserConfiguration(Substitutions.class).run(context->assertThat(context).hasFailed());
    }
    @Test void everyTopologySampleRechecksFlagUrlsAndCanonicalComponentUniqueness(){
        enabled().run(context->{assertThat(context).hasNotFailed();var sample=sample(context);sample.run();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("drift",Map.of(FLAG,"false")));
            assertThatThrownBy(sample::run).isInstanceOf(IllegalStateException.class);
        });
        enabled().run(context->{assertThat(context).hasNotFailed();var sample=sample(context);var registration=context.getBean("riskScenarioHttpBoundaryRegistration",FilterRegistrationBean.class);
            registration.setUrlPatterns(RISK);assertThatThrownBy(sample::run).isInstanceOf(IllegalStateException.class);
        });
        for(String extra:List.of("source","keys","settings","requests","service","parser","transport","controller","graph","boundary","riskPort"))enabled().run(context->{
            assertThat(context).hasNotFailed();var callback=sample(context);Object value=switch(extra){
                case "source"->mock(DataSource.class);case "keys"->mock(SessionSensitiveKeyProvider.class);case "settings"->mock(JdbcRepositorySettings.class);
                case "requests"->new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12));case "service"->mock(TenantAuditQueryService.class);case "parser"->new TenantAuditQueryParser();
                case "boundary"->new RiskScenarioHttpBoundary(0,true);case "riskPort"->(com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport)(a,b,c,d,e,f)->{};
                case "transport"->(TenantAuditHttpTransport)(a,b,c,d,e,f)->{};case "controller"->context.getBean(TenantAuditQueryController.class);default->context.getBean("tenantAuditQueryGraph");};
            context.getBeanFactory().registerSingleton("lateExtra",value);assertThatThrownBy(callback::run).isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void additionalEarlierOrSameOrderFilterIsStillRejectedWithAuditEnabled(){
        enabled().withUserConfiguration(RiskScenarioHttpConfigurationTest.UnknownEarlyRegistration.class).run(context->assertThat(context).hasFailed());
        enabled().withUserConfiguration(SameOrder.class).run(context->assertThat(context).hasFailed());
    }
    private static Set<String> patterns(org.springframework.context.ApplicationContext context){return new HashSet<>(context.getBean("riskScenarioHttpBoundaryRegistration",FilterRegistrationBean.class).getUrlPatterns());}
    private static Runnable sample(org.springframework.context.ApplicationContext context){
        try{var field=RiskScenarioTomcatProfile.class.getDeclaredField("filterProfile");assertThat(field.trySetAccessible()).isTrue();return (Runnable)field.get(context.getBean(RiskScenarioTomcatProfile.class));}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static Object graphValue(Object graph,String name){try{var accessor=graph.getClass().getDeclaredMethod(name);assertThat(accessor.trySetAccessible()).isTrue();return accessor.invoke(graph);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    private static Class<?> load(String name){try{return Class.forName(name);}catch(ClassNotFoundException failure){throw new AssertionError(failure);}}
    @Configuration(proxyBeanMethods=false) static class SameOrder {
        @Bean FilterRegistrationBean<jakarta.servlet.Filter> auditPeer(){var value=new FilterRegistrationBean<jakarta.servlet.Filter>((a,b,c)->c.doFilter(a,b));value.setOrder(org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER-3);value.setUrlPatterns(List.of("/unrelated/*"));return value;}
    }
    @Configuration(proxyBeanMethods=false) static class Substitutions {
        @Bean static BeanPostProcessor substitute(org.springframework.core.env.Environment environment){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){String mode=environment.getProperty("audit.config.test.fault","");
                if(mode.equals("boundary")&&name.equals("riskScenarioHttpBoundary"))return new RiskScenarioHttpBoundary(()->0,false);
                if(mode.equals("service")&&name.equals("tenantAuditQueryService"))return mock(TenantAuditQueryService.class);
                if(mode.equals("parser")&&name.equals("tenantAuditQueryParser"))return new TenantAuditQueryParser();
                if(mode.equals("transport")&&name.equals("tenantAuditHttpTransport"))return (TenantAuditHttpTransport)(a,b,c,d,e,f)->{};
                if(mode.equals("graph")&&name.equals("tenantAuditQueryGraph"))return cloneGraph(bean);
                if(mode.equals("controller")&&bean instanceof TenantAuditQueryController)return mock(TenantAuditQueryController.class);
                return bean;
            }
        };}
        private static Object cloneGraph(Object graph){try{var components=graph.getClass().getRecordComponents();var types=Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);var constructor=graph.getClass().getDeclaredConstructor(types);assertThat(constructor.trySetAccessible()).isTrue();Object[] values=new Object[components.length];for(int i=0;i<values.length;i++)values[i]=graphValue(graph,components[i].getName());return constructor.newInstance(values);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    }
}
