package vip.mate.domain.tool.builtin;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves a user-supplied file path against the current conversation's chat-upload
 * directory ({@code data/chat-uploads/{conversationId}/}).
 * <p>
 * Chat attachments are stored as {@code {timestamp}_{safeFilename}} where
 * {@code safeFilename} replaces every non-{@code [a-zA-Z0-9._-]} character with
 * {@code _}. This means a file uploaded as {@code 人人有虾.docx} is stored on disk
 * as e.g. {@code 1777391026594_____.docx}. The LLM only ever sees the original
 * filename in the rendered "[附件] foo.docx" prefix, so when a tool gets called
 * with the original name it won't match anything on disk via direct lookup.
 * <p>
 * This helper rescues such calls by matching basenames inside the conversation's
 * upload directory. Used by both {@link ReadFileTool} and {@link DocumentExtractTool}.
 */
@Slf4j
final class ChatUploadResolver {

    static final Path CHAT_UPLOAD_ROOT = Paths.get("data", "chat-uploads");

    private ChatUploadResolver() {}

    /**
     * @return absolute path of the matched attachment, or {@code null} if no match
     */
    static Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        // 绝对路径：直接检查文件是否存在
        Path absPath = Paths.get(rawPath).toAbsolutePath().normalize();
        if (absPath.isAbsolute() && Files.isRegularFile(absPath)) {
            return absPath;
        }
        String conversationId = ToolExecutionContext.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Path uploadDir = CHAT_UPLOAD_ROOT.resolve(conversationId).toAbsolutePath().normalize();
        if (!Files.isDirectory(uploadDir)) {
            return null;
        }

        String basename;
        try {
            Path requested = Paths.get(rawPath).getFileName();
            basename = requested != null ? requested.toString() : null;
        } catch (Exception e) {
            return null;
        }
        if (basename == null || basename.isBlank()) {
            return null;
        }

        Path direct = uploadDir.resolve(basename);
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        // Stored as "{millis}_{safeFilename}" where safeFilename replaces non-ASCII
        // characters with underscores; match by sanitized basename suffix.
        String safeBasename = basename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String suffix = "_" + safeBasename;
        Path matched = null;
        try (var stream = Files.list(uploadDir)) {
            matched = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("[ChatUploadResolver] Failed to scan chat-upload dir {}: {}", uploadDir, e.getMessage());
        }
        if (matched != null) {
            return matched;
        }

        // 父对话 fallback
        String parentConvId = ToolExecutionContext.parentConversationId();
        if (parentConvId != null && !parentConvId.isBlank()) {
            Path parentDir = CHAT_UPLOAD_ROOT.resolve(parentConvId).toAbsolutePath().normalize();
            if (Files.isDirectory(parentDir)) {
                Path parentDirect = parentDir.resolve(basename);
                if (Files.isRegularFile(parentDirect)) {
                    return parentDirect;
                }
                try (var parentStream = Files.list(parentDir)) {
                    return parentStream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(suffix))
                            .findFirst()
                            .orElse(null);
                } catch (IOException e) {
                    log.warn("[ChatUploadResolver] Failed to scan parent upload dir {}: {}", parentDir, e.getMessage());
                }
            }
        }
        return null;
    }
}
