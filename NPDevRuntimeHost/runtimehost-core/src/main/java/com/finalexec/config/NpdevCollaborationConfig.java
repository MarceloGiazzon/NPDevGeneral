package com.finalexec.config;

import com.finalexec.collab.InMemoryRecordCommentStore;
import com.finalexec.collab.JdbcRecordCommentStore;
import com.finalexec.collab.RecordCommentService;
import com.finalexec.collab.RecordCommentStore;
import com.npdev.kernel.concepts.ConceptGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * PROC-5/PROC-6: wires the record-thread store and service. The store follows {@code npdev.storage.mode}
 * the way the other runtime stores do ({@code jdbc} with a DataSource -> {@code npdev_record_comment};
 * anything else -> in-process). The service reads the model through {@link ModelHolder} on every call
 * (hot reload safe) and the gateway lazily, so an app without collaboration pays nothing.
 *
 * <p>Mentions go to whatever {@link RecordCommentService.MentionNotifier} bean exists (the in-app
 * notification bell installs one); without it they are dropped silently.
 */
@Configuration
public class NpdevCollaborationConfig {

    @Bean
    public RecordCommentStore recordCommentStore(
            @Value("${npdev.storage.mode:in-memory}") String storageMode,
            ObjectProvider<DataSource> dataSource) {
        DataSource source = dataSource.getIfAvailable();
        if ("jdbc".equalsIgnoreCase(storageMode) && source != null) {
            return new JdbcRecordCommentStore(source);
        }
        return new InMemoryRecordCommentStore();
    }

    @Bean
    public RecordCommentService recordCommentService(
            ModelHolder modelHolder,
            ObjectProvider<ConceptGateway> conceptGateway,
            RecordCommentStore store,
            ObjectProvider<RecordCommentService.MentionNotifier> notifier) {
        return new RecordCommentService(modelHolder::get, conceptGateway::getIfAvailable, store,
                notifier.getIfAvailable(() -> RecordCommentService.MentionNotifier.NONE), System::currentTimeMillis);
    }
}
