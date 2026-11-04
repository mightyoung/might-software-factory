package com.mightyoung.softwarefactory.dualprevention.asset;

/** Must use the real persistent Modulith registry on the caller's exact command connection. */
public interface AssetFoundationEventPublisher {
    void publish(AssetFoundationChangedEvent event);
}
