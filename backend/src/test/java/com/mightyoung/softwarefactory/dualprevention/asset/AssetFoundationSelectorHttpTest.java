package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorContracts.*;

class AssetFoundationSelectorHttpTest {
    private static final String ID = "a1234567-1234-1234-1234-123456789abc";
    @Test @SuppressWarnings("unchecked") void everyFixedRouteMatchesItsIndependentOpenApiOwnerAndReferenceMatrix() throws Exception {
        var service = mock(AssetFoundationSelectorService.class);
        var requests = mock(SessionSensitiveRequests.class); var capture = mock(CapturedHumanRequest.class);
        when(requests.capture(any())).thenReturn(capture);
        when(service.query(eq(capture), any(), nullable(UUID.class), any())).thenReturn(new Page(List.of(new Item(UUID.fromString(ID), "W1", "Name")), null));
        var mvc = MockMvcBuilders.standaloneSetup(new AssetFoundationSelectorController(service,
                new AssetFoundationSelectorParser(JsonMapper.builder().build()), requests)).build();
        Map<String, Object> document;
        try (var input = Files.newBufferedReader(Path.of("..", "contracts", "openapi", "asset-foundation.yaml"))) { document = new Yaml().load(input); }
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        int count = 0;
        for (var entry : paths.entrySet()) {
            var operation = (Map<String, Object>) ((Map<String, Object>) entry.getValue()).get("post");
            if (operation == null || !(operation.get("x-selector") instanceof Map<?, ?> selector)) continue;
            String path = entry.getKey().replace("{lineId}", ID).replace("{stageId}", ID).replace("{workUnitId}", ID);
            String body = switch (selector.get("request").toString()) {
                case "AnchorReferenceRequest" -> "{\"query\":\"\",\"organizationId\":\"" + ID + "\"}";
                case "OrganizationPositionReferenceRequest" -> "{\"query\":\"\",\"orgId\":\"" + ID + "\"}";
                default -> "{\"query\":\"\"}";
            };
            var result = mvc.perform(post(path).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextAfterId").doesNotExist()).andReturn();
            AssetFoundationHttpWireContractTest.validate("ReferenceResponse", result.getResponse().getContentAsString());
            var invocation = mockingDetails(service).getInvocations().stream().reduce((left, right) -> right).orElseThrow();
            Selector selected = invocation.getArgument(1);
            assertThat(selected.owner.permissionCode()).isEqualTo(selector.get("ownerAction"));
            assertThat(selected.referencedRead == null ? null : selected.referencedRead.permissionCode()).isEqualTo(selector.get("referencedReadAction"));
            count++;
        }
        assertThat(count).isEqualTo(17); verify(capture, times(17)).close();
    }
}
