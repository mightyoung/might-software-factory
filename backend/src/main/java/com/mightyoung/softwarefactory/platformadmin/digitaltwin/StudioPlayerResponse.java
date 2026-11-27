package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.util.Map;
import java.util.Optional;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant response mapping. No sessions, transport handlers, cache lookup, or conditional GET. */
public sealed interface StudioPlayerResponse permits StudioPlayerResponse.Content, StudioPlayerResponse.Failure {
    int status();
    Map<String, String> headers();

    static StudioPlayerResponse read(StudioPlayerPublication reader, Caller caller, Target target, Long version) {
        try {
            return fromPublication(reader.read(caller, target, version));
        } catch (Forbidden rejected) {
            return new Failure(MappedError.FORBIDDEN);
        } catch (StudioPlayerPublication.InvalidVersion rejected) {
            return new Failure(MappedError.INVALID_REQUEST);
        }
    }

    static StudioPlayerResponse fromPublication(Optional<StudioPlayerPublication.Published> found) {
        return found.isPresent() ? new Content(found.get()) : new Failure(MappedError.NOT_FOUND);
    }

    final class Content implements StudioPlayerResponse {
        private final StudioPlayerPublication.Published publication;
        private Content(StudioPlayerPublication.Published publication) { this.publication = publication; }

        @Override public int status() { return 200; }
        @Override public Map<String, String> headers() {
            return Map.of("Content-Type", "application/json", "Cache-Control", "private, no-store",
                    "ETag", "\"" + publication.canonicalSha256() + "\"");
        }
        public byte[] bytes() { return publication.canonicalBytes(); }
    }

    enum MappedError {
        INVALID_REQUEST(400), FORBIDDEN(403), NOT_FOUND(404);
        private final int status;
        MappedError(int status) { this.status = status; }
    }

    record Failure(MappedError error) implements StudioPlayerResponse {
        @Override public int status() { return error.status; }
        @Override public Map<String, String> headers() { return Map.of("Cache-Control", "private, no-store"); }
    }
}
