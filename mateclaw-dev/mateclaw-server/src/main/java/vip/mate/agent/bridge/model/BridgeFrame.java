package vip.mate.agent.bridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * WebSocket 协议帧，{type, seq, ts, payload}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BridgeFrame {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String type;
    private long seq;
    private long ts;
    private Map<String, Object> payload;

    public static BridgeFrame of(String type, Map<String, Object> payload) {
        BridgeFrame frame = new BridgeFrame();
        frame.type = type;
        frame.seq = 0;
        frame.ts = System.currentTimeMillis();
        frame.payload = payload;
        return frame;
    }

    public static BridgeFrame parse(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, BridgeFrame.class);
    }

    public String toJson() throws JsonProcessingException {
        return MAPPER.writeValueAsString(this);
    }
}
