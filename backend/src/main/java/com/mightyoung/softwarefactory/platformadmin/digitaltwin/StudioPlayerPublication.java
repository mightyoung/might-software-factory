package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant Player read use case; no transport, workflow, or storage configuration. */
public final class StudioPlayerPublication {
    private final PublishedLookup lookup;

    public StudioPlayerPublication(PublishedLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup);
    }

    /** Read only published data, by exact composite target and pinned version (null means current). */
    @FunctionalInterface
    public interface PublishedLookup {
        Optional<Published> find(Target target, Long version);
    }

    /** Read-only wire-independent projection; never contains a draft or stored head metadata. */
    public record Published(Target target, long version, String canonicalSha256, byte[] canonicalBytes) {
        public Published {
            Objects.requireNonNull(target);
            canonicalBytes = canonicalBytes.clone();
        }
        @Override public byte[] canonicalBytes() { return canonicalBytes.clone(); }
    }

    public Optional<Published> read(Caller caller, Target target, Long version) {
        authorizeRead(caller, target);
        if (version != null && version < 1) throw new IllegalArgumentException("Invalid published version");
        return lookup.find(target, version).map(publication -> verified(target, version, publication));
    }

    private static Published verified(Target target, Long version, Published publication) {
        if (!target.equals(publication.target())) throw new Forbidden();
        if (publication.version() < 1 || (version != null && publication.version() != version)) throw corrupt();
        var bytes = publication.canonicalBytes();
        StudioDraftDecoder.Decoded decoded;
        try {
            decoded = StudioDraftDecoder.decode(bytes);
        } catch (IllegalArgumentException malformed) {
            throw corrupt();
        }
        if (!target.screenKey().equals(decoded.facts().screenKey())
                || !decoded.facts().canonicalSha256().equals(publication.canonicalSha256())
                || !Arrays.equals(bytes, decoded.canonicalBytes())) throw corrupt();
        return publication;
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException("Invalid stored Studio publication");
    }
}
