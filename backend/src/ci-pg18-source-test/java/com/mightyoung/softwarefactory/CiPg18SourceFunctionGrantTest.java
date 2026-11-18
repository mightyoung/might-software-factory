package com.mightyoung.softwarefactory;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Static privilege-declaration refusal; actual effective ACL checks still require the real PG source lane. */
class CiPg18SourceFunctionGrantTest {
    private static String resource() throws Exception {
        try(var input=CiPg18SourceFunctionGrantTest.class.getResourceAsStream("/ci-pg18-source/runtime-grants.sql")) {
            assertThat(input).isNotNull();return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void exactClosureIncludesTwentyOrdinaryRiskHelpersAndTwoCheckPredicates() throws Exception {
        Set<String> functions=CiPg18SourceFixture.declaredFunctionGrants(resource());
        assertThat(functions).hasSize(22).contains("public.g014_instant_valid(timestamptz)","public.g014_text_valid(text,integer,boolean)");
        assertThat(functions.stream().filter(name->name.startsWith("public.g014r_")).count()).isEqualTo(20);
        assertThat(functions).noneMatch(name->name.contains("g014r_before_")||name.contains("history_complete")||name.contains("g014r_receipt_complete"));
        assertThatThrownBy(()->functions.add("public.unexpected()")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void unexpectedFunctionTriggerGrantAndBroadExecuteAreRejected() throws Exception {
        String text=resource();
        for(String extra:new String[]{
                "grant execute on function public.g014_lock_receipt_key(uuid,uuid,text,uuid) to msf_ci_source_runtime;",
                "grant execute on function public.g014r_before_scenario() to msf_ci_source_runtime;",
                "grant execute on all functions in schema public to msf_ci_source_runtime;"})
            assertThatThrownBy(()->CiPg18SourceFixture.declaredFunctionGrants(text+extra+"\n")).hasMessage("CI source fixture rejected");
    }
    @Test void everyMissingSignatureIncludingOnlyDirectEntrypointsIsRejected() throws Exception {
        String text=resource();
        for(String signature:CiPg18SourceFixture.declaredFunctionGrants(text)) {
            String line="grant execute on function "+signature+" to msf_ci_source_runtime;\n";
            assertThat(text).contains(line);
            assertThatThrownBy(()->CiPg18SourceFixture.declaredFunctionGrants(text.replace(line,""))).hasMessage("CI source fixture rejected");
        }
    }
    @Test void duplicateWrongOverloadWrongRoleAndGrantOptionAreRejected() throws Exception {
        String text=resource();
        String line="grant execute on function public.g014r_require_context(uuid) to msf_ci_source_runtime;";
        for(String changed:new String[]{text+line+"\n",text.replace(line,line.replace("(uuid)","(text)")),
                text.replace(line,line.replace("msf_ci_source_runtime","msf_ci_registry_runtime")),
                text.replace(line,line.replace(";"," with grant option;")),text.replace(line,line.toUpperCase(java.util.Locale.ROOT))})
            assertThatThrownBy(()->CiPg18SourceFixture.declaredFunctionGrants(changed)).hasMessage("CI source fixture rejected");
    }
}
