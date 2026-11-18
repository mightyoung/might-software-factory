package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Arrays;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;
import static org.assertj.core.api.Assertions.*;

class IdentityLifecycleFingerprintTest {
    private static byte[] key(int value){byte[] key=new byte[32];Arrays.fill(key,(byte)value);return key;}
    @Test void retainedReceiptKeySurvivesRotationWithoutUsingTheNewActiveKey() {
        var oldCodec=new IdentityLifecycleFingerprint("old",Map.of("old",key(1)),key(3));
        var original=oldCodec.prepare(Operation.CONSUME_RESET,"challenge","body".toCharArray());
        var newCodec=new IdentityLifecycleFingerprint("new",Map.of("old",key(1),"new",key(2)),key(3));
        var retry=newCodec.prepare(Operation.CONSUME_RESET,"challenge","body".toCharArray());
        assertThat(retry.matches("old",original.activeFingerprint())).isTrue();
        assertThat(retry.activeFingerprint()).isNotEqualTo(original.activeFingerprint());
        assertThat(newCodec.prepare(Operation.CONSUME_RESET,"challenge","changed".toCharArray())
                .matches("old",original.activeFingerprint())).isFalse();
        assertThatThrownBy(()->retry.matches("missing",original.activeFingerprint()))
                .isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void domainAndLengthPrefixesBindEverySecretFieldWithoutAmbiguousConcatenation() {
        var codec=new IdentityLifecycleFingerprint("one",Map.of("one",key(1)),key(3));
        String first=codec.prepare(Operation.CONSUME_RESET,"ab","c").activeFingerprint();
        assertThat(codec.prepare(Operation.CONSUME_RESET,"a","bc").activeFingerprint()).isNotEqualTo(first);
        assertThat(codec.prepare(Operation.CONSUME_INVITATION,"ab","c").activeFingerprint()).isNotEqualTo(first);
        assertThat(codec.prepare(Operation.CONSUME_RESET,null,"ab","c").activeFingerprint()).isNotEqualTo(first);
        assertThat(codec.prepare(Operation.CONSUME_RESET,"ab","c").toString()).isEqualTo("PreparedLifecycleFingerprint[REDACTED]");
        assertThat(codec.selector("reset","ab")).isNotEqualTo(first);
    }
    @Test void keyMaterialIsDefensivelyCopiedAndAbsentConfigurationFails() {
        byte[] key=key(1);
        var codec=new IdentityLifecycleFingerprint("one",Map.of("one",key),key(3));
        String first=codec.prepare(Operation.CONSUME_RESET,"body").activeFingerprint();
        Arrays.fill(key,(byte)0);
        assertThat(codec.prepare(Operation.CONSUME_RESET,"body").activeFingerprint()).isEqualTo(first);
        assertThatThrownBy(()->new IdentityLifecycleFingerprint("one",Map.of(),key(3)))
                .isInstanceOf(IdentityLifecycleException.class);
    }
}
