package com.mightyoung.softwarefactory.files;

/** Public lifecycle states defined by the private file storage contract. */
public enum FileObjectState {
    UPLOADING,
    QUARANTINED,
    READY,
    DELETING,
    FAILED
}
