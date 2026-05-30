package vip.mate.domain.llm.model;

import lombok.Data;

import java.util.List;

@Data
public class ApplyDiscoveredModelsRequest {
    private List<String> modelIds;
}
