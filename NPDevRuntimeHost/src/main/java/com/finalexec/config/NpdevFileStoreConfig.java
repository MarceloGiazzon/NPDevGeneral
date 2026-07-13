package com.finalexec.config;

import com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter;
import com.npdev.kernel.ports.FileStoreContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * LIFT-UPLOAD-P1/P3: wires the filesystem {@link FileStoreContract} adapter. Root defaults to a
 * directory next to the running app's own working directory (never inside the source repo/Build
 * tree, consistent with {@code npdev.strict-execution.generated-root}'s sibling convention) --
 * override with {@code npdev.filestore.root} for a real deployment.
 */
@Configuration
public class NpdevFileStoreConfig {

    @Bean
    public FileStoreContract fileStoreContract(
            @Value("${npdev.filestore.root:${user.dir}\\npdev-files}") String root
    ) {
        return new FileSystemFileStoreAdapter(Path.of(root));
    }
}
