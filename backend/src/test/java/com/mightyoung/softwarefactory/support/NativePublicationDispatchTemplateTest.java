package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePublicationDispatchTemplateTest {
    @Test void everyExecutionEntryAndConfigurationSetterHasAnExplicitClosedOverride() throws Exception {
        JdbcOperations delegate = mock(JdbcOperations.class);
        AtomicInteger rejected = new AtomicInteger();
        var template = new NativePublicationDispatchTemplate(delegate, rejected::incrementAndGet);
        var methods = new HashSet<>(List.of(JdbcOperations.class.getMethods()));
        Arrays.stream(JdbcTemplate.class.getMethods()).filter(method -> method.getName().startsWith("set")
                || List.of("query", "execute", "call", "update", "batchUpdate").stream().anyMatch(method.getName()::startsWith)).forEach(methods::add);
        for (Method method : methods) {
            Method implementation = NativePublicationDispatchTemplate.class.getMethod(method.getName(), method.getParameterTypes());
            assertThat(implementation.getDeclaringClass()).as(method.toString()).isEqualTo(NativePublicationDispatchTemplate.class);
            boolean nativeMethod = method.getName().equals("update") && List.of(method.getParameterTypes()).equals(List.of(String.class, Object[].class))
                    || method.getName().equals("query") && (List.of(method.getParameterTypes()).equals(List.of(String.class, ResultSetExtractor.class))
                    || List.of(method.getParameterTypes()).equals(List.of(String.class, ResultSetExtractor.class, Object[].class)));
            if (nativeMethod) continue;
            Object[] arguments = Arrays.stream(method.getParameterTypes()).map(type -> type == int.class ? 0 : type == boolean.class ? false : null).toArray();
            assertThatThrownBy(() -> implementation.invoke(template, arguments)).isInstanceOf(InvocationTargetException.class)
                    .satisfies(failure -> assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class).hasMessage("Shared native publication unavailable"));
        }
        assertThat(rejected.get()).isGreaterThan(70);
        verifyNoInteractions(delegate);
    }
}
