@org.springframework.modulith.ApplicationModule(
        displayName = "Platform administration",
        allowedDependencies = {"applicationregistry::query", "authorization", "identity", "tenancy"}
)
package com.mightyoung.softwarefactory.platformadmin;
