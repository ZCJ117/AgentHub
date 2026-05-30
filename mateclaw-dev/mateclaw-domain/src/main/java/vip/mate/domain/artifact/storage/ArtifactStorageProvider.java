package vip.mate.domain.artifact.storage;

import java.io.InputStream;

/** 产物存储抽象 — 支持本地文件系统和后续对象存储扩展 */
public interface ArtifactStorageProvider {
    /** Store content and return the storage path */
    String store(Long artifactId, int versionNumber, String filename, InputStream content);
    /** Read stored content */
    InputStream read(String storagePath);
    /** Delete stored content */
    void delete(String storagePath);
}
