package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

/** Structural source gate; this deliberately does not claim PostgreSQL migration acceptance. */
class IdentityLifecycleMigrationContractTest {
    private String sql()throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V19__identity_lifecycle_capabilities.sql")){
            assertThat(input).isNotNull();return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void capabilityScopeCannotUpgradeNullLegacyBindingsOrBeObtainedByPermissionRename()throws Exception {
        String ddl=sql();assertThat(ddl).contains("old.kind='IDENTITY_TENANT' or new.kind='IDENTITY_TENANT'","new.kind is distinct from old.kind","scope_kind is distinct from 'IDENTITY_TENANT'","identity.invitation.create","identity.invitation.revoke","new.resource_type='account-invitation'","new.action='create'","new.action='revoke'","reserved identity permission tuples require fresh definitions");
        assertThat(ddl).doesNotContain("security definer","grant ","insert into public.permission","insert into public.role_grant");
    }
    @Test void globalIdentityHistoryIsHashOnlyAppendOnlyAndOneUseBoundToCompletedReceipts()throws Exception {
        String ddl=sql();assertThat(ddl).contains("secret_digest varchar(64)","fingerprint_key_id varchar(64)","fingerprint varchar(64)","identity_receipt_immutable","identity_delivery_immutable","identity_event_immutable","old.state<>'ISSUED'","new.state not in ('CONSUMED','REVOKED','EXPIRED')","deferrable initially deferred","consumed challenge requires its exact completed receipt","verified contact requires exact invitation consumption");
        assertThat(ddl).doesNotContain("secret_token","plaintext","encrypted_token","enable row level security","force row level security");
    }
    @Test void deliveryDeadlineIsOnlyAttemptMetadataAndTerminalUnknownCannotBeUpgraded()throws Exception {
        String ddl=sql();String challenge=ddl.substring(ddl.indexOf("create table public.identity_credential_challenge"),ddl.indexOf("create table public.identity_lifecycle_command_receipt"));
        assertThat(challenge).doesNotContain("origin_instance_id","delivery_deadline_at");
        assertThat(ddl).contains("origin_instance_id uuid not null","delivery_deadline_at<=created_at+interval '6 minutes'","new.origin_instance_id<>previous.origin_instance_id","new.delivery_deadline_at<>previous.delivery_deadline_at","previous.state='PREPARED'","previous.state='DISPATCHING'","new.delivery_deadline_at<=statement_timestamp()");
        assertThat(ddl).doesNotContain("previous.state='UNKNOWN'");
    }
    @Test void existingRecipientRequiresExactVerifiedContactInsteadOfLoginInference()throws Exception {
        String ddl=sql();assertThat(ddl).contains("existing recipient verified binding does not match","c.account_id=new.target_account_id","c.version=new.issued_contact_version","c.channel=new.channel and c.destination=new.destination");
    }
}
