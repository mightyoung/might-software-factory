package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

/** Unicode White_Space handling shared by confirmation validation and normalization. */
public final class ConfirmationOpinionText {

    private ConfirmationOpinionText() {
    }

    public static boolean hasContent(String value) {
        if (value == null) return false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!isUnicodeWhiteSpace(codePoint)) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    public static String trim(String value) {
        if (value == null || value.isEmpty()) return value;
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhiteSpace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhiteSpace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /** Unicode White_Space property, kept in sync with the V13 PostgreSQL CHECK constraint. */
    private static boolean isUnicodeWhiteSpace(int codePoint) {
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
