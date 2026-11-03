package com.mightyoung.softwarefactory.dualprevention;

/** Finite dependency failure; no database or session detail is exposed to the client. */
public final class FindingDefinitionUnavailableException extends RuntimeException {
    public FindingDefinitionUnavailableException() { super("The Finding definition snapshot is unavailable"); }
}
