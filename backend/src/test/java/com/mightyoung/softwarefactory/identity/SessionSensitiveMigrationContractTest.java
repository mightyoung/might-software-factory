package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

/** Source integrity/finite schema checks only; no claim of PostgreSQL execution. */
class SessionSensitiveMigrationContractTest {
    @Test void v20IsByteExactReviewedCandidateAndDoesNotActivate()throws Exception{
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/db/migration/V20__session_sensitive_actions.sql")){
            assertThat(input).isNotNull();bytes=input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("dafe3dff365e0fd50761af8082a6d6462e7354fdae02d4bea31bb8d7689e7c54");
        String sql=new String(bytes,StandardCharsets.UTF_8);
        assertThat(sql).contains("values(true,'OFF',0)","activation is one way","session authority is retained",
                "session authority cannot revive or rebind","session receipt does not match revocation");
        assertThat(sql.split("create table public\\.",-1)).hasSize(10);
        assertThat(sql).doesNotContain("security definer","grant all","on delete cascade");
    }
}
