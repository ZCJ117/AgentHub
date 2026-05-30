package vip.mate.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.message.service.MessageReactionService;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link MessageReactionService} — add, remove, and
 * group-count reactions on messages.
 */
@SpringBootTest(
    classes = MateClawApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:reaction_svc_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none"
})
@Transactional
class MessageReactionServiceTest {

    @Autowired
    private MessageReactionService reactionService;

    @Test
    @DisplayName("should add and remove reaction, verifying grouped counts")
    void shouldAddAndRemoveReaction() {
        reactionService.addReaction(1L, 1L, "like");
        var reactions = reactionService.getReactionsGrouped(1L);
        assertThat(reactions).containsKey("like");
        assertThat(reactions.get("like")).hasSize(1);

        reactionService.removeReaction(1L, 1L, "like");
        var after = reactionService.getReactionsGrouped(1L);
        assertThat(after).isEmpty();
    }

    @Test
    @DisplayName("should not duplicate reaction when same user adds same type twice")
    void shouldNotDuplicateReaction() {
        reactionService.addReaction(1L, 1L, "like");
        reactionService.addReaction(1L, 1L, "like"); // idempotent
        var reactions = reactionService.getReactionsGrouped(1L);
        assertThat(reactions.get("like")).hasSize(1);
    }
}
