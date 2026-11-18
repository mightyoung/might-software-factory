package com.mightyoung.softwarefactory;

import java.util.List;
import java.util.Map;

/** Conservative HTTP/1.1 header admission budget, not a measurement of container wire output. */
final class RiskScenarioHeaderBudget {
    static final int MAX_BYTES = 16 * 1024;
    // Includes CRLF and conservatively covers the standard status line without guessing a reason phrase.
    private static final int STATUS_LINE_BYTES = 64;

    private RiskScenarioHeaderBudget() {}

    static long bytes(int status, Map<String, List<String>> headers) {
        if (status < 100 || status > 599 || headers == null) throw new Rejected();
        long total = STATUS_LINE_BYTES + 2L; // Final empty line.
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            if (name == null || name.isEmpty() || values == null || values.isEmpty()) throw new Rejected();
            long nameBytes = 0;
            for (int i = 0; i < name.length(); i++) {
                if (!token(name.charAt(i))) throw new Rejected();
                nameBytes = add(nameBytes, 1);
            }
            for (String value : values) {
                if (value == null) throw new Rejected();
                total = add(total, nameBytes);
                total = add(total, 4); // Colon, SP and CRLF; repeated values each have their own field line.
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    if (character != '\t' && (character < 0x20 || character > 0x7e)) throw new Rejected();
                    total = add(total, 1);
                }
            }
        }
        return total;
    }

    static void requireFits(int status, Map<String, List<String>> headers, int containerReserve) {
        if (containerReserve < 0 || containerReserve > MAX_BYTES) throw new Rejected();
        add(bytes(status, headers), containerReserve);
    }

    private static long add(long current, long added) {
        if (added > MAX_BYTES - current) throw new Rejected();
        return current + added;
    }

    private static boolean token(char value) {
        return value >= '0' && value <= '9' || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z' || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
    }

    static final class Rejected extends IllegalArgumentException {
        Rejected() { super("Risk response headers exceed the admitted boundary"); }
    }
}
