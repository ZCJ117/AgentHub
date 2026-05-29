package vip.mate.artifact.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;

@Slf4j
@Component
public class LocalFileSystemProvider implements ArtifactStorageProvider {

    @Value("${mateclaw.workspace.base-dir:./workspace}")
    private String baseDir;

    @Override
    public String store(Long artifactId, int versionNumber, String filename, InputStream content) {
        try {
            Path dir = Paths.get(baseDir, "artifacts", artifactId.toString(), String.valueOf(versionNumber));
            Files.createDirectories(dir);
            Path file = dir.resolve(filename != null ? filename : "artifact");
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store artifact", e);
        }
    }

    @Override
    public InputStream read(String storagePath) {
        try {
            return Files.newInputStream(Paths.get(storagePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read artifact", e);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException e) {
            log.warn("Failed to delete artifact file: {}", storagePath, e);
        }
    }
}
