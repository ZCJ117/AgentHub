package vip.mate.domain.skill.global;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GlobalSkillsService {

    private static final String CLAUDE_SKILLS_PATH = System.getProperty("user.home") + "/.claude/skills";
    private static final String OPENCODE_SKILLS_PATH = System.getProperty("user.home") + "/.config/opencode/skills";

    private List<SkillDef> skillDefs;

    public GlobalSkillsService() {
        this.skillDefs = loadSkillDefs();
    }

    /**
     * Scan and return skill list with installation status.
     * @param type "claude_code" or "opencode"
     */
    public List<Map<String, Object>> scan(String type) {
        String scanPath = "opencode".equals(type) ? OPENCODE_SKILLS_PATH : CLAUDE_SKILLS_PATH;
        Path dir = Path.of(scanPath);

        Set<String> installed = Set.of();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                installed = stream
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toSet());
            } catch (Exception e) {
                log.warn("Failed to scan directory {}: {}", scanPath, e.getMessage());
            }
        } else {
            log.debug("Skills directory does not exist: {}", scanPath);
        }

        final Set<String> finalInstalled = installed;
        List<Map<String, Object>> result = new ArrayList<>();
        for (SkillDef def : skillDefs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", def.name());
            item.put("description", def.description());
            item.put("installCount", def.installCount());
            item.put("installCommand", def.installCommand());
            item.put("installed", finalInstalled.contains(def.name()));
            result.add(item);
        }
        return result;
    }

    private List<SkillDef> loadSkillDefs() {
        List<SkillDef> defs = new ArrayList<>();
        try {
            var resource = new ClassPathResource("skills/skills.txt");
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\t", 4);
                    if (parts.length >= 4) {
                        defs.add(new SkillDef(parts[0], parts[1], parts[2], parts[3]));
                    }
                }
            }
            log.info("Loaded {} global skill definitions", defs.size());
        } catch (Exception e) {
            log.error("Failed to load skills.txt from classpath", e);
            throw new RuntimeException("Cannot load skills/skills.txt", e);
        }
        return defs;
    }

    record SkillDef(String name, String description, String installCount, String installCommand) {}
}
