package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionApiContractTest {

    @Test
    void publishesTheSessionBootstrapLoginLogoutCurrentUserAndReportableOrganizations() throws Exception {
        Map<String, Object> document = new Yaml().load(
                Files.readString(Path.of("..", "contracts", "openapi", "session-api.yaml")));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");

        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/api/v1/auth/csrf",
                "/api/v1/auth/login",
                "/api/v1/auth/logout",
                "/api/v1/session/current",
                "/api/v1/organizations/reportable");
        for (String path : new String[] {"/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout"}) {
            Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) paths.get(path))
                    .get(path.endsWith("/csrf") ? "get" : "post");
            Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
            assertThat(responses).containsKey("204");
        }
        assertThat(((Map<?, ?>) paths.get("/api/v1/session/current")).containsKey("get")).isTrue();
        assertThat(((Map<?, ?>) paths.get("/api/v1/organizations/reportable")).containsKey("get")).isTrue();
    }
}
