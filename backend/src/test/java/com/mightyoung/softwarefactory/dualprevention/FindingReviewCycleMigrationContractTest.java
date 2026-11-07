package com.mightyoung.softwarefactory.dualprevention;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** Source-shape checks only: these do not establish PostgreSQL execution or runtime privileges. */
class FindingReviewCycleMigrationContractTest {
    private String migration() throws Exception {
        return Files.readString(Path.of("src/main/resources/db/migration/V17__finding_review_cycles_and_supplements.sql"));
    }

    @Test void newHistoryColumnsAreExplicitlyRequiredExceptDecisionGrade() throws Exception {
        String sql = migration();
        for (String table : List.of("finding_review_cycle", "finding_review_decision", "finding_report_supplement", "finding_command_participant")) {
            var matcher = Pattern.compile("create table public\\." + table + " \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
            assertThat(matcher.find()).as(table).isTrue();
            for (String line : matcher.group(1).split("\n")) {
                String column = line.strip();
                if (column.matches("[a-z_]+ (uuid|integer|bigint|text|timestamptz|varchar\\([0-9]+\\)).*")) {
                    if (column.startsWith("confirmed_grade ")) assertThat(column).doesNotContain("not null");
                    else assertThat(column).as(table + ": " + column).contains("not null");
                }
            }
            assertThat(sql).contains("alter table public." + table + " enable row level security",
                    "alter table public." + table + " force row level security",
                    "create policy " + table + "_tenant_isolation",
                    "create trigger " + table + "_append_only before update or delete");
        }
        assertThat(sql.toLowerCase()).doesNotContain("grant ", "on delete cascade", "disable trigger");
    }

    @Test void reviewConstraintsSeparateGradeNullabilityAndReviewFromRemediation() throws Exception {
        String sql = migration();
        assertThat(sql).contains("confirmed_grade is not null and confirmed_grade in ('GENERAL', 'MAJOR')",
                "and confirmed_grade is null", "next_review_cycle = review_cycle + 1",
                "and d.decision = 'RETURN_FOR_INFORMATION' and f.created_by_member_id = new.reporter_member_id",
                "d.review_cycle = old.review_cycle and d.stage = 'PERSON' and d.decision = 'APPROVE'",
                "if old.state not in ('PERSON_CONFIRM_PENDING', 'SAFETY_CONFIRM_PENDING') then return null; end if;",
                "if new.review_cycle <> old.review_cycle then",
                "deferrable initially deferred",
                "if new is not distinct from old then return null; end if;",
                "new.created_by_member_id is distinct from old.created_by_member_id",
                "new.review_protocol = 'CYCLE_V1' and new.description is distinct from old.description",
                "case when ascii(c) > 65535 then 2 else 1 end",
                "finding_review_utf16_length(value) between 1 and 4000");
    }

    @Test void historyAndUncoveredReverseForeignKeysHaveNamedIndexesWithoutRedundantKeyIndexes() throws Exception {
        String sql = migration().replaceAll("\\s+", " ");
        assertThat(sql).contains(
                "create index finding_review_decision_history_idx on public.finding_review_decision (tenant_id, finding_id, review_cycle, decided_at, stage)",
                "create index finding_report_supplement_history_idx on public.finding_report_supplement (tenant_id, finding_id, review_cycle, submitted_at, id)",
                "create index finding_review_cycle_opened_command_idx on public.finding_review_cycle (tenant_id, opened_by_command_id)",
                "create index finding_review_decision_actor_idx on public.finding_review_decision (tenant_id, actor_member_id)",
                "create index finding_report_supplement_next_cycle_idx on public.finding_report_supplement (tenant_id, finding_id, next_review_cycle)",
                "create index finding_report_supplement_reporter_idx on public.finding_report_supplement (tenant_id, reporter_member_id)",
                "create index finding_command_participant_finding_idx on public.finding_command_participant (tenant_id, finding_id)",
                "create index finding_command_participant_actor_idx on public.finding_command_participant (tenant_id, actor_member_id)");
        assertThat(Pattern.compile("create index ").matcher(sql).results().count()).isEqualTo(8);
        // Existing PK/unique indexes already serve these exact/prefix lookups.
        assertThat(sql).doesNotContain(
                "on public.finding_review_cycle (tenant_id, finding_id)",
                "on public.finding_review_decision (tenant_id, command_id)",
                "on public.finding_report_supplement (tenant_id, command_id)",
                "on public.finding_command_participant (tenant_id, command_id)");
    }

    @Test void receiptUpgradePreservesHistoricalNullButRequiresAllNewCompletionsAndFreezesHistory() throws Exception {
        String sql = migration();
        assertThat(sql).contains("completed_at is not null or result_review_cycle is null",
                "old.completed_at is not null and new is distinct from old",
                "tg_op = 'INSERT' or old.completed_at is null",
                "and new.result_review_cycle is null",
                "create trigger finding_confirmation_append_only before update or delete",
                "create trigger finding_transition_append_only before update or delete");
        assertThat(sql).doesNotContain("update public.finding_command_receipt", "update public.finding set");
    }
}
