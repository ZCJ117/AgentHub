package vip.mate.domain.llm.model;

import lombok.Data;

@Data
public class ModelSlotRequest {
    private String providerId;
    private String model;
}
