package com.mightyoung.softwarefactory.files;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScannerGenerationManifestTest {
    private final Instant now=Instant.parse("2026-11-03T10:00:00Z");
    @Test void parsesAllPinnedHeaderFieldsRatherThanDailyVersionOutput() {
        var definition=ScannerGenerationManifest.parseHeader("daily.cvd",header(now.minusSeconds(60),42,230),1024,"a".repeat(64),now,230);
        assertThat(definition.version()).isEqualTo(42);assertThat(definition.signatureCount()).isEqualTo(3000);
        assertThat(definition.functionalityLevel()).isEqualTo(230);assertThat(definition.builtAt()).isEqualTo(now.minusSeconds(60));
    }
    @Test void freshMainCannotHideStaleDailyAndEveryFutureHeaderIsRejected() {
        assertThatCode(()->ScannerGenerationManifest.parseHeader("main.cvd",header(now,42,230),1024,"a".repeat(64),now,230)).doesNotThrowAnyException();
        assertThatThrownBy(()->ScannerGenerationManifest.parseHeader("daily.cvd",header(now.minus(Duration.ofHours(48)).minusSeconds(1),42,230),1024,"a".repeat(64),now,230)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        for(String name:new String[]{"main.cvd","daily.cvd","bytecode.cvd"})
            assertThatThrownBy(()->ScannerGenerationManifest.parseHeader(name,header(now.plusSeconds(301),42,230),1024,"a".repeat(64),now,230)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void rejectsUnsignedOrUnsupportedHeaderAndUnknownDefinitionSetMembers() {
        assertThatThrownBy(()->ScannerGenerationManifest.parseHeader("daily.cld",header(now,42,230),1024,"a".repeat(64),now,230)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->ScannerGenerationManifest.parseHeader("daily.cvd",header(now,42,231),1024,"a".repeat(64),now,230)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        byte[] unsigned=header(now,42,230);byte[] marker="A".repeat(32).getBytes(StandardCharsets.US_ASCII);
        String text=new String(unsigned,StandardCharsets.US_ASCII).replace("A".repeat(32)," ".repeat(32));
        assertThatThrownBy(()->ScannerGenerationManifest.parseHeader("daily.cvd",text.getBytes(StandardCharsets.US_ASCII),1024,"a".repeat(64),now,230)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->ScannerGenerationManifest.parseHeader("daily.cvd",Arrays.copyOf(unsigned,511),1024,"a".repeat(64),now,230)).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    @Test void containmentProfileRejectsDuplicateFieldsMalformedAsciiAndUnboundedInput() {
        assertThatThrownBy(()->ImmutableContainmentProfile.parse("format=MSF_SCANNER_LINUX_V1\nid=x\nid=y\n".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->ImmutableContainmentProfile.parse("id=x\r\n".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->ImmutableContainmentProfile.parse(new byte[32769])).isInstanceOf(FileEvidenceTrustUnavailableException.class);
        assertThatThrownBy(()->ImmutableContainmentProfile.parse("format=unterminated".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }
    static byte[] header(Instant built,int version,int functionality) {
        String value="ClamAV-VDB:03 Nov 2026 10-00 +0000:"+version+":3000:"+functionality+":"+"a".repeat(32)+":"+"A".repeat(32)+":builder:"+built.getEpochSecond();
        if(value.length()>512)throw new AssertionError();return (value+" ".repeat(512-value.length())).getBytes(StandardCharsets.US_ASCII);
    }
}
