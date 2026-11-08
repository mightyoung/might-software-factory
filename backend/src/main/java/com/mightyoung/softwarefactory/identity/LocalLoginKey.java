package com.mightyoung.softwarefactory.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** The existing local-login normalization, shared with invitation uniqueness checks. */
final class LocalLoginKey {
    private LocalLoginKey() { }
    static String normalize(String value) {
        return Normalizer.normalize(Objects.requireNonNull(value).strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }
}
