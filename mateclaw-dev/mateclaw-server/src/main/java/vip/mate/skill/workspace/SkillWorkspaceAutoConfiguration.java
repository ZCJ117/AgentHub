package vip.mate.skill.workspace;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import vip.mate.skill.installer.SkillHubProperties;

/**
 * Skill 工作区与安装器自动配置
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties({SkillWorkspaceProperties.class, SkillHubProperties.class})
public class SkillWorkspaceAutoConfiguration {
}
