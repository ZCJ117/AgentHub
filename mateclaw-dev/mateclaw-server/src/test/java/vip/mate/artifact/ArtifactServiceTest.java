package vip.mate.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.artifact.service.ArtifactService;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link ArtifactService} — artifact creation, versioning,
 * version listing, and restoration.
 * <p>
 * Tests write to the configured storage provider (local filesystem by default).
 */
@SpringBootTest(
    classes = MateClawApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:artifact_svc_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none"
})
@Transactional
class ArtifactServiceTest {

    @Autowired
    private ArtifactService artifactService;

    @Test
    @DisplayName("should create artifact with initial version and deploy status none")
    void shouldCreateArtifact() {
        var content = new ByteArrayInputStream("<html><body>Test</body></html>".getBytes());
        var artifact = artifactService.createArtifact(
            1L, 1L, 5L, 1L, "test-page", "html", content, "index.html");
        assertThat(artifact.getId()).isNotNull();
        assertThat(artifact.getCurrentVersion()).isEqualTo(1);
        assertThat(artifact.getDeployStatus()).isEqualTo("none");
    }

    @Test
    @DisplayName("should list versions for a newly created artifact")
    void shouldListVersions() {
        var content = new ByteArrayInputStream("v1".getBytes());
        var artifact = artifactService.createArtifact(
            1L, 1L, 5L, 1L, "test", "code", content, "test.txt");
        var versions = artifactService.listVersions(artifact.getId());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getVersionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("should restore a previous version and increment currentVersion")
    void shouldRestoreVersion() {
        var content = new ByteArrayInputStream("original".getBytes());
        var artifact = artifactService.createArtifact(
            1L, 1L, 5L, 1L, "test", "code", content, "test.txt");
        var versions = artifactService.listVersions(artifact.getId());
        var restored = artifactService.restoreVersion(artifact.getId(), versions.get(0).getId());
        assertThat(restored.getCurrentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("should throw exception when artifact not found")
    void shouldThrowWhenArtifactNotFound() {
        assertThatThrownBy(() -> artifactService.getArtifact(99999L))
            .hasMessageContaining("产物不存在");
    }
}
