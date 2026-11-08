package com.mightyoung.softwarefactory;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real servlet lifecycle ordering only; NAS tests still prove actual Session/audit JDBC faults. */
class IdentityFaultRequestScopeContainerTest {
    @TempDir Path temporary;
    enum Kind { IDENTITY, AUTHENTICATION_EVENT }
    enum Request { MATCH, WRONG_TOKEN, WRONG_PATH }

    @ParameterizedTest(name="{0} {1}")
    @MethodSource("requests")
    void passiveScopeCoversEveryFilterReturnAndClearsOnRequestDestroy(Kind kind,Request request)throws Exception {
        var scope=scope(kind);
        var tomcat=new Tomcat();
        Path base=temporary.resolve(kind.name()+request.name());Files.createDirectories(base);
        tomcat.setBaseDir(base.toString());tomcat.setHostname("127.0.0.1");
        tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
        var context=tomcat.addContext("",base.toString());
        var destroyed=new CompletableFuture<Void>();var trace=new ArrayList<String>();
        Object expected=request==Request.MATCH?scope.marker():null;
        // Tomcat destroys listeners in reverse order: this observer sees the fixture's cleanup.
        var observer=new ServletRequestListener(){
            @Override public void requestInitialized(ServletRequestEvent event){scope.seedStale().run();}
            @Override public void requestDestroyed(ServletRequestEvent event){
                try{assertThat(scope.current().get()).isNull();trace.add("destroyed");destroyed.complete(null);}
                catch(Throwable failure){destroyed.completeExceptionally(failure);}
            }
        };
        context.addServletContainerInitializer((types,servletContext)->{
            servletContext.addListener(observer);servletContext.addListener(scope.listener());
        },Set.of());
        addFilter(context,"audit-return",(req,res,chain)->{
            assertThat(scope.current().get()).isSameAs(expected);
            chain.doFilter(req,res);
            assertThat(scope.current().get()).isSameAs(expected);trace.add("audit-return");
        });
        addFilter(context,"session-finally",(req,res,chain)->{
            try{chain.doFilter(req,res);}
            finally{assertThat(scope.current().get()).isSameAs(expected);trace.add("session-finally");}
        });
        Tomcat.addServlet(context,"request",new HttpServlet(){
            @Override protected void doPost(HttpServletRequest req,HttpServletResponse res){
                assertThat(scope.current().get()).isSameAs(expected);trace.add("servlet");res.setStatus(204);
            }
        });
        context.addServletMappingDecoded("/*","request");
        try{
            tomcat.start();
            String path=request==Request.WRONG_PATH?"/different":"/api/v1/auth/login";
            String token=request==Request.WRONG_TOKEN?"unmatched-token":scope.token();
            try(var client=HttpClient.newBuilder().build()){
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+tomcat.getConnector().getLocalPort()+path))
                        .header(scope.header(),token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(204);
            }
            destroyed.get(5,TimeUnit.SECONDS);
            assertThat(trace).containsExactly("servlet","session-finally","audit-return","destroyed");
            scope.verify().accept(request==Request.MATCH);
        }finally{tomcat.stop();tomcat.destroy();}
    }

    private static Stream<Arguments> requests(){
        return Stream.of(Kind.values()).flatMap(kind->Stream.of(Request.values()).map(request->Arguments.of(kind,request)));
    }
    private static void addFilter(org.apache.catalina.Context context,String name,Filter filter){
        var definition=new FilterDef();definition.setFilterName(name);definition.setFilter(filter);context.addFilterDef(definition);
        var mapping=new FilterMap();mapping.setFilterName(name);mapping.addURLPattern("/*");context.addFilterMap(mapping);
    }
    private record Scope(Object marker,String token,String header,ServletRequestListener listener,Supplier<Object> current,
                         Runnable seedStale,Consumer<Boolean> verify){}
    private static Scope scope(Kind kind){
        if(kind==Kind.IDENTITY){
            var faults=new IdentityLoginFailurePostgresIT.Faults();
            var fault=new IdentityLoginFailurePostgresIT.Fault(IdentityLoginFailurePostgresIT.Mode.EARLY_SESSION_WRITE,UUID.randomUUID());
            faults.armed.set(fault);
            var listener=new IdentityLoginFailurePostgresIT.FaultConfiguration().identityClosureRequestScope(faults).getListener();
            return new Scope(fault,fault.token,"X-Identity-Closure-Fault",listener,faults.current::get,
                    ()->faults.current.set(fault),matched->{
                        if(!matched){assertThat(fault.scopeExits.get()).isZero();assertThat(fault.scopeClosed).isNotDone();}
                        else{assertThat(fault.scopeEntries.get()).isEqualTo(1);assertThat(fault.scopeExits.get()).isEqualTo(1);
                            assertThat(fault.scopeClosed).isCompleted();assertThat(fault.scopeExitThread.get()).isSameAs(fault.scopeThread.get());}
                    });
        }
        var probes=new AuthenticationEventPostgresIT.Probes();
        var probe=new AuthenticationEventPostgresIT.Probe(AuthenticationEventPostgresIT.Mode.AUDIT_INSERT_FAILURE);
        probes.armed.set(probe);
        var listener=new AuthenticationEventPostgresIT.FaultConfiguration().authenticationEventProbeScope(probes).getListener();
        return new Scope(probe,probe.token,"X-Authentication-Event-Acceptance",listener,probes.current::get,
                ()->probes.current.set(probe),matched->{
                    if(!matched){assertThat(probe.scopeExits.get()).isZero();assertThat(probe.scopeClosed).isNotDone();}
                    else{assertThat(probe.scopeEntries.get()).isEqualTo(1);assertThat(probe.scopeExits.get()).isEqualTo(1);
                        assertThat(probe.scopeClosed).isCompleted();assertThat(probe.scopeExitThread.get()).isSameAs(probe.scopeThread.get());}
                });
    }
}
