package vip.mate.server.channel.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Talk Mode WebSocket Handler。
 *
 * <p>处理实时语音对话的 WebSocket 连接。客户端通过此端点发送语音数据（二进制帧）
 * 和 JSON 命令（文本帧），服务端返回转录结果和响应状态。
 *
 * <p>二进制帧：16 kHz / 16-bit / mono PCM WAV 音频数据，约 32 KB/s。
 * <p>文本帧：init / state / transcript 等 JSON 消息。
 */
@Slf4j
@Component
public class TalkModeWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[Talk WS] Connection established: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.debug("[Talk WS] Text message from session={}: {}chars",
                session.getId(), message.getPayloadLength());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        log.debug("[Talk WS] Binary message from session={}: {}bytes",
                session.getId(), message.getPayloadLength());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[Talk WS] Connection closed: sessionId={} status={}",
                session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[Talk WS] Transport error for session={}: {}",
                session.getId(), exception.getMessage());
    }
}
