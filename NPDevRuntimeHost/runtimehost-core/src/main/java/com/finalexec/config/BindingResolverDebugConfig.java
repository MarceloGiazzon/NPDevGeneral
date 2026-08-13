package com.finalexec.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.lang.reflect.Proxy;

/**
 * Logs calls to the real capability binding resolver ONLY.
 *
 * Important:
 * - Do NOT proxy @Configuration classes (breaks Spring @Bean factory methods).
 * - Only proxy the resolver bean we care about: "capabilityBindingResolver".
 *
 * Remove after debugging.
 */
@Configuration
@Profile("dev")
public class BindingResolverDebugConfig {

    private static final Logger log = LoggerFactory.getLogger(BindingResolverDebugConfig.class);

    @Bean
    public static BeanPostProcessor capabilityBindingResolverOnlyProxy() {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

                // Only wrap the one resolver bean we care about
                if (!"capabilityBindingResolver".equals(beanName)) {
                    return bean;
                }

                // Avoid proxying Spring @Configuration / CGLIB enhanced classes
                String cn = bean.getClass().getName();
                if (cn.contains("$$SpringCGLIB$$") || cn.contains("$$EnhancerBySpringCGLIB$$")) {
                    log.info("BIND DEBUG -> Skipping proxy for CGLIB bean '{}': {}", beanName, cn);
                    return bean;
                }

                // Need interfaces to create JDK proxy
                Class<?>[] ifaces = bean.getClass().getInterfaces();
                if (ifaces == null || ifaces.length == 0) {
                    log.warn("BIND DEBUG -> Bean '{}' has no interfaces; cannot JDK-proxy. Type={}", beanName, cn);
                    return bean;
                }

                log.info("BIND DEBUG -> Wrapping bean '{}' type={}", beanName, cn);

                return Proxy.newProxyInstance(
                        bean.getClass().getClassLoader(),
                        ifaces,
                        (proxy, method, args) -> {
                            String m = method.getName();
                            if (!m.equals("toString") && !m.equals("hashCode") && !m.equals("equals")) {
                                log.info("BIND DEBUG -> {}.{} args={}", cn, m, safeArgs(args));
                            }
                            return method.invoke(bean, args);
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
