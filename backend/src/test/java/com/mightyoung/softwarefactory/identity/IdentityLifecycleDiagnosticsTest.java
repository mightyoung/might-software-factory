package com.mightyoung.softwarefactory.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class IdentityLifecycleDiagnosticsTest {
    final Logger logger=(Logger)LoggerFactory.getLogger(IdentityLifecycleDiagnostics.class);
    final ListAppender<ILoggingEvent> appender=new ListAppender<>();
    @BeforeEach void capture(){appender.start();logger.addAppender(appender);}
    @AfterEach void detach(){logger.detachAppender(appender);appender.stop();}
    @Test void boundedFixedCardinalityDiagnosticsNeverIncludeExceptionMessagesCausesOrStacks(){
        var diagnostics=new IdentityLifecycleDiagnostics();UUID reference=UUID.randomUUID();
        RuntimeException secret=new IllegalStateException("secret-token password@example.invalid SELECT secret_hash=hidden",new IllegalArgumentException("private-cause"));
        for(int i=0;i<1000;i++)diagnostics.record(IdentityLifecycleDiagnostics.Stage.PROVIDER,secret,reference);
        assertThat(diagnostics.counts().get(IdentityLifecycleDiagnostics.Stage.PROVIDER)).isEqualTo(1000);
        assertThat(diagnostics.counts()).hasSize(IdentityLifecycleDiagnostics.Stage.values().length);
        var own=appender.list.stream().filter(event->event.getFormattedMessage().contains(reference.toString())).toList();
        assertThat(own).isNotEmpty().hasSizeLessThanOrEqualTo(8);
        for(var event:own){
            assertThat(event.getFormattedMessage()).contains("PROVIDER","UNEXPECTED",reference.toString()).doesNotContain("secret-token","password@example.invalid","SELECT","secret_hash","private-cause","IllegalStateException");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }
}
