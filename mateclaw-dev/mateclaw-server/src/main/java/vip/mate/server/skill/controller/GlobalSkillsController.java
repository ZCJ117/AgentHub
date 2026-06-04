package vip.mate.server.skill.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.domain.skill.global.GlobalSkillsService;

import java.util.List;
import java.util.Map;

/**
 * 全局技能管理接口 — 扫描本地 Claude Code / OpenCode skills 目录
 */
@RestController
@RequestMapping("/api/v1/skills/global")
@RequiredArgsConstructor
public class GlobalSkillsController {

    private final GlobalSkillsService globalSkillsService;

    /**
     * 扫描并返回全局技能列表（含安装状态）。
     * @param type claude_code 或 opencode
     */
    @PostMapping("/scan")
    public R<List<Map<String, Object>>> scan(@RequestParam(defaultValue = "claude_code") String type) {
        return R.ok(globalSkillsService.scan(type));
    }
}
