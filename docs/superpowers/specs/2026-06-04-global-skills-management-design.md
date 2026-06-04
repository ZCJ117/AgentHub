# 全局技能管理页面

## 概述

在主页面导航栏新增 🔧 "技能"图标，打开全局技能管理页面。页面分 "Claude Code" 和 "OpenCode" 两个 Tab，展示20个预定义技能的安装状态。后端扫描本地目录判断已/未安装，未安装的提供安装命令供用户手动执行。

## 导航栏变更

在 `TopBar.vue` 的 `navItems` 数组中，"产物"和"设置"之间插入新项：

```
navItems: [
  { key: 'agents',    icon: HardwareChipOutline, tooltip: 'Agent',  path: '/agents' },
  { key: 'artifacts', icon: CubeOutline,         tooltip: '产物',  path: '/artifacts' },
  { key: 'skills',    icon: BuildOutline,        tooltip: '技能',  path: '/skills'  },  ← 新增
  { key: 'settings',  icon: SettingsOutline,     tooltip: '设置',  path: '/settings' }
]
```

## 前端改动

| 文件 | 操作 | 描述 |
|------|------|------|
| `TopBar.vue` | 修改 | `navItems` 数组插入新条目，`activeKey` 计算属性增加 skills 路由判断 |
| `router/index.js` | 修改 | 新增 `{ path: '/skills', name: 'Skills', component: SkillsView, meta: { requiresAuth: true } }` |
| `SkillsView.vue` | 新建 | 双 Tab + 扫描按钮 + 技能列表 + 安装命令 Modal |
| `api/skills.js` | 新建 | `scanGlobalSkills(type)` + `getGlobalSkills(type)` |

## 后端改动

| 文件 | 操作 | 描述 |
|------|------|------|
| `GlobalSkillsController.java` | 新建 | `POST /api/v1/skills/global/scan?type=claude_code\|opencode` |
| `GlobalSkillsService.java` | 新建 | 扫描逻辑 + 静态技能列表加载 |
| `skills.txt` | 新建 | 20 个技能定义，放入 `resources/skills/` |

## API

### 扫描全局技能

```
POST /api/v1/skills/global/scan?type=claude_code
POST /api/v1/skills/global/scan?type=opencode
```

**扫描路径：**
- `claude_code` → `~/.claude/skills/`
- `opencode` → `~/.config/opencode/skills/`

**响应：**
```json
{
  "code": 200,
  "data": [
    {
      "name": "find-skills",
      "description": "智能匹配与推荐适配的技能，是 AI 代理的应用商店搜索框",
      "installCount": "608.7k",
      "installCommand": "npx skills add vercel-labs/find-skills",
      "installed": true
    }
  ]
}
```

## 扫描逻辑

1. 从 classpath 加载 `skills/skills.txt`，解析为 `List<SkillDef>`
2. 根据 type 参数确定扫描目录
3. `Files.list(scanPath)` 获取所有子目录名
4. 对比：子目录名是否在 SkillDef.name 集合中
5. 返回带 `installed` 标志的完整列表

## 静态技能文件

位置：`mateclaw-server/src/main/resources/skills/skills.txt`

格式（TSV）：
```
find-skills	智能匹配与推荐适配的技能	608.7k	npx skills add vercel-labs/find-skills
bash	提供终端运维、脚本执行能力	418.6k	npx skills add vercel-labs/bash
...
```

共 20 条，启动时加载。

## 页面交互

1. 打开页面 → 默认显示 Claude Code Tab，列表为空，显示扫描引导
2. 点击"🔍 扫描全局 Skills" → 前端 spinner → 调用 API
3. 扫描完成 → 列表渲染：
   - `installed: true` → 绿色 "✓ 已安装"
   - `installed: false` → 红色 "未安装" + "安装" 按钮
4. 点击"安装" → Modal 弹出安装命令 + 📋 复制按钮
5. 切换 Tab → 前端缓存结果，切换不重新扫描

## 边界情况

| 场景 | 行为 |
|------|------|
| 扫描目录不存在 | 所有技能显示"未安装"，提示目录不存在 |
| 扫描目录为空 | 所有技能显示"未安装" |
| 部分已安装 | 精确匹配子目录名 |
| skills.txt 读取失败 | 启动时校验，缺失则启动失败 |
| 用户切换 Tab | 使用缓存结果，手动可重新扫描 |

## 验收标准

1. TopBar 中 🔧 图标位于"产物"和"设置"之间
2. 点击图标进入 `/skills` 页面，默认显示 Claude Code Tab
3. 点击"扫描全局 Skills"触发扫描，前端显示 spinner
4. 扫描完成后已安装显示绿色"已安装"，未安装显示"安装"按钮
5. 点击"安装"弹出安装命令 Modal
6. 切换 Claude Code / OpenCode Tab 正常工作
7. 未安装 Claude Code 时扫描不报错，全部显示未安装
