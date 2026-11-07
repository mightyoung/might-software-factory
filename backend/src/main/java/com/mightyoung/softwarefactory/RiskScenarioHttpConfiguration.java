package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.RiskScenarioHttpTransport;
import com.mightyoung.softwarefactory.auditops.TenantAuditHttpTransport;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryController;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryFactory;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryParser;
import com.mightyoung.softwarefactory.auditops.TenantAuditQueryService;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import javax.sql.DataSource;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.DispatcherType;
import java.util.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import jakarta.servlet.Filter;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.session.web.http.SessionRepositoryFilter;

/** Project-specific outer read boundary: before reviewed session/security response writers, on a pristine response.
 * Existing native headers/body/hidden locale history are not a generic compatibility surface. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class RiskScenarioHttpConfiguration {
    private static final String AUDIT_FLAG="msf.audit-query.http.enabled";
    private static final String AUDIT_PATH="/api/v1/audit-events/query";
    private Boolean frozenAuditFlag;
    private AuditGraph canonicalAuditGraph;
    private RiskScenarioHttpBoundary canonicalBoundary;
    @Bean RiskScenarioTomcatProfile riskScenarioTomcatProfile(ApplicationContext context){return new RiskScenarioTomcatProfile(context);}
    @Bean RiskScenarioHttpBoundary riskScenarioHttpBoundary(RiskScenarioTomcatProfile profile,Environment environment){
        if(canonicalBoundary!=null)throw auditFailure();
        canonicalBoundary=new RiskScenarioHttpBoundary(profile,auditEnabled(environment));return canonicalBoundary;
    }
    @Bean FilterRegistrationBean<RiskScenarioHttpBoundary> riskScenarioHttpBoundaryRegistration(RiskScenarioHttpBoundary boundary,Environment environment){
        int sessionOrder=environment.getProperty("spring.session.servlet.filter-order",Integer.class,SessionRepositoryFilter.DEFAULT_ORDER);
        if(sessionOrder<Integer.MIN_VALUE+3)throw new IllegalStateException("Session filter order must leave room for the risk response boundary");
        var registration=new FilterRegistrationBean<>(boundary);registration.setName("riskScenarioHttpBoundary");registration.setOrder(sessionOrder-3);
        registration.setUrlPatterns(patterns(auditEnabled(environment)));
        registration.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR);
        // Prefix registration must not disable unrelated routes; the seven matching read requests forbid async themselves.
        registration.setAsyncSupported(true);registration.setMatchAfter(false);return registration;
    }
    @Bean SmartInitializingSingleton riskScenarioHttpBoundaryInvariant(RiskScenarioHttpBoundary expected,
            FilterRegistrationBean<RiskScenarioHttpBoundary> riskScenarioHttpBoundaryRegistration,
            RiskScenarioTomcatProfile profile,ApplicationContext context,
            ObjectProvider<RiskScenarioHttpTransport> transports,ObjectProvider<RiskScenarioHttpBoundary> boundaries,
            ObjectProvider<FilterRegistrationBean<?>> registrations){
        return ()->{var ports=transports.stream().toList();var instances=boundaries.stream().toList();
            long matches=registrations.stream().filter(value->value.getFilter()==expected).count();
            if(ports.size()!=1||ports.getFirst()!=expected||instances.size()!=1||instances.getFirst()!=expected||matches!=1
                    ||riskScenarioHttpBoundaryRegistration.getFilter()!=expected||!riskScenarioHttpBoundaryRegistration.isEnabled())
                throw new IllegalStateException("One identical risk HTTP transport, boundary and explicit registration is required");
            var topology=new FilterTopology(context,expected,riskScenarioHttpBoundaryRegistration,this,auditEnabled(context.getEnvironment()));profile.bindFilterProfile(topology);
        };
    }


    @Bean @ConditionalOnProperty(prefix="msf.audit-query.http",name="enabled",havingValue="true",matchIfMissing=false)
    AuditGraph tenantAuditQueryGraph(ApplicationContext context,RiskScenarioHttpBoundary boundary){
        if(!auditEnabled(context.getEnvironment())||canonicalAuditGraph!=null)throw auditFailure();
        var original=unique(context,DataSource.class);var keys=unique(context,SessionSensitiveKeyProvider.class);
        var settings=unique(context,JdbcRepositorySettings.class);var requests=unique(context,SessionSensitiveRequests.class);
        var source=new TenantAuditPostgresDataSource(original,settings);
        var graph=new AuditGraph(original,keys,settings,requests,source,TenantAuditQueryFactory.create(source,keys),
                new TenantAuditQueryParser(),new AuditTransport(boundary));
        canonicalAuditGraph=graph;return graph;
    }
    @Bean @ConditionalOnProperty(prefix="msf.audit-query.http",name="enabled",havingValue="true",matchIfMissing=false)
    TenantAuditQueryService tenantAuditQueryService(AuditGraph graph){return graph.service();}
    @Bean @ConditionalOnProperty(prefix="msf.audit-query.http",name="enabled",havingValue="true",matchIfMissing=false)
    TenantAuditQueryParser tenantAuditQueryParser(AuditGraph graph){return graph.parser();}
    @Bean @ConditionalOnProperty(prefix="msf.audit-query.http",name="enabled",havingValue="true",matchIfMissing=false)
    TenantAuditHttpTransport tenantAuditHttpTransport(AuditGraph graph){return graph.transport();}

    private synchronized boolean auditEnabled(Environment environment){
        String configured=environment.getProperty(AUDIT_FLAG);boolean value;
        if(configured==null||configured.equalsIgnoreCase("false"))value=false;
        else if(configured.equalsIgnoreCase("true"))value=true;
        else throw auditFailure();
        if(frozenAuditFlag!=null&&frozenAuditFlag!=value)throw auditFailure();
        frozenAuditFlag=value;return value;
    }
    private static List<String> patterns(boolean audit){return audit?List.of("/api/v1/entities/risk_scenario/*","/api/v1/risk-scenarios/*",AUDIT_PATH):List.of("/api/v1/entities/risk_scenario/*","/api/v1/risk-scenarios/*");}
    private static <T> T unique(ApplicationContext context,Class<T> type){
        var beans=context.getBeansOfType(type);if(beans.size()!=1)throw auditFailure();return beans.values().iterator().next();
    }
    private static void identical(ApplicationContext context,Class<?> type,Object expected){if(unique(context,type)!=expected)throw auditFailure();}
    private static IllegalStateException auditFailure(){return new IllegalStateException("Tenant audit HTTP requires the canonical explicitly enabled query graph");}
    private record AuditGraph(DataSource original,SessionSensitiveKeyProvider keys,JdbcRepositorySettings settings,
            SessionSensitiveRequests requests,TenantAuditPostgresDataSource source,TenantAuditQueryService service,
            TenantAuditQueryParser parser,AuditTransport transport) { }
    private static final class AuditTransport implements TenantAuditHttpTransport {
        private final RiskScenarioHttpBoundary boundary;
        AuditTransport(RiskScenarioHttpBoundary boundary){this.boundary=Objects.requireNonNull(boundary);}
        @Override public void stage(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,int status,
                Map<String,List<String>> headers,int length,BodyWriter body)throws java.io.IOException{boundary.stageAudit(request,response,status,headers,length,body);}
    }
    private List<Object> auditState(ApplicationContext context,RiskScenarioHttpBoundary boundary,boolean expected){
        if(auditEnabled(context.getEnvironment())!=expected)throw auditFailure();
        if(!expected){
            if(canonicalAuditGraph!=null||!context.getBeansOfType(AuditGraph.class).isEmpty()||!context.getBeansOfType(TenantAuditQueryService.class).isEmpty()
                    ||!context.getBeansOfType(TenantAuditQueryParser.class).isEmpty()||!context.getBeansOfType(TenantAuditHttpTransport.class).isEmpty()
                    ||!context.getBeansOfType(TenantAuditQueryController.class).isEmpty())throw auditFailure();
            return List.of();
        }
        var graph=canonicalAuditGraph;if(graph==null)throw auditFailure();
        identical(context,AuditGraph.class,graph);identical(context,DataSource.class,graph.original());
        identical(context,SessionSensitiveKeyProvider.class,graph.keys());identical(context,JdbcRepositorySettings.class,graph.settings());
        identical(context,SessionSensitiveRequests.class,graph.requests());identical(context,TenantAuditQueryService.class,graph.service());
        identical(context,TenantAuditQueryParser.class,graph.parser());identical(context,TenantAuditHttpTransport.class,graph.transport());
        if(graph.transport().boundary!=boundary)throw auditFailure();
        var controller=unique(context,TenantAuditQueryController.class);if(controller.getClass()!=TenantAuditQueryController.class
                ||!controller.usesDependencies(graph.service(),graph.parser(),graph.requests(),graph.transport()))throw auditFailure();
        return List.of(graph,graph.original(),graph.keys(),graph.settings(),graph.requests(),graph.source(),graph.service(),graph.parser(),graph.transport(),controller);
    }

    /** Read-only Boot4.1.2 SDK inspection. No field mutation, target rebinding or replacement registration. */
    private static final class FilterTopology implements Runnable {
        private static final String FAILURE="Risk HTTP requires the reviewed actual session/security filter topology";
        private static final Map<String,String> AUDITS=Map.of("loginAuditBoundary","com.mightyoung.softwarefactory.identity.LoginAuditFilter",
                "loginSessionPersistenceFailureBoundary","com.mightyoung.softwarefactory.identity.LoginSessionPersistenceFailureFilter",
                "lifecycleSessionPersistenceFailureBoundary","com.mightyoung.softwarefactory.identity.IdentityLifecycleSessionPersistenceFilter");
        private static final Set<String> BEFORE_NEUTRAL=Set.of("org.springframework.web.filter.CharacterEncodingFilter","org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter",
                "org.springframework.web.filter.FormContentFilter","org.springframework.boot.servlet.filter.OrderedFormContentFilter");
        private static final Set<String> DIRECT_NEUTRAL=Set.of("org.springframework.web.filter.CharacterEncodingFilter","org.springframework.boot.servlet.filter.OrderedCharacterEncodingFilter",
                "org.springframework.web.filter.FormContentFilter","org.springframework.boot.servlet.filter.OrderedFormContentFilter",
                "org.springframework.web.filter.RequestContextFilter","org.springframework.boot.servlet.filter.OrderedRequestContextFilter");
        private final ApplicationContext context;private final RiskScenarioHttpBoundary boundary;private final FilterRegistrationBean<RiskScenarioHttpBoundary> root;
        private final RiskScenarioHttpConfiguration owner;private final boolean audit;
        private final List<State> admitted;
        FilterTopology(ApplicationContext context,RiskScenarioHttpBoundary boundary,FilterRegistrationBean<RiskScenarioHttpBoundary> root,RiskScenarioHttpConfiguration owner,boolean audit){this.context=context;this.boundary=boundary;this.root=root;this.owner=owner;this.audit=audit;admitted=sample();}
        @Override public void run(){if(!admitted.equals(sample()))throw fail();}
        private List<State> sample(){
            if(!"4.1.2".equals(SpringBootVersion.getVersion()))throw fail();
            if(boundary!=owner.canonicalBoundary)throw fail();
            identical(context,RiskScenarioHttpBoundary.class,boundary);identical(context,RiskScenarioHttpTransport.class,boundary);
            var auditObjects=owner.auditState(context,boundary,audit);
            if(root.getClass()!=FilterRegistrationBean.class||root.getFilter()!=boundary||!root.isEnabled()||!root.isAsyncSupported()||root.isMatchAfter()
                    ||!root.getFilterName().equals("riskScenarioHttpBoundary")||!root.getServletNames().isEmpty()||!root.getServletRegistrationBeans().isEmpty()
                    ||root.getUrlPatterns().size()!=patterns(audit).size()||!new HashSet<>(root.getUrlPatterns()).equals(new HashSet<>(patterns(audit)))
                    ||!root.determineDispatcherTypes().equals(EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR)))throw fail();
            var sessions=context.getBeansOfType(SessionRepositoryFilter.class);if(sessions.size()!=1)throw fail();String sessionName=sessions.keySet().iterator().next();Object session=sessions.get(sessionName);
            Object security=context.getBean("springSecurityFilterChain");if(!(security instanceof FilterChainProxy)||context.getBeansOfType(FilterChainProxy.class).size()!=1)throw fail();
            var all=context.getBeansOfType(AbstractFilterRegistrationBean.class);var states=new ArrayList<State>();var targets=new HashMap<String,DelegatingFilterProxyRegistrationBean>();
            Set<Object> consumed=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var entry:all.entrySet()){
                var registration=entry.getValue();String targetName=null;Object target;
                if(registration instanceof DelegatingFilterProxyRegistrationBean delegated){
                    if(delegated.getClass()!=DelegatingFilterProxyRegistrationBean.class)throw fail();targetName=target(delegated);
                    if(!targetName.equals(sessionName)&&!targetName.equals("springSecurityFilterChain"))throw fail();
                    if(targets.put(targetName,delegated)!=null||!registration.isEnabled())throw fail();
                    target=context.getBean(targetName);if(targetName.equals(sessionName)?target!=session:target!=security)throw fail();
                    // Boot4.1.2: Session defaults to REQUEST/ASYNC/ERROR; Security deliberately defaults to all five dispatchers.
                    global(registration);var expectedDispatchers=targetName.equals(sessionName)?EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR):EnumSet.allOf(DispatcherType.class);
                    if(!registration.determineDispatcherTypes().equals(expectedDispatchers))throw fail();
                }else target=registration.getFilter();
                if(target==null)throw fail();consumed.add(target);
                states.add(new State(entry.getKey(),registration,target,targetName,registration.getOrder(),registration.isEnabled(),registration.getFilterName(),
                        new TreeSet<>(registration.getUrlPatterns()).toString(),new TreeSet<>(registration.getServletNames()).toString(),List.copyOf(registration.getServletRegistrationBeans()),registration.determineDispatcherTypes().toString(),registration.isMatchAfter(),registration.isAsyncSupported()));
                if(registration!=root&&registration.isEnabled()&&registration.getOrder()<=root.getOrder()&&!BEFORE_NEUTRAL.contains(target.getClass().getName()))throw fail();
            }
            var sessionRegistration=targets.get(sessionName);var securityRegistration=targets.get("springSecurityFilterChain");
            if(sessionRegistration==null||securityRegistration==null||root.getFilter()!=boundary||!root.isEnabled()
                    ||!(root.getOrder()<sessionRegistration.getOrder()&&sessionRegistration.getOrder()<securityRegistration.getOrder())
                    ||sessionRegistration.getOrder()<Integer.MIN_VALUE+3||root.getOrder()!=sessionRegistration.getOrder()-3)throw fail();
            for(var required:AUDITS.entrySet()){
                var registration=all.get(required.getKey());if(!(registration instanceof FilterRegistrationBean<?> actual)||!actual.isEnabled()
                        ||!actual.getFilter().getClass().getName().equals(required.getValue()))throw fail();
                global(actual);if(!actual.determineDispatcherTypes().equals(EnumSet.of(DispatcherType.REQUEST)))throw fail();
                int offset=required.getKey().equals("loginAuditBoundary")?2:1;if(actual.getOrder()!=sessionRegistration.getOrder()-offset)throw fail();
                long count=states.stream().filter(state->state.target().getClass().getName().equals(required.getValue())).count();if(count!=1)throw fail();
            }
            // Boot adapts direct Filter beans not consumed by a registration/Delegating target. Never ignore this path.
            for(var entry:context.getBeansOfType(Filter.class).entrySet()){Filter filter=entry.getValue();if(consumed.contains(filter))continue;
                if(!DIRECT_NEUTRAL.contains(filter.getClass().getName()))throw fail();
                int order=filter instanceof Ordered ordered?ordered.getOrder():OrderUtils.getOrder(filter.getClass(),Ordered.LOWEST_PRECEDENCE);
                if(order<=root.getOrder()&&!BEFORE_NEUTRAL.contains(filter.getClass().getName()))throw fail();
                states.add(new State("@direct:"+entry.getKey(),filter,filter,null,order,true,entry.getKey(),"Boot4.1.2 auto mapping", "",List.of(),"Boot4.1.2 auto dispatch",false,null));
            }
            for(int i=0;i<auditObjects.size();i++){Object object=auditObjects.get(i);states.add(new State("@audit:"+i,object,object,null,0,true,"canonical audit graph","", "",List.of(),"",false,null));}
            states.sort(Comparator.comparing(State::bean));return List.copyOf(states);
        }
        private String target(DelegatingFilterProxyRegistrationBean registration){
            try{
                Method accessor=DelegatingFilterProxyRegistrationBean.class.getDeclaredMethod("getTargetBeanName");Field owner=DelegatingFilterProxyRegistrationBean.class.getDeclaredField("applicationContext");
                if(!accessor.trySetAccessible()||!owner.trySetAccessible()||owner.get(registration)!=context)throw fail();
                Object name=accessor.invoke(registration);if(!(name instanceof String value)||!value.equals(registration.getFilterName()))throw fail();return value;
            }catch(ReflectiveOperationException|RuntimeException failure){throw fail();}
        }
        private static void global(AbstractFilterRegistrationBean<?> registration){
            if(!registration.getServletNames().isEmpty()||!registration.getServletRegistrationBeans().isEmpty()||registration.isMatchAfter()
                    ||!registration.getUrlPatterns().isEmpty()&&!new HashSet<>(registration.getUrlPatterns()).equals(Set.of("/*")))throw fail();
        }
        private static IllegalStateException fail(){return new IllegalStateException(FAILURE);}
        private record State(String bean,Object registration,Object target,String targetName,int order,boolean enabled,String filterName,
                             String urls,String servletNames,List<?> servletRegistrations,String dispatchers,boolean matchAfter,Boolean asyncSupported) { }
    }
}
