package com.mightyoung.softwarefactory.applicationregistry.navigation;

import com.mightyoung.softwarefactory.identity.CurrentSessionDetails;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NavigationControllerTest {
    @Test void bootstrapIsSmallAndMenuIsLoadedOnlyOnDemand() throws Exception {
        var service = mock(NavigationService.class);
        UUID id = UUID.randomUUID();
        var version = new NavigationService.AuthorizationVersion(2,3,4L);
        var identity = new CurrentSessionDetails(id,id,id,"t","Tenant",id,"Member");
        when(service.bootstrap()).thenReturn(new NavigationService.Bootstrap(1,identity,
                List.of(new NavigationService.ApplicationEntry("safety","生产安全","safety/findings")),
                List.of("finding.read"),version));
        when(service.menu("safety")).thenReturn(new NavigationService.Menu(1,"safety","生产安全",
                List.of(new NavigationService.MenuNode("findings","隐患管理","safety/findings","finding.read")),
                List.of("finding.read"),version));
        var mvc = MockMvcBuilders.standaloneSetup(new NavigationController(service)).build();
        mvc.perform(get("/api/v1/session/bootstrap")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.applications[0].id").value("safety"))
                .andExpect(jsonPath("$.data.permissions[0]").value("finding.read"))
                .andExpect(jsonPath("$.data.authorization.applicationVersion").value(4))
                .andExpect(jsonPath("$.data.authorization.credentialVersion").doesNotExist())
                .andExpect(jsonPath("$.data.nodes").doesNotExist())
                .andExpect(jsonPath("$.data.menus").doesNotExist())
                .andExpect(jsonPath("$.data.organizations").doesNotExist())
                .andExpect(jsonPath("$.data.dictionaries").doesNotExist());
        verify(service,never()).menu(anyString());
        mvc.perform(get("/api/v1/applications/safety/menu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].permission").value("finding.read"));
    }
    @Test void actualCreateOnlyServiceBootstrapAndMenuNeverAdvertiseRead() throws Exception {
        var resolver = mock(com.mightyoung.softwarefactory.identity.SessionContextResolver.class);
        var sessions = mock(com.mightyoung.softwarefactory.identity.CurrentSessionService.class);
        var applications = mock(com.mightyoung.softwarefactory.tenancy.TenantApplicationReadRepository.class);
        var reads = mock(com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy.class);
        var actions = mock(com.mightyoung.softwarefactory.authorization.FindingWorkflowAuthorizationRepository.class);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        UUID id = UUID.randomUUID();
        var context = new com.mightyoung.softwarefactory.identity.CurrentTenantContext(id, id, id, id, 2, 3, 1);
        when(resolver.currentTenant()).thenReturn(context);
        when(sessions.current()).thenReturn(new CurrentSessionDetails(id, id, id, "t", "Tenant", id, "Member"));
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(applications.findCurrent(id, "safety")).thenReturn(java.util.Optional.of(
                new com.mightyoung.softwarefactory.tenancy.TenantApplicationState(true, 4)));
        when(reads.resolveEntityReadGrant(context, "finding")).thenReturn(java.util.Optional.empty());
        when(actions.loadActionSnapshot(context, "finding.create", "create")).thenReturn(java.util.Optional.of(
                new com.mightyoung.softwarefactory.authorization.FindingReadAuthorizationSnapshot(id, id,
                        "HUMAN", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", 2, 3, java.util.Set.of(id))));
        var service = new NavigationService(resolver, sessions, applications, reads, actions,
                mock(com.mightyoung.softwarefactory.authorization.EffectivePermissions.class), manager);
        var mvc = MockMvcBuilders.standaloneSetup(new NavigationController(service)).build();
        mvc.perform(get("/api/v1/session/bootstrap")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applications[0].id").value("safety"))
                .andExpect(jsonPath("$.data.permissions.length()").value(1))
                .andExpect(jsonPath("$.data.permissions[0]").value("finding.create"))
                .andExpect(jsonPath("$.data.authorization.applicationVersion").value(4))
                .andExpect(jsonPath("$.data.nodes").doesNotExist());
        mvc.perform(get("/api/v1/applications/safety/menu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(1))
                .andExpect(jsonPath("$.data.permissions[0]").value("finding.create"))
                .andExpect(jsonPath("$.data.nodes[0].permission").value("finding.create"))
                .andExpect(jsonPath("$.data.nodes[0].path").value("safety/findings"));
        verify(reads, times(2)).resolveEntityReadGrant(context, "finding");
        verify(actions, times(2)).loadActionSnapshot(context, "finding.create", "create");
    }

}
