package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationOpinionTextTest {

    @Test
    void recognizesTheSameExplicitUnicodeWhiteSpaceSetUsedByV13() {
        List<Integer> whiteSpaceCodePoints = List.of(
                0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
                0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
                0x2028, 0x2029, 0x202F, 0x205F, 0x3000);

        for (int codePoint : whiteSpaceCodePoints) {
            String whitespace = new String(Character.toChars(codePoint));
            assertThat(ConfirmationOpinionText.hasContent(whitespace))
                    .as("U+%04X is Unicode White_Space", codePoint)
                    .isFalse();
            assertThat(ConfirmationOpinionText.trim(whitespace))
                    .as("U+%04X is trimmed", codePoint)
                    .isEmpty();
        }
        assertThat(ConfirmationOpinionText.trim("\u00a0\tOpinion\u3000")).isEqualTo("Opinion");
        assertThat(ConfirmationOpinionText.hasContent("\uFEFF")).isTrue();
    }
}
