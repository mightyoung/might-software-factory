package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.files.VerifiedFileContent;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FindingEvidenceContentControllerHttpTest {

    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final String PATH = "/api/v1/findings/" + FINDING_ID
            + "/remediation-rounds/2/evidence-items/" + ITEM_ID + "/content";

    @Test
    void streamsOnlyTheServerSelectedEvidenceItemAndDoesNotAcceptClientStorageSelectors() throws Exception {
        AtomicReference<String> resolved = new AtomicReference<>();
        FindingEvidenceContentReader service = (finding, round, item) -> {
            resolved.set(finding + "/" + round + "/" + item);
            return new ByteContent();
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FindingEvidenceContentController(service)).build();

        mvc.perform(get(PATH).param("fileId", UUID.randomUUID().toString())
                        .param("storageVersion", "latest").param("objectKey", "../../private"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("synthetic evidence response".getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"evidence.bin\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        assertThat(resolved.get()).isEqualTo(FINDING_ID + "/2/" + ITEM_ID);
    }

    @Test
    void closesThePinnedStreamWhenTheClientCancelsTheResponse() throws Exception {
        ByteContent content = new ByteContent();
        content.failDuringTransfer = true;
        FindingEvidenceContentReader service = (finding, round, item) -> content;
        FindingEvidenceContentController controller = new FindingEvidenceContentController(service);
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener writeListener) { }
                    @Override public void write(int value) throws IOException { throw new IOException("client cancelled"); }
                };
            }
        };

        assertThatThrownBy(() -> controller.download(FINDING_ID, 2, ITEM_ID, response))
                .isInstanceOf(IOException.class);

        assertThat(content.closed).isTrue();
    }

    @Test
    void hidesUnsealedOrRevokedEvidenceAsNotVisibleWithoutLeakingStorageDetails() throws Exception {
        FindingEvidenceContentReader service = (finding, round, item) -> {
            throw new FindingNotVisibleException();
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FindingEvidenceContentController(service))
                .setControllerAdvice(new FindingApiExceptionHandler())
                .build();

        mvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("RESOURCE_NOT_VISIBLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("The finding is not visible"));
    }

    @Test
    void unavailableTrustedStorageFailsClosedWithoutExposingItsPath() throws Exception {
        FindingEvidenceContentReader service = (finding, round, item) -> {
            throw new FileEvidenceTrustUnavailableException();
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FindingEvidenceContentController(service))
                .setControllerAdvice(new FindingApiExceptionHandler())
                .build();

        mvc.perform(get(PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("The finding command could not be completed safely"));
    }

    private static final class ByteContent implements VerifiedFileContent {
        private boolean closed;
        private boolean failDuringTransfer;

        @Override public long sizeBytes() { return 27; }
        @Override public void transferTo(java.io.OutputStream output) throws IOException {
            if (failDuringTransfer) {
                output.write("partial".getBytes(StandardCharsets.UTF_8));
            } else {
                output.write("synthetic evidence response".getBytes(StandardCharsets.UTF_8));
            }
        }
        @Override public void close() { closed = true; }
    }
}
