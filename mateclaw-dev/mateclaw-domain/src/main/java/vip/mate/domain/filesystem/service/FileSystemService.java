package vip.mate.domain.filesystem.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class FileSystemService {

    public record DirEntry(String name, String path) {}

    public record DirResult(String path, String parent, List<DirEntry> dirs) {}

    /**
     * List directories under the given path.
     * When path is empty or "/", returns root-level entries:
     *   Windows: drive letters (C:\, D:\, ...)
     *   Linux/macOS: / directories
     */
    public DirResult listDirs(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return listRoots();
        }

        // Normalize to resolve .. and . to a canonical absolute path
        Path normalized = Paths.get(path).normalize().toAbsolutePath();
        File dir = normalized.toFile();

        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            log.warn("Directory not accessible: {}", normalized);
            return new DirResult(normalized.toString(), parentPath(normalized), List.of());
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            log.warn("listFiles returned null for: {}", normalized);
            return new DirResult(normalized.toString(), parentPath(normalized), List.of());
        }

        List<DirEntry> entries = Arrays.stream(children)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                .toList();

        return new DirResult(normalized.toString(), parentPath(normalized), entries);
    }

    private DirResult listRoots() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<DirEntry> entries = new ArrayList<>();

        if (os.contains("win")) {
            // Windows: list drive letters
            for (File root : File.listRoots()) {
                entries.add(new DirEntry(root.getPath(), root.getPath()));
            }
            entries.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        } else {
            // Unix: list / children
            File rootDir = new File("/");
            File[] children = rootDir.listFiles(File::isDirectory);
            if (children != null) {
                entries = Arrays.stream(children)
                        .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                        .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                        .toList();
            }
        }

        return new DirResult("/", null, entries);
    }

    private String parentPath(Path path) {
        Path parent = path.getParent();
        return parent != null ? parent.toString() : null;
    }
}
