@org.springframework.modulith.ApplicationModule(
        displayName = "Platform administration",
        allowedDependencies = {"applicationregistry::query", "applicationregistry::navigation", "authorization", "identity", "tenancy"}
)
package com.mightyoung.softwarefactory.platformadmin;
