package com.mightyoung.softwarefactory;

import java.util.Objects;
import java.util.function.IntSupplier;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;

/** Live supported-container admission, issued only by this application's actual server-initialized event.
 * The boundary samples before entering and after late writers; observed drift permanently closes this profile.
 * Sampling is not a proof against unobserved change-and-restore between samples. */
final class RiskScenarioTomcatProfile implements IntSupplier,ApplicationListener<ServletWebServerInitializedEvent> {
    private static final String FAILURE="Risk HTTP requires the reviewed actual Tomcat response profile";
    private final ApplicationContext context;
    private TomcatWebServer server;
    private Settings admitted;
    private boolean invalid;
    private Runnable filterProfile;
    synchronized void bindFilterProfile(Runnable verified){if(invalid||admitted!=null||filterProfile!=null)throw reject();filterProfile=Objects.requireNonNull(verified);filterProfile.run();}
    RiskScenarioTomcatProfile(ApplicationContext context){this.context=Objects.requireNonNull(context);}
    @Override public synchronized void onApplicationEvent(ServletWebServerInitializedEvent event){
        if(event.getApplicationContext()!=context)return;
        if(invalid||admitted!=null)throw reject();
        try{
            if(filterProfile==null)throw reject();filterProfile.run();
            if(!(event.getWebServer() instanceof TomcatWebServer actual)||actual.getClass()!=TomcatWebServer.class||event.getApplicationContext().getWebServer()!=actual)throw reject();
            Settings first=sample(actual),second=sample(actual);if(!first.equals(second))throw reject();
            reserve(first);server=actual;admitted=first;
        }catch(RuntimeException failure){throw reject();}
    }
    @Override public synchronized int getAsInt(){
        if(invalid||admitted==null||server==null)throw new IllegalStateException(FAILURE);
        if(!(context instanceof ServletWebServerApplicationContext actualContext)||actualContext.getWebServer()!=server)throw reject();
        try{if(filterProfile==null)throw reject();filterProfile.run();Settings current=sample(server);if(!admitted.equals(current))throw reject();return reserve(current);}
        catch(RuntimeException failure){throw reject();}
    }
    private Settings sample(TomcatWebServer actual){
        if(!"Apache Tomcat/11.0.27".equals(ServerInfo.getServerInfo())||!"11.0.27.0".equals(ServerInfo.getServerNumber()))throw reject();
        Tomcat tomcat=Objects.requireNonNull(actual.getTomcat());Connector[] connectors=tomcat.getService().findConnectors();
        if(connectors.length!=1)throw reject();Connector connector=connectors[0];
        if(connector.getProtocolHandler()==null||connector.getProtocolHandler().getClass()!=Http11NioProtocol.class)throw reject();
        var protocol=(Http11NioProtocol)connector.getProtocolHandler();
        if(!"off".equals(protocol.getCompression())||protocol.findUpgradeProtocols().length!=0||protocol.getMaxHttpResponseHeaderSize()<=0)throw reject();
        String configuredServer=protocol.getServer();if(configuredServer!=null)ascii(configuredServer);
        return new Settings(tomcat,connector,protocol,protocol.getMaxHttpResponseHeaderSize(),protocol.getMaxHttpHeaderSize(),protocol.getCompression(),
                protocol.getUseKeepAliveResponseHeader(),protocol.getKeepAliveTimeout(),protocol.getConnectionTimeout(),protocol.getMaxKeepAliveRequests(),configuredServer,protocol.getServerRemoveAppProvidedValues());
    }
    private static int reserve(Settings settings){
        long reserve=RiskScenarioHeaderBudget.MAX_BYTES-Math.min(RiskScenarioHeaderBudget.MAX_BYTES,settings.responseCap());
        // Tomcat11 may add close, add keep-alive, or append ', keep-alive'. A whole longest line covers each case.
        reserve+=Math.max(line("Connection","close"),line("Connection","keep-alive"));
        if(settings.keepAliveHeader()&&settings.keepAliveTimeout()>0)reserve+=line("Keep-Alive","timeout="+(settings.keepAliveTimeout()/1000L));
        if(settings.serverValue()!=null)reserve+=line("Server",settings.serverValue());
        if(reserve>RiskScenarioHeaderBudget.MAX_BYTES)throw new IllegalStateException(FAILURE);return (int)reserve;
    }
    private static long line(String name,String value){return (long)name.length()+2+value.length()+2;}
    private static void ascii(String value){if(value.length()>RiskScenarioHeaderBudget.MAX_BYTES)throw new IllegalStateException(FAILURE);for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c!='\t'&&(c<0x20||c>0x7e))throw new IllegalStateException(FAILURE);}}
    private IllegalStateException reject(){invalid=true;return new IllegalStateException(FAILURE);}
    private record Settings(Tomcat tomcat,Connector connector,Http11NioProtocol protocol,int responseCap,int baseCap,String compression,
                            boolean keepAliveHeader,int keepAliveTimeout,int connectionTimeout,int maximumKeepAliveRequests,String serverValue,boolean removeApplicationServer) { }
}
