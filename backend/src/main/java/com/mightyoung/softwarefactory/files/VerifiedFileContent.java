package com.mightyoung.softwarefactory.files;

import java.io.IOException;
import java.io.OutputStream;

/** A preverified, pinned storage-version handle. Closing it releases all underlying resources. */
public interface VerifiedFileContent extends AutoCloseable {

    long sizeBytes();

    void transferTo(OutputStream output) throws IOException;

    @Override
    void close() throws IOException;
}
