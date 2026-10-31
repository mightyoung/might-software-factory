-- Add reporter-supplied finding metadata without inventing values for existing records.
-- The command service is the sole runtime writer of the server-computed backfill flag.
-- Location non-blank checks use the explicit Unicode White_Space code points, independent of locale.
alter table public.finding
    add column source_category varchar(32),
    add column urgency varchar(16),
    add column location text,
    add column interim_measure text,
    add column backfill boolean,
    add constraint finding_source_category_valid
        check (source_category is null or source_category in (
            'FIELD_OBSERVATION', 'INSPECTION', 'PERSON_REPORT')),
    add constraint finding_urgency_valid
        check (urgency is null or urgency in ('NORMAL', 'URGENT')),
    add constraint finding_location_length
        check (location is null or (
            char_length(location) between 1 and 200
            and translate(location,
                chr(9) || chr(10) || chr(11) || chr(12) || chr(13) || chr(32) || chr(133)
                || chr(160) || chr(5760) || chr(8192) || chr(8193) || chr(8194) || chr(8195)
                || chr(8196) || chr(8197) || chr(8198) || chr(8199) || chr(8200) || chr(8201)
                || chr(8202) || chr(8232) || chr(8233) || chr(8239) || chr(8287) || chr(12288),
                '') <> '')),
    add constraint finding_interim_measure_length
        check (interim_measure is null or char_length(interim_measure) <= 2000),
    add constraint finding_report_metadata_legacy_or_complete
        check (
            (source_category is null and urgency is null and location is null
                and interim_measure is null and backfill is null)
            or
            (source_category is not null and urgency is not null and location is not null
                and backfill is not null)
        );

comment on column public.finding.backfill is
    'Server-computed: true only when discovered_at is strictly more than 24 hours before backend submission time; null only for legacy rows.';
