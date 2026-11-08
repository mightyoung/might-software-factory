package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class FindingReviewHistoryCursorTest {
    final UUID tenant=UUID.randomUUID(),id=UUID.randomUUID(),member=UUID.randomUUID(),org=UUID.randomUUID();
    final FindingReadScope scope=new FindingReadScope(UUID.randomUUID(),tenant,member,Set.of(org));
    @Test void typedOriginsRoundTripWithStableIdentity() {
        for(String kind:List.of("LEGACY_CONFIRMATION","REVIEW_DECISION","REPORTER_SUPPLEMENT")) {
            String entryId=switch(kind) {case "LEGACY_CONFIRMATION" -> "legacy-confirmation:"+id+":PERSON";case "REVIEW_DECISION" -> "review-decision:"+id+":1:PERSON";default -> "reporter-supplement:"+UUID.randomUUID();};
            var entry=new FindingReviewHistoryRepository.Entry(entryId,kind,1,null,null,member,OffsetDateTime.parse("2026-01-02T00:00:00.123456Z"),null,null,null);
            String token=FindingReviewHistoryService.encode(entry,scope,id,20);
            assertThat(FindingReviewHistoryService.decode(token,scope,id,20)).isEqualTo(new FindingReviewHistoryRepository.Cursor(1,entry.occurredAt(),kind,entryId));
            assertThatThrownBy(() -> FindingReviewHistoryService.decode(token,scope,UUID.randomUUID(),20)).isInstanceOf(RuntimeException.class);
            String[] parts=token.split("\\.");
            String forged=parts[0].substring(0,parts[0].length()-1)+(parts[0].endsWith("A")?"B":"A")+"."+parts[1];
            assertThatThrownBy(() -> FindingReviewHistoryService.decode(forged,scope,id,20)).isInstanceOf(RuntimeException.class);
            var foreign=new FindingReadScope(scope.actorId(),UUID.randomUUID(),member,Set.of(org));
            assertThatThrownBy(() -> FindingReviewHistoryService.decode(token,foreign,id,20)).isInstanceOf(RuntimeException.class);
            var revoked=new FindingReadScope(scope.actorId(),tenant,member,Set.of(UUID.randomUUID()));
            assertThatThrownBy(() -> FindingReviewHistoryService.decode(token,revoked,id,20)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void malformedOriginAndUnboundedCursorAreRejected() {
        for(String value:List.of("", "!", "x".repeat(4097), Base64.getUrlEncoder().withoutPadding().encodeToString("foreign".getBytes()))) {
            assertThatThrownBy(() -> FindingReviewHistoryService.decode(value,scope,id,20)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void unionIsAppendOnlyAndPreservesLegacyNullGrade() {
        assertThat(JooqFindingReviewHistoryRepository.HISTORY_SQL).contains("union all","finding_confirmation","finding_review_decision","finding_report_supplement","null::varchar recorded_grade");
        assertThat(JooqFindingReviewHistoryRepository.HISTORY_SQL.toLowerCase()).doesNotContain("insert ","update ","delete ","tenant_member","account");
    }
}
