package com.mightyoung.softwarefactory.dualprevention.validation;

/** Explicit Unicode White_Space rules shared by finding location validation paths. */
public final class UnicodeWhiteSpace {

    /** Matches a string containing at least one code point outside the Unicode White_Space set. */
    public static final String HAS_NON_WHITE_SPACE_CODE_POINT_PATTERN =
            "[\\s\\S]*[^\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680"
                    + "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000][\\s\\S]*";

    private UnicodeWhiteSpace() {
    }

    public static boolean containsNonWhiteSpace(CharSequence value) {
        if (value == null) return false;
        for (int index = 0; index < value.length();) {
            int codePoint = Character.codePointAt(value, index);
            if (!isWhiteSpace(codePoint)) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
                || codePoint == 0x0020
                || codePoint == 0x0085
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }
}
