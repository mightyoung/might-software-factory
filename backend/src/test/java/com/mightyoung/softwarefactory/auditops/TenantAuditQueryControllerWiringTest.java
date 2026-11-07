package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TenantAuditQueryControllerWiringTest {
    @Test void compositionWitnessRequiresEveryCanonicalDependencyIdentity() {
        var service=mock(TenantAuditQueryService.class);
        var parser=new TenantAuditQueryParser();
        var requests=mock(SessionSensitiveRequests.class);
        var transport=mock(TenantAuditHttpTransport.class);
        var controller=new TenantAuditQueryController(service,parser,requests,transport);
        assertTrue(controller.usesDependencies(service,parser,requests,transport));
        assertFalse(controller.usesDependencies(mock(TenantAuditQueryService.class),parser,requests,transport));
        assertFalse(controller.usesDependencies(service,new TenantAuditQueryParser(),requests,transport));
        assertFalse(controller.usesDependencies(service,parser,mock(SessionSensitiveRequests.class),transport));
        assertFalse(controller.usesDependencies(service,parser,requests,mock(TenantAuditHttpTransport.class)));
        assertFalse(controller.usesDependencies(null,parser,requests,transport));
        assertFalse(controller.usesDependencies(service,null,requests,transport));
        assertFalse(controller.usesDependencies(service,parser,null,transport));
        assertFalse(controller.usesDependencies(service,parser,requests,null));
        assertTrue(controller.usesDependencies(service,parser,requests,transport));
    }
}
