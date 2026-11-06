package com.mightyoung.softwarefactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real connector settings and one actual embedded server; synthetic event/context fixtures do not prove the full application chain. */
class RiskScenarioTomcatProfileTest {
    @Test void refusesBeforeInitializationThenUsesActualDefaultCapWithoutChanging8192(){
        var f=new Fixture();assertEquals(8192,f.protocol.getMaxHttpResponseHeaderSize());assertThrows(IllegalStateException.class,f.profile::getAsInt);
        f.protocol.setKeepAliveTimeout(60_000);f.initialize();assertEquals(8192+24+24,f.profile.getAsInt());assertEquals(8192,f.protocol.getMaxHttpResponseHeaderSize());
    }
    @Test void reserveDerivesConfiguredCapConnectionKeepAliveTimeoutAndServerBytes(){
        var f=new Fixture();f.protocol.setMaxHttpResponseHeaderSize(16384);f.protocol.setKeepAliveTimeout(1001);f.protocol.setServer("App/1");f.initialize();
        assertEquals(24+23+15,f.profile.getAsInt());
        var larger=new Fixture();larger.protocol.setMaxHttpResponseHeaderSize(32768);larger.protocol.setKeepAliveTimeout(1001);larger.protocol.setServer("App/1");larger.initialize();assertEquals(24+23+15,larger.profile.getAsInt());
    }
    @Test void actualDisabledKeepAliveHeaderAndNullServerHaveNoInventedExtraLines(){
        var f=new Fixture();f.protocol.setUseKeepAliveResponseHeader(false);f.initialize();assertEquals(8192+24,f.profile.getAsInt());
        var noTimeout=new Fixture();noTimeout.protocol.setKeepAliveTimeout(0);noTimeout.initialize();assertEquals(8192+24,noTimeout.profile.getAsInt());
    }
    @Test void foreignContextCannotInitializeAndSameContextMustNameItsActualWebServer(){
        var f=new Fixture();var foreign=mock(ServletWebServerApplicationContext.class);f.profile.onApplicationEvent(new ServletWebServerInitializedEvent(f.server,foreign));assertThrows(IllegalStateException.class,f.profile::getAsInt);f.initialize();assertTrue(f.profile.getAsInt()>8192);
        var mismatch=new Fixture();when(mismatch.context.getWebServer()).thenReturn(mock(WebServer.class));assertThrows(IllegalStateException.class,mismatch::initialize);assertThrows(IllegalStateException.class,mismatch.profile::getAsInt);
    }
    @Test void unsupportedServerVersionOrProtocolCannotBecomeAConstantFallback(){
        var f=new Fixture();try(var info=mockStatic(ServerInfo.class,CALLS_REAL_METHODS)){info.when(ServerInfo::getServerInfo).thenReturn("Apache Tomcat/11.0.28");assertThrows(IllegalStateException.class,f::initialize);}assertThrows(IllegalStateException.class,f.profile::getAsInt);
        var nonTomcat=new Fixture();var other=mock(WebServer.class);when(nonTomcat.context.getWebServer()).thenReturn(other);assertThrows(IllegalStateException.class,()->nonTomcat.profile.onApplicationEvent(new ServletWebServerInitializedEvent(other,nonTomcat.context)));
        var wrongProtocol=new Fixture();wrongProtocol.tomcat.getService().removeConnector(wrongProtocol.connector);wrongProtocol.tomcat.getService().addConnector(new Connector("org.apache.coyote.http11.Http11Nio2Protocol"));assertThrows(IllegalStateException.class,wrongProtocol::initialize);
    }
    @Test void compressionUpgradeAdditionalConnectorAndImpossibleCapAreClosed(){
        var compression=new Fixture();compression.protocol.setCompression("on");assertThrows(IllegalStateException.class,compression::initialize);
        var upgrade=new Fixture();upgrade.protocol.addUpgradeProtocol(mock(UpgradeProtocol.class));assertThrows(IllegalStateException.class,upgrade::initialize);
        var multiple=new Fixture();multiple.tomcat.getService().addConnector(new Connector(Http11NioProtocol.class.getName()));assertThrows(IllegalStateException.class,multiple::initialize);
        var cap=new Fixture();cap.protocol.setMaxHttpResponseHeaderSize(1);assertThrows(IllegalStateException.class,cap::initialize);
    }
    @Test void nonAsciiControlOrUnboundedConfiguredServerCannotBeBudgetedAsAscii(){
        for(String server:List.of("bad\rheader","é","a".repeat(16385))){var f=new Fixture();f.protocol.setServer(server);assertThrows(IllegalStateException.class,f::initialize);}
    }
    @Test void observedSettingsDriftPermanentlyRefusesEvenWhenNumericReserveWouldStayEqual(){
        List<Consumer<Http11NioProtocol>> changes=List.of(p->p.setMaxHttpResponseHeaderSize(9000),p->p.setMaxHttpHeaderSize(9000),p->p.setCompression("on"),p->p.setUseKeepAliveResponseHeader(false),p->p.setKeepAliveTimeout(p.getKeepAliveTimeout()+1),p->p.setConnectionTimeout(p.getConnectionTimeout()+1),p->p.setServer("changed"),p->p.setServerRemoveAppProvidedValues(!p.getServerRemoveAppProvidedValues()),p->p.addUpgradeProtocol(mock(UpgradeProtocol.class)));
        for(var change:changes){var f=new Fixture();f.initialize();var before=observed(f.protocol);change.accept(f.protocol);assertNotEquals(before,observed(f.protocol),"Drift fixture must change an actual sampled getter");assertThrows(IllegalStateException.class,f.profile::getAsInt);}
        var f=new Fixture();f.initialize();f.protocol.setServer("changed");assertThrows(IllegalStateException.class,f.profile::getAsInt);f.protocol.setServer(null);assertThrows(IllegalStateException.class,f.profile::getAsInt);
    }
    @Test void replacedContextServerAndRepeatedInitializationAreNotReboundSilently(){
        var f=new Fixture();f.initialize();when(f.context.getWebServer()).thenReturn(mock(WebServer.class));assertThrows(IllegalStateException.class,f.profile::getAsInt);
        var repeated=new Fixture();repeated.initialize();assertThrows(IllegalStateException.class,repeated::initialize);assertThrows(IllegalStateException.class,repeated.profile::getAsInt);
    }
    @Test void boundarySecondSampleRejectsChangedSettingsBeforeAnyOwnedBodyIsSent()throws Exception{
        var f=new Fixture();f.initialize();var boundary=new RiskScenarioHttpBoundary(f.profile);var request=new MockHttpServletRequest("GET","/api/v1/entities/risk_scenario/id");var response=new MockHttpServletResponse();var writes=new AtomicInteger();
        assertThrows(java.io.IOException.class,()->boundary.doFilter(request,response,(req,res)->{
            boundary.stage((jakarta.servlet.http.HttpServletRequest)req,(jakarta.servlet.http.HttpServletResponse)res,200,Map.of("Content-Type",List.of("application/json"),"Cache-Control",List.of("no-store")),1,out->{writes.incrementAndGet();out.write(1);});
            String before=f.protocol.getServer();f.protocol.setServer("changed-during-request");assertNotEquals(before,f.protocol.getServer());
        }));assertEquals(0,writes.get());assertEquals(0,response.getContentAsByteArray().length);
    }
    @Test void actualEmbeddedTomcatVersionAndConnectorAreObservedWithoutCookieProcessorMutation(){
        var factory=new TomcatServletWebServerFactory(0);var server=factory.getWebServer();try{
            server.start();assertTrue(server.getPort()>0);assertEquals("Apache Tomcat/11.0.27",ServerInfo.getServerInfo());assertEquals("11.0.27.0",ServerInfo.getServerNumber());
            var actual=(TomcatWebServer)server;var connectors=actual.getTomcat().getService().findConnectors();assertEquals(1,connectors.length);assertEquals(Http11NioProtocol.class,connectors[0].getProtocolHandler().getClass());
            var context=mock(ServletWebServerApplicationContext.class);when(context.getWebServer()).thenReturn(server);var profile=new RiskScenarioTomcatProfile(context);profile.bindFilterProfile(()->{}); // Connector-only fixture; assembled filter topology is verified by configuration/Boot tests.
            var application=(org.apache.catalina.Context)actual.getTomcat().getHost().findChildren()[0];var cookies=application.getCookieProcessor();int cap=((Http11NioProtocol)connectors[0].getProtocolHandler()).getMaxHttpResponseHeaderSize();
            profile.onApplicationEvent(new ServletWebServerInitializedEvent(server,context));assertTrue(profile.getAsInt()>0);assertEquals(cap,((Http11NioProtocol)connectors[0].getProtocolHandler()).getMaxHttpResponseHeaderSize());assertSame(cookies,application.getCookieProcessor());
            // Keep this drift assertion on a bound endpoint; an unbound endpoint getter always returns 1.
            var protocol=(Http11NioProtocol)connectors[0].getProtocolHandler();int before=protocol.getMaxKeepAliveRequests();protocol.setMaxKeepAliveRequests(before+1);assertNotEquals(before,protocol.getMaxKeepAliveRequests());assertThrows(IllegalStateException.class,profile::getAsInt);protocol.setMaxKeepAliveRequests(before);assertEquals(before,protocol.getMaxKeepAliveRequests());assertThrows(IllegalStateException.class,profile::getAsInt);
        }finally{server.destroy();}
    }
    private static List<Object> observed(Http11NioProtocol p){return java.util.Arrays.asList(p.getMaxHttpResponseHeaderSize(),p.getMaxHttpHeaderSize(),p.getCompression(),p.getUseKeepAliveResponseHeader(),p.getKeepAliveTimeout(),p.getConnectionTimeout(),p.getServer(),p.getServerRemoveAppProvidedValues(),p.findUpgradeProtocols().length);}
    private static final class Fixture {
        final ServletWebServerApplicationContext context=mock(ServletWebServerApplicationContext.class);final TomcatWebServer server=mock(TomcatWebServer.class);final Tomcat tomcat=new Tomcat();final Connector connector=new Connector(Http11NioProtocol.class.getName());final Http11NioProtocol protocol=(Http11NioProtocol)connector.getProtocolHandler();final RiskScenarioTomcatProfile profile=new RiskScenarioTomcatProfile(context);
        Fixture(){profile.bindFilterProfile(()->{});tomcat.getService().addConnector(connector);when(server.getTomcat()).thenReturn(tomcat);when(context.getWebServer()).thenReturn(server);}
        void initialize(){profile.onApplicationEvent(new ServletWebServerInitializedEvent(server,context));}
    }
}
