package com.npdev.dsl.v1.repo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ModelRepository {

    ModelArtifact publish(Path modelPath) throws IOException;

    List<ModelArtifact> list() throws IOException;

    Optional<ModelArtifactManifest> readManifest(String name, String hash) throws IOException;
}
