package com.mightyoung.softwarefactory.identity;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Objects;

/** One-request secrets, never serialized, audited or placed in a session. */
public final class PasswordChangeRequest implements AutoCloseable {
    private final char[] currentPassword;
    private final char[] newPassword;
    public PasswordChangeRequest(char[] currentPassword, char[] newPassword) {
        this.currentPassword = Objects.requireNonNull(currentPassword).clone();
        this.newPassword = Objects.requireNonNull(newPassword).clone();
    }
    CharSequence currentPassword() { return CharBuffer.wrap(currentPassword).asReadOnlyBuffer(); }
    CharSequence newPassword() { return CharBuffer.wrap(newPassword).asReadOnlyBuffer(); }
    @Override public void close() { Arrays.fill(currentPassword, '\0'); Arrays.fill(newPassword, '\0'); }
    @Override public String toString() { return "PasswordChangeRequest[REDACTED]"; }
}
