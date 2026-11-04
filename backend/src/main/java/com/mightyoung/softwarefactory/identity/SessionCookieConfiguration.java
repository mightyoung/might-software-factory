package com.mightyoung.softwarefactory.identity;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWarDeployment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWarDeployment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.util.Assert;
import org.springframework.web.context.ServletContextAware;

/** Boot-compatible servlet cookie assembly; does not activate session-authority SQL or services. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionCookieConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnNotWarDeployment
    @EnableConfigurationProperties(ServerProperties.class)
    static class Embedded {
        @Bean
        KnownLogoutRequests knownLogoutRequests(ServerProperties serverProperties,
                ObjectProvider<DefaultCookieSerializerCustomizer> customizers) {
            return new KnownLogoutRequests(serializer -> {
                Cookie cookie = serverProperties.getServlet().getSession().getCookie();
                PropertyMapper map = PropertyMapper.get();
                map.from(cookie::getName).to(serializer::setCookieName);
                map.from(cookie::getDomain).to(serializer::setDomainName);
                map.from(cookie::getPath).to(serializer::setCookiePath);
                map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
                map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
                map.from(cookie::getMaxAge).asInt(Duration::getSeconds).to(serializer::setCookieMaxAge);
                map.from(cookie::getSameSite).to(value -> serializer.setSameSite(value.attributeValue()));
                map.from(cookie::getPartitioned).to(serializer::setPartitioned);
                customizers.orderedStream().forEach(customizer -> customizer.customize(serializer));
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWarDeployment
    static class War implements ServletContextAware {
        private ServletContext servletContext;

        @Override public void setServletContext(ServletContext servletContext) { this.servletContext = servletContext; }

        @Bean
        KnownLogoutRequests knownLogoutRequests(ObjectProvider<DefaultCookieSerializerCustomizer> customizers) {
            Assert.notNull(servletContext, "ServletContext is required for session cookie configuration in a WAR deployment");
            SessionCookieConfig cookie = servletContext.getSessionCookieConfig();
            return new KnownLogoutRequests(serializer -> {
                PropertyMapper map = PropertyMapper.get();
                map.from(cookie::getName).to(serializer::setCookieName);
                map.from(cookie::getDomain).to(serializer::setDomainName);
                map.from(cookie::getPath).to(serializer::setCookiePath);
                map.from(cookie::isHttpOnly).to(serializer::setUseHttpOnlyCookie);
                map.from(cookie::isSecure).to(serializer::setUseSecureCookie);
                map.from(cookie::getMaxAge).to(serializer::setCookieMaxAge);
                map.from(cookie.getAttribute("SameSite")).always().to(serializer::setSameSite);
                map.from(cookie.getAttribute("Partitioned")).as(Boolean::valueOf).to(serializer::setPartitioned);
                customizers.orderedStream().forEach(customizer -> customizer.customize(serializer));
            });
        }
    }

    /** Declared interface visibility makes Boot's otherwise-unused serializer factory back off. */
    @Bean
    CookieSerializer sessionCookieSerializer(KnownLogoutRequests requests) {
        return requests.cookieSerializer();
    }

    @Bean
    CookieHttpSessionIdResolver sessionCookieHttpSessionIdResolver(KnownLogoutRequests requests) {
        return requests.sessionIdResolver();
    }

    /** Supplements construction and actual-filter tests; @Primary is not a uniqueness exemption. */
    @Bean
    SmartInitializingSingleton sharedSessionCookieInvariant(KnownLogoutRequests expected,
            ObjectProvider<KnownLogoutRequests> bundles,
            ObjectProvider<HttpSessionIdResolver> resolvers,
            ObjectProvider<CookieSerializer> serializers) {
        return () -> {
            List<KnownLogoutRequests> allBundles = bundles.stream().toList();
            List<HttpSessionIdResolver> allResolvers = resolvers.stream().toList();
            List<CookieSerializer> allSerializers = serializers.stream().toList();
            if (allBundles.size() != 1 || allBundles.getFirst() != expected
                    || allResolvers.size() != 1 || allResolvers.getFirst() != expected.sessionIdResolver()
                    || allSerializers.size() != 1 || allSerializers.getFirst() != expected.cookieSerializer()) {
                throw new IllegalStateException("Exactly one trusted session cookie bundle, resolver and serializer is required");
            }
        };
    }
}
