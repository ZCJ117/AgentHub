package config;

import domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import domain.agent.service.IArmoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.util.ArrayList;

// NOTE 6,执行onApplicationEvent方法，调用IArmoryService的acceptArmoryAgents方法
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    // NOTE 注入AiAgentAutoConfigProperties类的属性值，
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("Ai Agent 智能体装配 {}", objectMapper.writeValueAsString(aiAgentAutoConfigProperties.getTables().values()));

            armoryService.acceptArmoryAgents(new ArrayList<>(aiAgentAutoConfigProperties.getTables().values()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
