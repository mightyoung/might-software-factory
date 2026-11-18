package com.mightyoung.softwarefactory.workflow;

import org.junit.jupiter.api.Test;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class FindingReviewBpmnContractTest {
    @Test void preservesLegacyBytesAndDefinesCompleteReviewCycleWithoutDefaultApproval() throws Exception {
        assertThat(hash("v1")).isEqualTo("314650552a423a07791707e554e3330f1da3667b4a7eb104ee87c2e85d804c91");
        assertThat(hash("v2")).isEqualTo("7cfad5931474e15a8ebd539eb3ba19afe95a99aac5b2794d200003972b552eda");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var stream = getClass().getResourceAsStream("/processes/finding-remediation-v3.bpmn20.xml")) {
            var document = factory.newDocumentBuilder().parse(stream);
            Map<String, Element> elements = new HashMap<>();
            var nodes = document.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                var element = (Element) nodes.item(i);
                if (element.hasAttribute("id")) elements.put(element.getAttribute("id"), element);
            }
            assertThat(elements).containsKey("findingRemediationV3");
            assertThat(elements.get("reporterSupplement").getAttributeNS("http://flowable.org/bpmn", "assignee"))
                    .isEqualTo("${reporterMemberId}");
            for (String stage : new String[]{"person", "safety"}) {
                assertThat(elements.get(stage + "Decision").hasAttribute("default")).isFalse();
                assertThat(elements.get(stage + "Returned").getAttribute("targetRef")).isEqualTo("reporterSupplement");
                assertThat(elements.get(stage + "Returned").getTextContent()).contains("reviewDecision == 'RETURN_FOR_INFORMATION'");
                assertThat(elements.get(stage + "NotEstablished").getAttribute("targetRef")).isEqualTo("notEstablishedEnd");
                assertThat(elements.get(stage + "Approved").getTextContent()).contains("reviewDecision == 'APPROVE'");
            }
            assertThat(elements.get("supplementToPerson").getAttribute("targetRef")).isEqualTo("personConfirm");
            assertThat(elements.get("personApproved").getAttribute("targetRef")).isEqualTo("safetyConfirm");
            assertThat(elements.get("safetyApproved").getAttribute("targetRef")).isEqualTo("safetyDispatch");
            assertThat(elements.get("verificationFailure").getAttribute("targetRef")).isEqualTo("safetyDispatch");
            assertThat(elements.get("verificationPass").getTextContent()).contains("verificationDecision == 'PASS'");
        }
    }
    private String hash(String version) throws Exception {
        try (var stream = getClass().getResourceAsStream("/processes/finding-remediation-" + version + ".bpmn20.xml")) {
            return PinnedFindingReviewDefinitionResolver.sha256(stream.readAllBytes());
        }
    }
}
