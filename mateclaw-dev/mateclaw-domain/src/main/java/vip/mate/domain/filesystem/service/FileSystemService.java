package vip.mate.domain.filesystem.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FileSystemService {

    @Data
    public static class DirEntry {
        private final String name;
        private final String path;
    }

    @Data
    public static class DirResult {
        private final String path;
        private final String parent;
        private final List<DirEntry> dirs;
    }

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

        // Normalize to prevent path traversal
        Path normalized = Paths.get(path).normalize().toAbsolutePath();
        File dir = normalized.toFile();

        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            return new DirResult(normalized.toString(), parentPath(normalized), List.of());
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            return new DirResult(normalized.toString(), parentPath(normalized), List.of());
        }

        List<DirEntry> entries = Arrays.stream(children)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                .collect(Collectors.toList());

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
        } else {
            // Unix: list / children
            File rootDir = new File("/");
            File[] children = rootDir.listFiles(File::isDirectory);
            if (children != null) {
                entries = Arrays.stream(children)
                        .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                        .map(f -> new DirEntry(f.getName(), f.getAbsolutePath()))
                        .collect(Collectors.toList());
            }
        }

        return new DirResult("/", null, entries);
    }

    private String parentPath(Path path) {
        Path parent = path.getParent();
        return parent != null ? parent.toString() : null;
    }
}
