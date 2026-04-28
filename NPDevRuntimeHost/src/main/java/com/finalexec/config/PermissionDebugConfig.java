package com.finalexec.config;

import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.ports.PermissionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Debug helpers:
 * 1) Dumps loaded PermissionGrants at startup.
 * 2) Wraps PermissionEvaluator with a dynamic proxy that logs every call + arguments.
 *
 * Remove after debugging.
 */
@Configuration
@Profile("dev")
public class PermissionDebugConfig {

    private static final Logger log = LoggerFactory.getLogger(PermissionDebugConfig.class);

    @Bean
    public ApplicationRunner dumpPermissionGrantsOnStartup(List<PermissionGrant> permissionGrants) {
        return args -> {
            log.info("PERM DEBUG -> Loaded PermissionGrants count={}", permissionGrants.size());
            for (int i = 0; i < permissionGrants.size(); i++) {
                PermissionGrant g = permissionGrants.get(i);
                log.info("PERM DEBUG -> grant[{}]={}", i, g);
            }
        };
    }

    @Bean
    public static BeanPostProcessor permissionEvaluatorLoggingProxy() {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof PermissionEvaluator evaluator)) {
                    return bean;
                }

                // Avoid double-proxying
                if (Proxy.isProxyClass(bean.getClass())) {
                    return bean;
                }

                log.info("PERM DEBUG -> Wrapping PermissionEvaluator bean '{}': {}", beanName, bean.getClass().getName());

                return Proxy.newProxyInstance(
                        bean.getClass().getClassLoader(),
                        new Class<?>[]{PermissionEvaluator.class},
                        (proxy, method, args) -> {
                            // log all calls except Object methods
                            String m = method.getName();
                            if (!m.equals("toString") && !m.equals("hashCode") && !m.equals("equals")) {
                                log.info("PERM DEBUG -> PermissionEvaluator.{} args={}", m, safeArgs(args));
                            }
                            return method.invoke(evaluator, args);
                        }
                );
            }

            private String safeArgs(Object[] args) {
                if (args == null) return "null";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(", ");
                    Object a = args[i];
                    sb.append(a == null ? "null" : a.toString());
                }
                sb.append("]");
                return sb.toString();
            }
        };
    }
}
