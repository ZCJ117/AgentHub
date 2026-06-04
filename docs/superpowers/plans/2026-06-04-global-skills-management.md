# 全局技能管理页面 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在导航栏新增 🔧 技能图标，打开全局技能管理页面，扫描本地 Claude Code / OpenCode 目录判断技能安装状态

**Architecture:** 前端新建 `SkillsView.vue` + `api/skills.js`，后端新建 `GlobalSkillsController` + `GlobalSkillsService` + 静态 `skills.txt` 资源文件

**Tech Stack:** Vue 3 + Naive UI + Spring Boot 3.5 + Java 21

---

### Task 1: 后端 — 静态技能资源文件

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/resources/skills/skills.txt`

- [ ] **Step 1: 创建 skills.txt 资源文件**

文件内容基于用户桌面 `C:\Users\86178\Desktop\SKILLS.txt`，转换为 TSV 格式（tab 分隔）：

```
find-skills	智能匹配与推荐适配的技能，是 AI 代理的"应用商店搜索框"，用于发现和安装其他 Skills	608.7k	npx skills add vercel-labs/find-skills
bash	提供终端运维、脚本执行能力，让 AI 能在 Shell 环境中运行命令	418.6k	npx skills add vercel-labs/bash
fs	处理文件读写、代码修改等操作，让 AI 能直接操作本地文件系统	389.2k	npx skills add vercel-labs/fs
git	赋予 AI 代码版本管理能力，自动处理 Git 操作	356.8k	npx skills add vercel-labs/git
search	支持联网进行全网实时搜索，帮助 AI 获取最新信息	321.5k	npx skills add vercel-labs/search
url	实现网页解析与文档读取功能	298.7k	npx skills add vercel-labs/url
code	辅助代码重构和错误纠正	276.3k	npx skills add vercel-labs/code
terminal	支持长时间运行的终端命令	253.1k	npx skills add vercel-labs/terminal
docker	提供容器部署与运维能力	223.8k	npx skills add vercel-labs/docker
npm	管理前端项目依赖	198.5k	npx skills add vercel-labs/npm
python	运行 Python 脚本和进行数据分析	187.2k	npx skills add vercel-labs/python
markdown	优化文案、文档的排版与呈现	165.9k	npx skills add vercel-labs/markdown
sql	执行数据库操作	143.6k	npx skills add vercel-labs/sql
ssh	连接并运维远程服务器	128.7k	npx skills add vercel-labs/ssh
curl	调试 API 接口	115.3k	npx skills add vercel-labs/curl
task	执行批量自动化任务	102.8k	npx skills add vercel-labs/task
zip	处理文件压缩与备份	98.5k	npx skills add vercel-labs/zip
keyboard	实现桌面键鼠自动化操作	92.7k	npx skills add vercel-labs/keyboard
clipboard	同步与读写系统剪贴板	87.3k	npx skills add vercel-labs/clipboard
notes	进行本地笔记的整理与归档	82.1k	npx skills add vercel-labs/notes
```

每行格式：`name\t description\t installCount\t installCommand`

- [ ] **Step 2: 验证文件存在**

Run: `wc -l mateclaw-dev/mateclaw-server/src/main/resources/skills/skills.txt`
Expected: 20 lines

- [ ] **Step 3: 提交**

```bash
git add mateclaw-dev/mateclaw-server/src/main/resources/skills/skills.txt
git commit -m "feat: add static global skills definition file (20 skills)"
```

---

### Task 2: 后端 — GlobalSkillsService 扫描服务

**Files:**
- Create: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/skill/global/GlobalSkillsService.java`

- [ ] **Step 1: 创建 GlobalSkillsService**

```java
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
```

- [ ] **Step 2: 验证编译**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/skill/global/GlobalSkillsService.java
git commit -m "feat: add GlobalSkillsService for scanning local skill directories"
```

---

### Task 3: 后端 — GlobalSkillsController

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/skill/controller/GlobalSkillsController.java`

- [ ] **Step 1: 创建 GlobalSkillsController**

```java
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
```

- [ ] **Step 2: 验证编译**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-server -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/skill/controller/GlobalSkillsController.java
git commit -m "feat: add GlobalSkillsController for scanning local skills"
```

---

### Task 4: 前端 — API 层

**Files:**
- Create: `AIagent_frontend/src/api/skills.js`

- [ ] **Step 1: 创建 api/skills.js**

```js
import apiClient from './client'

/**
 * 扫描全局技能
 * @param {'claude_code' | 'opencode'} type
 */
export function scanGlobalSkills(type = 'claude_code') {
  return apiClient.post('/api/v1/skills/global/scan', null, {
    params: { type }
  })
}
```

- [ ] **Step 2: 提交**

```bash
git add AIagent_frontend/src/api/skills.js
git commit -m "feat: add global skills API layer"
```

---

### Task 5: 前端 — SkillsView.vue 页面

**Files:**
- Create: `AIagent_frontend/src/views/SkillsView.vue`

- [ ] **Step 1: 创建 SkillsView.vue**

```vue
<script setup>
import { ref } from 'vue'
import { NTabs, NTabPane, NButton, NTag, NModal, NSpin, NIcon, NCard } from 'naive-ui'
import { CopyOutline } from '@vicons/ionicons5'
import { scanGlobalSkills } from '@/api/skills'

const activeTab = ref('claude_code')
const loading = ref(false)
const skills = ref([])
const scanned = ref(false)

const installModal = ref(false)
const installTarget = ref(null)

const tabLabels = {
  claude_code: 'Claude Code',
  opencode: 'OpenCode'
}

const scanPaths = {
  claude_code: '~/.claude/skills/',
  opencode: '~/.config/opencode/skills/'
}

async function handleScan() {
  loading.value = true
  try {
    const res = await scanGlobalSkills(activeTab.value)
    skills.value = res.data
    scanned.value = true
  } finally {
    loading.value = false
  }
}

function openInstall(skill) {
  installTarget.value = skill
  installModal.value = true
}

function copyCommand() {
  if (installTarget.value) {
    navigator.clipboard.writeText(installTarget.value.installCommand)
  }
}
</script>

<template>
  <div class="skills-page">
    <NCard>
      <div class="skills-header">
        <h2>🔧 全局技能管理</h2>
        <p class="subtitle">管理 Claude Code 和 OpenCode 的全局 Skills</p>
      </div>

      <NTabs v-model:value="activeTab" type="segment" animated>
        <NTabPane name="claude_code" tab="Claude Code" />
        <NTabPane name="opencode" tab="OpenCode" />
      </NTabs>

      <div class="scan-area">
        <NButton type="primary" :loading="loading" @click="handleScan" size="large">
          🔍 扫描全局 Skills
        </NButton>
        <p class="scan-path">扫描目录: {{ scanPaths[activeTab] }}</p>
      </div>

      <NSpin :show="loading">
        <div v-if="scanned && skills.length" class="skill-list">
          <div class="skill-list-header">
            <span class="col-name">Skill 名称</span>
            <span class="col-desc">核心功能</span>
            <span class="col-count">安装量</span>
            <span class="col-status">状态</span>
            <span class="col-action">操作</span>
          </div>
          <div
            v-for="skill in skills"
            :key="skill.name"
            class="skill-row"
            :class="{ 'not-installed': !skill.installed }"
          >
            <span class="col-name"><strong>{{ skill.name }}</strong></span>
            <span class="col-desc">{{ skill.description }}</span>
            <span class="col-count">{{ skill.installCount }}</span>
            <span class="col-status">
              <NTag :type="skill.installed ? 'success' : 'error'" size="small">
                {{ skill.installed ? '已安装' : '未安装' }}
              </NTag>
            </span>
            <span class="col-action">
              <NButton
                v-if="!skill.installed"
                size="small"
                @click="openInstall(skill)"
              >
                安装
              </NButton>
            </span>
          </div>
        </div>
        <div v-else-if="scanned && !skills.length" class="empty-state">
          <p>暂无可扫描的技能数据</p>
        </div>
      </NSpin>
    </NCard>

    <!-- 安装命令 Modal -->
    <NModal v-model:show="installModal" title="安装 Skill">
      <div v-if="installTarget" class="install-modal">
        <p>请在终端中执行以下命令：</p>
        <div class="command-block">
          <code>{{ installTarget.installCommand }}</code>
        </div>
        <NButton @click="copyCommand" size="small">
          <template #icon><NIcon :component="CopyOutline" /></template>
          复制命令
        </NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.skills-page {
  max-width: 960px;
  margin: 24px auto;
  padding: 0 16px;
}
.skills-header {
  text-align: center;
  margin-bottom: 16px;
}
.skills-header h2 {
  margin: 0;
}
.subtitle {
  color: #888;
  font-size: 13px;
  margin: 4px 0 0;
}
.scan-area {
  text-align: center;
  margin: 20px 0;
}
.scan-path {
  color: #aaa;
  font-size: 12px;
  margin-top: 6px;
}
.skill-list-header, .skill-row {
  display: flex;
  padding: 10px 12px;
  font-size: 13px;
  align-items: center;
}
.skill-list-header {
  font-weight: 600;
  color: #666;
  border-bottom: 2px solid #eee;
}
.skill-row {
  border-bottom: 1px solid #f0f0f0;
}
.skill-row.not-installed {
  background: #fffbf0;
}
.col-name { flex: 2; }
.col-desc { flex: 4; }
.col-count { flex: 1; text-align: center; }
.col-status { flex: 1.5; text-align: center; }
.col-action { flex: 1; text-align: center; }
.empty-state {
  text-align: center;
  padding: 40px;
  color: #999;
}
.install-modal {
  padding: 12px;
}
.install-modal p {
  margin: 0 0 8px;
}
.command-block {
  background: #1e1e1e;
  color: #4ec9b0;
  padding: 12px 16px;
  border-radius: 6px;
  font-family: monospace;
  font-size: 14px;
  margin-bottom: 12px;
}
</style>
```

- [ ] **Step 2: 验证构建**

Run: `cd AIagent_frontend && npm run build 2>&1 | tail -5`
Expected: build succeeds (may have existing warnings, but no new errors)

- [ ] **Step 3: 提交**

```bash
git add AIagent_frontend/src/views/SkillsView.vue
git commit -m "feat: add SkillsView with scan and install modal"
```

---

### Task 6: 前端 — TopBar 导航图标 + Router

**Files:**
- Modify: `AIagent_frontend/src/components/layout/TopBar.vue` (lines 8-11, 22-34)
- Modify: `AIagent_frontend/src/router/index.js` (after line 43)

- [ ] **Step 1: 修改 TopBar.vue — 导入 BuildOutline 图标**

修改 line 8-11，添加 `BuildOutline`：

```js
import {
  BusinessOutline, ChevronDownOutline,
  HardwareChipOutline, CubeOutline, BuildOutline, SettingsOutline,
  AddOutline, ChatbubbleOutline, PeopleOutline
} from '@vicons/ionicons5'
```

- [ ] **Step 2: 修改 TopBar.vue — 插入 navItems 条目**

修改 line 22-26：

```js
const navItems = [
  { key: 'agents',    icon: HardwareChipOutline, tooltip: 'Agent', path: '/agents' },
  { key: 'artifacts', icon: CubeOutline,         tooltip: '产物',  path: '/artifacts' },
  { key: 'skills',    icon: BuildOutline,        tooltip: '技能',  path: '/skills' },
  { key: 'settings',  icon: SettingsOutline,     tooltip: '设置',  path: '/settings' }
]
```

- [ ] **Step 3: 修改 TopBar.vue — 更新 activeKey 计算属性**

修改 line 28-34，添加 skills 路由判断：

```js
const activeKey = computed(() => {
  const name = route.name
  if (name === 'Agents' || name === 'AgentDetail') return 'agents'
  if (name === 'Artifacts' || name === 'ArtifactDetail') return 'artifacts'
  if (name === 'Skills') return 'skills'
  if (name === 'Settings') return 'settings'
  return ''
})
```

- [ ] **Step 4: 修改 router/index.js — 添加 /skills 路由**

在 line 43 (artifacts 路由之后) 插入：

```js
  {
    path: '/skills',
    name: 'Skills',
    component: () => import('@/views/SkillsView.vue'),
    meta: { requiresAuth: true }
  },
```

- [ ] **Step 5: 验证构建**

Run: `cd AIagent_frontend && npm run build 2>&1 | tail -5`
Expected: build succeeds

- [ ] **Step 6: 提交**

```bash
git add AIagent_frontend/src/components/layout/TopBar.vue \
        AIagent_frontend/src/router/index.js
git commit -m "feat: add skills nav icon and route to TopBar"
```

---

### Task 7: 端到端验证

- [ ] **Step 1: 启动后端**

```bash
cd mateclaw-dev/mateclaw-server && mvn spring-boot:run
```

- [ ] **Step 2: 测试 API**

```bash
curl -X POST http://localhost:18088/api/v1/skills/global/scan?type=claude_code
```

Expected: 返回 20 个技能的 JSON 数组，每个含 `name`, `description`, `installCount`, `installCommand`, `installed` 字段

- [ ] **Step 3: 启动前端**

```bash
cd AIagent_frontend && npm run dev
```

- [ ] **Step 4: 手动验证 UI**

1. 打开浏览器 → 登录 → 看到 TopBar 中 🔧 图标
2. 点击 🔧 → 进入 `/skills` 页面
3. 默认显示 Claude Code Tab
4. 点击"扫描全局 Skills" → 观察 spinner
5. 扫描完成后验证已安装/未安装状态正确
6. 点击"安装"按钮 → Modal 显示命令 + 复制按钮
7. 切换到 OpenCode Tab → 点击扫描

---

### 改动文件汇总

| 文件 | 操作 | 描述 |
|------|------|------|
| `mateclaw-server/.../skills/skills.txt` | 新建 | 20 个预定义技能数据 |
| `mateclaw-domain/.../skill/global/GlobalSkillsService.java` | 新建 | 扫描逻辑 |
| `mateclaw-server/.../controller/GlobalSkillsController.java` | 新建 | REST 端点 |
| `AIagent_frontend/src/api/skills.js` | 新建 | API 调用 |
| `AIagent_frontend/src/views/SkillsView.vue` | 新建 | 技能管理页面 |
| `AIagent_frontend/src/components/layout/TopBar.vue` | 修改 | 导航图标 + activeKey |
| `AIagent_frontend/src/router/index.js` | 修改 | 新增 /skills 路由 |
