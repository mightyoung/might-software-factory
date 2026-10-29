package com.mightyoung.softwarefactory;

import com.mightyoung.modulithcontractprobe.ModuleContractProbeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleDependencyClosureTest {

    @Test
    void emptyAllowedDependenciesRejectsAReferenceToAnotherModule() {
        Violations violations = ApplicationModules.of(ModuleContractProbeApplication.class, location -> true)
                .detectViolations();

        assertThat(violations.hasViolations()).isTrue();
        assertThat(violations.getMessages())
                .anySatisfy(message -> assertThat(message)
                        .contains("identity")
                        .contains("authorization"));
    }
}
