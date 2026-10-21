package com.finalexec.config;

import com.finalexec.collab.RecordCommentService;
import com.finalexec.notify.InMemoryNotificationStore;
import com.finalexec.notify.MentionNotificationNotifier;
import com.finalexec.notify.JdbcNotificationStore;
import com.finalexec.notify.NotificationStore;
import com.finalexec.notify.NotifyCapabilityHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.finalexec.db.SideChannelPool;
import javax.sql.DataSource;

/**
 * PROC-4: in-app notifications. Persistent (npdev_notification) when {@code npdev.storage.mode=jdbc},
 * in-memory otherwise -- the same property pair {@code NpdevRuntimeModeConfig} uses for its stores.
 */
@Configuration
public class NpdevNotificationConfig {

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public NotificationStore inMemoryNotificationStore() {
        return new InMemoryNotificationStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public NotificationStore jdbcNotificationStore(SideChannelPool sideChannel) {
        return new JdbcNotificationStore(sideChannel.dataSource());
    }

    /** PROC-5 mentions land in the bell; NpdevCollaborationConfig picks this bean up by type. */
    @Bean
    public RecordCommentService.MentionNotifier mentionNotificationNotifier(NotificationStore store) {
        return new MentionNotificationNotifier(store, System::currentTimeMillis);
    }

    @Bean
    public NotifyCapabilityHandler notifyCapabilityHandler(NotificationStore store) {
        return new NotifyCapabilityHandler(store, System::currentTimeMillis);
    }
}
