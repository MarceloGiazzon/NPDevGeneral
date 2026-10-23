package com.finalexec.api;

import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** /api/admin/storage/summary names every internal and business table, so it answers ADMIN only. */
class StorageSummaryControllerAuthTest {

    private static final AuthenticatedContextResolver ROLES_FROM_CLAIMS = (claims, headers) -> {
        Set<String> roles = new LinkedHashSet<>();
        if (claims.get("roles") instanceof Collection<?> values) {
            values.forEach(value -> roles.add(String.valueOf(value)));
        }
        return ExecutionContext.of(String.valueOf(claims.get("tenant_id")), String.valueOf(claims.get("actor_id")))
                .withRoles(roles);
    };

    @Test
    void anAuthenticatedUserWithoutAdminIsRefused() {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller(true).summary(requestWithRoles("USER")));
        assertEquals(403, refused.getStatusCode().value());
    }

    @Test
    void aCallerWithNoClaimsIsUnauthorized() {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller(true).summary(new MockHttpServletRequest()));
        assertEquals(401, refused.getStatusCode().value());
    }

    @Test
    void anAdminGetsTheSummary() {
        assertNotNull(controller(true).summary(requestWithRoles("ADMIN", "USER")).get("physicalDatabase"));
    }

    @Test
    void anAuthDisabledAppStillAnswersLikeTheGeneratedContextDoes() {
        assertNotNull(controller(false).summary(new MockHttpServletRequest()).get("physicalDatabase"));
    }

    private static StorageSummaryController controller(boolean authEnabled) {
        RuntimeSettings settings = new RuntimeSettings("dev", false, 1, 1, authEnabled, 1, 1,
                null, null, null, 1, 1, 1, 1, null, false);
        return new StorageSummaryController(
                provider(DataSource.class, null),
                provider(RuntimeSettings.class, settings),
                provider(AuthenticatedContextResolver.class, ROLES_FROM_CLAIMS),
                "h2", "jdbc");
    }

    private static MockHttpServletRequest requestWithRoles(String... roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(StorageSummaryController.CLAIMS_ATTRIBUTE,
                Map.of("tenant_id", "tenant-a", "actor_id", "actor-a", "roles", List.of(roles)));
        return request;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (bean != null) {
            factory.addBean(type.getSimpleName(), bean);
        }
        return factory.getBeanProvider(type);
    }
}
