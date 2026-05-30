package vip.mate.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.message.service.MessagePinService;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link MessagePinService} — pin and unpin message
 * lifecycle, plus conversation-scoped pin listing.
 */
@SpringBootTest(
    classes = MateClawApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:pin_svc_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none"
})
@Transactional
class MessagePinServiceTest {

    @Autowired
    private MessagePinService pinService;

    @Test
    @DisplayName("should pin and unpin a message, verifying list state before and after")
    void shouldPinAndUnpinMessage() {
        var pin = pinService.pinMessage(1L, 1L, 1L, "Important context");
        assertThat(pin.getId()).isNotNull();
        assertThat(pin.getNote()).isEqualTo("Important context");

        var pins = pinService.listPins(1L);
        assertThat(pins).hasSize(1);

        pinService.unpinMessage(1L);
        var after = pinService.listPins(1L);
        assertThat(after).isEmpty();
    }
}
