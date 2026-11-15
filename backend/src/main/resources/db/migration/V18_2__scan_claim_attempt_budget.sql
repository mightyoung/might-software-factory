-- A scan claim requires attempt_no=old.attempt_no+1. Its allowlist must permit that exact change.
-- Preserve every other V18 transition predicate and immutable declaration check.
create or replace function public.check_finding_ingestion_transition() returns trigger language plpgsql as $$
declare q public.file_ingestion_allocation%rowtype;
        baseline public.file_ingestion_allocation_event%rowtype;
        proof public.file_ingestion_scan_proof%rowtype;
        receipt public.file_ingestion_content_receipt%rowtype;
        allowed text[];
        unused_bundle boolean;
        execution public.file_ingestion_execution%rowtype;
begin
    if tg_op='DELETE' then
        raise exception 'Upload intent declarations cannot be deleted' using errcode='23514';
    end if;
    if tg_op='INSERT' then
        if new.state<>'INTENT_CREATED' or new.technical_version<>0 or new.lease_epoch<>0 or new.attempt_no<>0
           or new.current_execution_id is not null or new.attempt_id is not null or new.reserved_scan_attempt_id is not null or new.cancel_requested
           or new.lease_token is not null or new.lease_until is not null or new.heartbeat_at is not null
           or new.next_attempt_at is not null or new.quarantine_allocation_id is not null
           or new.immutable_allocation_id is not null or new.scan_proof_id is not null
           or new.content_receipt_id is not null or new.scanner_generation_sha256 is not null
           or new.last_error_code is not null or new.technical_updated_at<>new.created_at then
            raise exception 'New upload intent cannot seed ingestion effects' using errcode='23514';
        end if;
        return new;
    end if;
    if row(new.tenant_id,new.id,new.owner_account_id,new.owner_actor_id,new.owner_member_id,new.operation,
           new.idempotency_key,new.fingerprint_version,new.payload_sha256,new.finding_id,new.round_no,
           new.notice_id,new.evidence_set_id,new.expected_finding_version,new.expected_evidence_revision,
           new.filename,new.declared_media_type,new.declared_size_bytes,new.producer_configuration_version,
           new.upload_policy_version,new.created_at,new.expires_at)
       is distinct from row(old.tenant_id,old.id,old.owner_account_id,old.owner_actor_id,old.owner_member_id,old.operation,
           old.idempotency_key,old.fingerprint_version,old.payload_sha256,old.finding_id,old.round_no,
           old.notice_id,old.evidence_set_id,old.expected_finding_version,old.expected_evidence_revision,
           old.filename,old.declared_media_type,old.declared_size_bytes,old.producer_configuration_version,
           old.upload_policy_version,old.created_at,old.expires_at)
       or new.technical_version<>old.technical_version+1 or new.technical_updated_at<old.technical_updated_at
       or (old.cancel_requested and not new.cancel_requested) or new.state='FINALIZED' then
        raise exception 'Upload intent immutable declaration or version is invalid' using errcode='23514';
    end if;
    if new.state=old.state and not old.cancel_requested and new.cancel_requested then
        allowed=array['technical_version','technical_updated_at','cancel_requested'];
    elsif new.state=old.state and old.state in ('RECEIVING','SCANNING') then
        if old.cancel_requested or old.lease_until<=statement_timestamp() or new.heartbeat_at<old.heartbeat_at
           or new.lease_until<old.lease_until then
            raise exception 'Upload ingestion heartbeat is stale' using errcode='23514';
        end if;
        allowed=array['technical_version','technical_updated_at','heartbeat_at','lease_until'];
    elsif new.state=old.state and old.state in ('FAILED','REJECTED','CANCELLED','EXPIRED') then
        -- A disposal callback may advance the technical version only with an exact same-transaction event.
        if not exists(select 1 from public.file_ingestion_allocation_event where tenant_id=old.tenant_id
            and intent_id=old.id and technical_version=old.technical_version
            and append_transaction_id=pg_current_xact_id()
            and event_kind in ('RESIZE_CONFIRMED','RECOVERY_OBSERVED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')) then
            raise exception 'Upload terminal checkpoint lacks its allocation event' using errcode='23514';
        end if;
        allowed=array['technical_version','technical_updated_at'];
    elsif new.state=old.state and old.state='RETRY_WAIT' then
        -- Preserve every phase/attempt/epoch/budget/lease/reference field; only the current recovery checkpoint advances.
        -- Exact tenant/intent row is already held before files events by the guarded caller and invoker triggers.
        if not exists(
            select 1 from public.file_ingestion_allocation_event e
            join public.file_ingestion_allocation a on a.tenant_id=e.tenant_id and a.id=e.allocation_id
            left join public.file_ingestion_execution x on x.tenant_id=e.tenant_id and x.execution_id=e.execution_id
            where e.tenant_id=old.tenant_id and e.intent_id=old.id and a.intent_id=old.id
              and e.append_transaction_id=pg_current_xact_id() and e.technical_version=old.technical_version
              and e.event_kind in ('RECOVERY_OBSERVED','DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')
              and row(e.intent_id,e.file_id,e.attempt_id,e.allocation_kind,e.reservation_lease_epoch)
                  is not distinct from row(a.intent_id,a.file_id,a.attempt_id,a.kind,a.reservation_lease_epoch)
              and (e.execution_id is null or (x.descriptor_sha256=e.descriptor_sha256 and x.intent_id=old.id
                    and x.file_id=a.file_id and (a.kind='QUARANTINE' and x.quarantine_allocation_id=a.id
                        or a.kind<>'QUARANTINE' and x.scan_attempt_id=a.attempt_id)
                    and (a.kind<>'SCANNER_SCRATCH' or x.scratch_allocation_id=a.id)))
              and (e.event_kind='DISPOSAL_CLAIMED' or
                    e.expected_current_technical_version=old.technical_version and e.expected_intent_lease_epoch=old.lease_epoch)
              and ((e.recovery_reason='SUPERSEDED' and a.id is distinct from old.quarantine_allocation_id
                    and a.id is distinct from old.immutable_allocation_id and a.attempt_id is distinct from old.attempt_id
                    and a.attempt_id is distinct from old.reserved_scan_attempt_id)
                   or (e.recovery_reason='EXPIRED_WORK' and old.lease_token is null
                    and old.lease_epoch>case when e.execution_id is null then a.reservation_lease_epoch else x.claim_epoch end))
        ) then
            raise exception 'Upload retry recovery checkpoint lacks its current eligible allocation event' using errcode='23514';
        end if;
        allowed=array['technical_version','technical_updated_at'];
    elsif old.state='INTENT_CREATED' and new.state in ('CANCELLED','EXPIRED') then
        -- The UPDATE owns the exact parent lock before this fresh RC allocation-absence check.
        -- Allocation-first makes this fail; terminal-first makes the waiting allocation INSERT fail.
        if old.attempt_no<>0 or new.attempt_no<>0 or old.lease_epoch<>0 or new.lease_epoch<>0
           or old.last_error_code is not null
           or old.attempt_id is not null or new.attempt_id is not null
           or old.reserved_scan_attempt_id is not null or new.reserved_scan_attempt_id is not null
           or old.lease_token is not null or new.lease_token is not null
           or old.lease_until is not null or new.lease_until is not null
           or old.heartbeat_at is not null or new.heartbeat_at is not null
           or old.next_attempt_at is not null or new.next_attempt_at is not null
           or old.quarantine_allocation_id is not null or new.quarantine_allocation_id is not null
           or old.immutable_allocation_id is not null or new.immutable_allocation_id is not null
           or old.scan_proof_id is not null or new.scan_proof_id is not null
           or old.content_receipt_id is not null or new.content_receipt_id is not null
           or old.scanner_generation_sha256 is not null or new.scanner_generation_sha256 is not null
           or old.current_execution_id is not null or new.current_execution_id is not null
           or exists(select 1 from public.file_ingestion_allocation where tenant_id=old.tenant_id and intent_id=old.id) then
            raise exception 'Initial terminal transition requires an empty unallocated intent' using errcode='23514';
        end if;
        if (new.state='CANCELLED' and (not new.cancel_requested or new.last_error_code is distinct from 'CANCELLED'))
           or (new.state='EXPIRED' and (old.cancel_requested or new.cancel_requested or old.expires_at>statement_timestamp()
                or new.last_error_code is distinct from 'EXPIRED')) then
            raise exception 'Initial terminal transition lacks its current cancellation or expiry predicate' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','cancel_requested','last_error_code'];
    elsif old.state in ('INTENT_CREATED','RETRY_WAIT') and new.state='RECEIVING' then
        if old.cancel_requested or old.attempt_no>=3 or old.expires_at<=statement_timestamp()
           or (old.state='RETRY_WAIT' and (old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()))
           or new.attempt_id is null or new.reserved_scan_attempt_id is null
           or new.attempt_id=new.reserved_scan_attempt_id or new.attempt_id is not distinct from old.attempt_id
           or new.reserved_scan_attempt_id is not distinct from old.reserved_scan_attempt_id
           or new.lease_epoch<>old.lease_epoch+1 or new.attempt_no<>old.attempt_no
           or new.lease_token is null or new.lease_token is not distinct from old.lease_token
           or new.lease_until<=statement_timestamp() or new.quarantine_allocation_id is null
           or new.immutable_allocation_id is not null or new.scan_proof_id is not null
           or new.content_receipt_id is not null or new.scanner_generation_sha256 is not null or new.current_execution_id is not null then
            raise exception 'Upload receive claim fence is invalid' using errcode='23514';
        end if;
        if old.quarantine_allocation_id is not null and exists(select 1 from public.file_ingestion_allocation_event
            where tenant_id=old.tenant_id and allocation_id=old.quarantine_allocation_id
              and event_kind='RETAIN' and observation_kind='COMPLETE_QUARANTINE')
            and not (old.state='RETRY_WAIT' and old.last_error_code is not distinct from 'QUARANTINE_NOT_REUSABLE') then
            raise exception 'A complete quarantine cannot be overwritten by another receive' using errcode='23514';
        end if;
        select * into strict q from public.file_ingestion_allocation where tenant_id=new.tenant_id and id=new.quarantine_allocation_id;
        if q.intent_id<>new.id or q.owner_member_id<>new.owner_member_id or q.kind<>'QUARANTINE'
           or q.attempt_id<>new.attempt_id or q.reservation_lease_epoch<>new.lease_epoch
           or q.reserved_bytes<new.declared_size_bytes
           or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.id
               and file_id=q.file_id and owner_member_id=new.owner_member_id and attempt_id=new.reserved_scan_attempt_id
               and reservation_lease_epoch=new.lease_epoch and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
            raise exception 'Upload receive reservation bundle is invalid' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','attempt_id','reserved_scan_attempt_id','lease_epoch',
            'lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code','quarantine_allocation_id',
            'immutable_allocation_id','scan_proof_id','content_receipt_id','scanner_generation_sha256','current_execution_id'];
    elsif old.state in ('QUARANTINED','RETRY_WAIT') and new.state='SCANNING' then
        if old.cancel_requested or old.attempt_no>=3 or old.expires_at<=statement_timestamp()
           or (old.state='RETRY_WAIT' and (old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()))
           or new.lease_epoch<>old.lease_epoch+1 or new.attempt_no<>old.attempt_no+1
           or new.lease_token is null or new.lease_token is not distinct from old.lease_token
           or new.lease_until<=statement_timestamp() or new.attempt_id is null
           or new.attempt_id is distinct from new.reserved_scan_attempt_id
           or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=new.tenant_id
               and intent_id=new.id and attempt_id=new.attempt_id and allocation_kind='SCANNER_SCRATCH'
               and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED')) then
            raise exception 'Upload scan claim fence is invalid' using errcode='23514';
        end if;
        select * into strict q from public.file_ingestion_allocation where tenant_id=old.tenant_id and id=old.quarantine_allocation_id;
        select * into strict baseline from public.file_ingestion_allocation_event where tenant_id=old.tenant_id
            and allocation_id=q.id and event_kind='RETAIN' and observation_kind='COMPLETE_QUARANTINE' order by event_no limit 1;
        if baseline.observation_kind<>'COMPLETE_QUARANTINE' or baseline.observed_size_bytes<>new.declared_size_bytes
           or baseline.observed_media_type<>new.declared_media_type
           or exists(select 1 from public.file_ingestion_allocation_event where tenant_id=old.tenant_id and allocation_id=q.id
               and event_kind in ('DISPOSAL_CLAIMED','DISPOSAL_CONFIRMED','TRANSFER_FINALIZED')) then
            raise exception 'Upload scan source is unavailable' using errcode='23514';
        end if;
        unused_bundle=old.attempt_id=q.attempt_id and old.reserved_scan_attempt_id is distinct from q.attempt_id;
        if unused_bundle then
            if new.attempt_id is distinct from old.reserved_scan_attempt_id
               or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.id
                   and file_id=q.file_id and owner_member_id=new.owner_member_id and attempt_id=new.attempt_id
                   and reservation_lease_epoch=q.reservation_lease_epoch and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
                raise exception 'Upload unused scan reservation is invalid' using errcode='23514';
            end if;
        else
            if old.state<>'RETRY_WAIT' or new.attempt_id is not distinct from old.attempt_id
               or new.attempt_id is not distinct from old.reserved_scan_attempt_id
               or (select count(*) from public.file_ingestion_allocation where tenant_id=new.tenant_id and intent_id=new.id
                   and file_id=q.file_id and owner_member_id=new.owner_member_id and attempt_id=new.attempt_id
                   and reservation_lease_epoch=new.lease_epoch and kind in ('SCANNER_SCRATCH','IMMUTABLE','RECEIPT'))<>3 then
                raise exception 'Upload retry requires fresh scan reservations' using errcode='23514';
            end if;
        end if;
        select * into strict execution from public.file_ingestion_execution where tenant_id=new.tenant_id and execution_id=new.current_execution_id;
        if row(execution.intent_id,execution.file_id,execution.source_attempt_id,execution.scan_attempt_id,execution.quarantine_allocation_id,
               execution.claim_epoch,execution.claim_token,execution.claim_technical_version)
           is distinct from row(new.id,q.file_id,q.attempt_id,new.attempt_id,q.id,new.lease_epoch,new.lease_token,new.technical_version) then
            raise exception 'Upload scan claim requires its exact immutable execution descriptor' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','attempt_no','attempt_id','reserved_scan_attempt_id','lease_epoch',
            'lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code','current_execution_id'];
    elsif old.state='RECEIVING' and new.state='QUARANTINED' then
        if old.cancel_requested or old.lease_until<=statement_timestamp() then
            raise exception 'Upload receive checkpoint is stale' using errcode='23514';
        end if;
        select * into strict baseline from public.file_ingestion_allocation_event where tenant_id=old.tenant_id
            and allocation_id=old.quarantine_allocation_id and event_kind='RETAIN' and observation_kind='COMPLETE_QUARANTINE' order by event_no limit 1;
        if baseline.observation_kind<>'COMPLETE_QUARANTINE' or baseline.attempt_id<>old.attempt_id
           or baseline.observed_size_bytes<>old.declared_size_bytes or baseline.observed_media_type<>old.declared_media_type then
            raise exception 'Upload receive checkpoint lacks its original complete baseline' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code'];
    elsif old.state='SCANNING' and new.state='VERIFIED_PENDING_FINALIZATION' then
        if old.cancel_requested or old.lease_until<=statement_timestamp() then
            raise exception 'Upload scan checkpoint is stale' using errcode='23514';
        end if;
        select * into strict proof from public.file_ingestion_scan_proof where tenant_id=old.tenant_id and id=new.scan_proof_id;
        select * into strict receipt from public.file_ingestion_content_receipt where tenant_id=old.tenant_id and id=new.content_receipt_id;
        if proof.execution_id is distinct from old.current_execution_id or proof.intent_id<>new.id or proof.scan_attempt_id<>old.attempt_id or proof.quarantine_allocation_id<>old.quarantine_allocation_id
           or receipt.scan_proof_id<>proof.id or receipt.scan_attempt_id<>old.attempt_id
           or receipt.immutable_allocation_id is distinct from new.immutable_allocation_id
           or proof.generation_sha256 is distinct from new.scanner_generation_sha256
           or proof.expires_at<=statement_timestamp() or receipt.expires_at<=statement_timestamp() then
            raise exception 'Upload verified checkpoint provenance is invalid' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_token','lease_until','heartbeat_at','next_attempt_at',
            'last_error_code','immutable_allocation_id','scan_proof_id','content_receipt_id','scanner_generation_sha256'];
    elsif old.state in ('RECEIVING','SCANNING') and new.state='RETRY_WAIT' and old.lease_until<=statement_timestamp() then
        if new.lease_epoch<>old.lease_epoch+1 or new.last_error_code is distinct from 'LEASE_LOST' then
            raise exception 'Upload recovery must permanently fence the expired execution' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_epoch','lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code'];
    elsif old.state in ('RECEIVING','SCANNING') and new.state in ('RETRY_WAIT','FAILED','REJECTED','CANCELLED','EXPIRED') then
        if old.lease_until<=statement_timestamp() or (new.state='REJECTED' and old.state<>'SCANNING') then
            raise exception 'Upload executor terminal checkpoint is stale' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','lease_token','lease_until','heartbeat_at','next_attempt_at','last_error_code'];
    elsif old.state in ('QUARANTINED','VERIFIED_PENDING_FINALIZATION','RETRY_WAIT') and new.state in ('CANCELLED','EXPIRED') then
        if (new.state='CANCELLED' and not new.cancel_requested)
           or (new.state='EXPIRED' and old.expires_at>statement_timestamp()) then
            raise exception 'Upload terminal predicate is not current' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','cancel_requested','next_attempt_at','last_error_code'];
    elsif old.state='RETRY_WAIT' and new.state='FAILED' then
        if old.attempt_no<>3 or old.next_attempt_at is null or old.next_attempt_at>statement_timestamp()
           or new.last_error_code is distinct from 'ATTEMPTS_EXHAUSTED' then
            raise exception 'Upload terminal observation is not due' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','next_attempt_at','last_error_code'];
    elsif old.state='VERIFIED_PENDING_FINALIZATION' and new.state='RETRY_WAIT' then
        if old.cancel_requested or new.last_error_code is null then
            raise exception 'Upload re-verification predicate is invalid' using errcode='23514';
        end if;
        allowed=array['state','technical_version','technical_updated_at','next_attempt_at','last_error_code'];
    else
        raise exception 'Upload ingestion transition is not allowed' using errcode='23514';
    end if;
    if new.state in ('FAILED','REJECTED','RETRY_WAIT') and new.last_error_code is null then
        raise exception 'Upload error state requires a safe reason' using errcode='23514';
    end if;
    if (to_jsonb(new)-allowed) is distinct from (to_jsonb(old)-allowed)
       or (new.state='CANCELLED' and (not new.cancel_requested or new.last_error_code is distinct from 'CANCELLED'))
       or (new.state='EXPIRED' and (new.expires_at>statement_timestamp() or new.last_error_code is distinct from 'EXPIRED'))
       or (new.state in ('QUARANTINED','VERIFIED_PENDING_FINALIZATION') and
           (new.lease_token is not null or new.lease_until is not null or new.heartbeat_at is not null)) then
        raise exception 'Upload ingestion transition changed unrelated facts' using errcode='23514';
    end if;
    return new;
end $$;
