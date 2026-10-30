package com.mightyoung.softwarefactory.dualprevention;

public final class FindingNotVisibleException extends RuntimeException {

    public FindingNotVisibleException() {
        super("The finding is not visible");
    }
}
