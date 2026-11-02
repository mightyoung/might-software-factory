package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.LanPostgresCompatibilityIT.DatabaseTarget;
import com.mightyoung.softwarefactory.LanPostgresCompatibilityIT.DatabaseTarget.RolePreflight;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure role-state validation; no database connections or environment credentials are used. */
class LanPostgresRolePreflightTest {

    private static final String DATABASE = "might_software_factory_role_gate_test";

    @ParameterizedTest(name = "migration role = {0}")
    @ValueSource(booleans = {true, false})
    void acceptsOnlyTheRequiredPrivilegesForEachIdentity(boolean migrationRole) {
        RolePreflight state = validRole(migrationRole);

        assertThatCode(() -> DatabaseTarget.assertRolePreflight(
                state, DATABASE, state.currentUser(), migrationRole)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "migration role = {0}: rejects {1}")
    @MethodSource("unsafeRoleStates")
    void rejectsEachUnsafeAttributeIndependently(boolean migrationRole, UnsafeAttribute attribute) {
        RolePreflight valid = validRole(migrationRole);
        RolePreflight unsafe = withUnsafeAttribute(valid, attribute);

        assertThatThrownBy(() -> DatabaseTarget.assertRolePreflight(
                unsafe, DATABASE, valid.currentUser(), migrationRole))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(attribute.expectedMessage(migrationRole));
    }

    private static Stream<Arguments> unsafeRoleStates() {
        return Stream.of(true, false).flatMap(migrationRole -> Arrays.stream(UnsafeAttribute.values())
                .map(attribute -> Arguments.of(migrationRole, attribute)));
    }

    private static RolePreflight validRole(boolean migrationRole) {
        String role = migrationRole ? "probe_migrator" : "probe_application";
        return new RolePreflight(DATABASE, role, role, "database_owner", "schema_owner",
                false, false, false, false, false, false, false, true, true, migrationRole);
    }

    private static RolePreflight withUnsafeAttribute(RolePreflight state, UnsafeAttribute attribute) {
        // Change exactly one field so every rejection protects its own independent gate.
        return new RolePreflight(
                attribute == UnsafeAttribute.DATABASE ? "another_database" : state.databaseName(),
                attribute == UnsafeAttribute.CURRENT_USER ? "another_role" : state.currentUser(),
                attribute == UnsafeAttribute.SESSION_USER ? "another_role" : state.sessionUser(),
                attribute == UnsafeAttribute.DATABASE_OWNER ? state.currentUser() : state.databaseOwner(),
                attribute == UnsafeAttribute.SCHEMA_OWNER ? state.currentUser() : state.schemaOwner(),
                attribute == UnsafeAttribute.SUPERUSER || state.superuser(),
                attribute == UnsafeAttribute.BYPASS_RLS || state.bypassRls(),
                attribute == UnsafeAttribute.CREATE_DATABASE || state.createDatabase(),
                attribute == UnsafeAttribute.CREATE_ROLE || state.createRole(),
                attribute == UnsafeAttribute.REPLICATION || state.replication(),
                attribute == UnsafeAttribute.INHERIT || state.inherit(),
                attribute == UnsafeAttribute.ROLE_MEMBERSHIP || state.memberOfAnotherRole(),
                attribute != UnsafeAttribute.MISSING_CONNECT && state.connect(),
                attribute != UnsafeAttribute.MISSING_SCHEMA_USAGE && state.schemaUsage(),
                attribute == UnsafeAttribute.WRONG_SCHEMA_CREATE ? !state.schemaCreate() : state.schemaCreate());
    }

    private enum UnsafeAttribute {
        DATABASE,
        CURRENT_USER,
        SESSION_USER,
        DATABASE_OWNER,
        SCHEMA_OWNER,
        SUPERUSER,
        BYPASS_RLS,
        CREATE_DATABASE,
        CREATE_ROLE,
        REPLICATION,
        INHERIT,
        ROLE_MEMBERSHIP,
        MISSING_CONNECT,
        MISSING_SCHEMA_USAGE,
        WRONG_SCHEMA_CREATE;

        String expectedMessage(boolean migrationRole) {
            String label = migrationRole ? "migration" : "application";
            return switch (this) {
                case DATABASE -> "Connected PostgreSQL database does not match the trusted test target";
                case CURRENT_USER, SESSION_USER ->
                        "Connected PostgreSQL identity does not match the configured " + label + " role";
                case DATABASE_OWNER, SCHEMA_OWNER ->
                        "PostgreSQL " + label + " role must not own the target database or public schema";
                case SUPERUSER, BYPASS_RLS, CREATE_DATABASE, CREATE_ROLE, REPLICATION, INHERIT, ROLE_MEMBERSHIP ->
                        "PostgreSQL " + label + " role must be non-elevated, NOINHERIT, and have no memberships";
                case MISSING_CONNECT, MISSING_SCHEMA_USAGE, WRONG_SCHEMA_CREATE -> migrationRole
                        ? "Migration role requires database CONNECT and public USAGE/CREATE"
                        : "Application role requires database CONNECT and public USAGE, with no public CREATE";
            };
        }
    }
}
