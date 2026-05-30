# AI 协作开发记录

## 项目背景

MateClaw 多 Agent 协作平台后端重构：将单 Maven 模块（901 个 Java 文件）拆分为三层架构。

## 协作方式总览

```
需求澄清 → 方案设计(Spec) → 实施计划(Plan) → 分步执行 → 验证闭环
   │            │               │              │           │
   └─ 对话共创    └─ 文档沉淀     └─ 任务拆解     └─ AI执行   └─ 编译验证
```

## 协作规范体系

本项目建立了三层协作规范，存放在 `.claude/` 和 `docs/` 目录下：

### 1. CLAUDE.md — 项目级指令（Rules）

`.claude/CLAUDE.md` 定义了 AI 在项目中的行为准则：

```markdown
## 1. 编码前先思考
## 2. 简单优先
## 3. 手术式修改
## 4. 目标驱动的执行
## 5. Loom项目指南
```

这相当于给 AI 的"项目 README"，每轮对话自动注入上下文，确保 AI 理解：
- 技术栈约束（Vue 3 + Spring Boot 3.5 + MyBatis Plus）
- 目录结构
- 关键约束（hash 路由、SSE 不断开）

### 2. Spec 文档 — 设计方案（Spec）

`docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`

重构 Spec 包含：
- 四模块架构设计
- 依赖方向约束
- 每个模块的职责边界
- 迁移策略（六阶段）
- 关键决策记录表

### 3. Plan 文档 — 实施计划（Plan）

`docs/superpowers/plans/YYYY-MM-DD-<topic>.md`

实施 Plan 包含：
- 每个 Task 的精确文件路径
- 每步操作的具体命令
- 预期结果和验证方式
- Commit 粒度

## 本次重构的完整协作流程

### 阶段一：需求对齐（Brainstorming）

**协作模式**：AI 先探索项目结构，再逐个提问澄清需求。

1. AI 扫描了 `mateclaw-server` 的 901 个文件，分析当前包结构
2. 通过 4 个关键问题对齐需求：
   - Q1: 核心痛点？→ Service 过厚 + 模块边界混乱
   - Q2: 推进方式？→ 一次性大重构
   - Q3: 分层粒度？→ 领域+分层混合
   - Q4: 模块数量？→ 精确 4 个模块
3. AI 提出 3 个方案并推荐最优方案

**产出**：方案 B（四模块架构），用户确认后进入设计阶段。

### 阶段二：方案设计（Spec）

**协作模式**：AI 呈现分层设计，逐模块确认职责边界。

**产出**：`docs/superpowers/specs/2026-05-30-mateclaw-layered-architecture-design.md`

核心内容：
- 模块依赖方向图
- 每个模块的内部包结构
- 包含/排除规则
- 迁移策略（6 阶段）
- 跨域调用规范
- 关键决策记录表

用户补充了 `adapters/` 目录的归属，AI 更新设计并重新确认。

### 阶段三：实施计划（Plan）

**协作模式**：AI 根据 Spec 编写可执行的任务清单，精确到文件级别。

**产出**：`docs/superpowers/plans/2026-05-30-mateclaw-layered-architecture-refactor.md`

核心内容：
- 7 个 Task，每个包含文件清单、操作步骤、验证命令
- Task 1: 创建模块骨架（pom.xml）
- Task 2-5: 逐模块迁移文件
- Task 6: 测试迁移
- Task 7: 全量验证

### 阶段四：执行（Inline Execution）

**协作模式**：AI 按 Task 顺序执行，每个 Task 完成后编译验证。

关键执行记录：

| 步骤 | 内容 | 结果 |
|------|------|------|
| Task 1 | 创建 4 个模块 pom.xml + 目录 | ✅ |
| Task 2 | 迁移 common 层（6 文件） | ✅ 编译通过 |
| Task 3-4 | 迁移 domain + infrastructure（~850 文件） | ⚠️ 循环依赖 |
| 架构调整 | 合并 domain+infra 为单模块 | ✅ 解决问题 |
| Task 5 | 收尾 server 层（64 文件） | ✅ 编译通过 |

### 阶段五：架构调整决策

执行过程中遇到了 Spec 未曾预见的编译时循环依赖问题：

**问题**：domain 代码大量引用 infrastructure 类，但 infrastructure 也依赖 domain 接口，Maven 不允许循环依赖。

**决策**：将 infrastructure 合并入 domain 模块，通过包级分离（`vip.mate.domain.*` vs `vip.mate.infra.*`）保持内部边界。

**记录**：这是一个重要的架构取舍——在"纯粹的模块分离"和"实际编译可行性"之间选择了后者。该决策已记录在 git commit 中。

## Agent 友好的仓库特征

### 1. 结构化文档目录

```
docs/
├── superpowers/
│   ├── specs/          # 设计文档（What & Why）
│   └── plans/          # 实施计划（How）
└── AI-COLLABORATION.md # 协作记录（本文件）
```

### 2. 项目级 AI 指令

```
.claude/
├── CLAUDE.md           # 项目规则（自动注入AI上下文）
├── specs/              # 技术规范引用
└── settings.local.json # AI 工具配置
```

### 3. 清晰的 Commit 历史

```
ce0819e4 删除不必要的
454575df 初始化项目
...
53ec5fdb feat: create four-module skeleton with pom.xml and directory structures
157f885a docs: add four-module migration implementation plan
a52e6718 docs: add MateClaw layered architecture refactoring design spec
e1d6d793 refactor: final three-module architecture, main source compiles
de871b80 fix: move MateClawApplication to src/main/java root, fix MapperScan
```

每个 commit 做一件事，消息描述 Why 而非 What。

### 4. Spec-Plan-Execute 协作模式

这套模式使 AI 能够：
- **理解上下文**：通过 CLAUDE.md 了解项目约束
- **设计方案**：通过 brainstorming + spec 对齐需求
- **拆解任务**：通过 plan 将大任务拆为可执行步骤
- **验证闭环**：每个步骤编译验证，失败回滚修复

## 可复用的协作技巧

### Tip 1: Spec 中记录"关键决策表"

```markdown
| 决策 | 结论 | 理由 |
|------|------|------|
| Maven 模块数量 | 3 个 | 平衡分离度与编译可行性 |
| Service 是否必须接口化 | 是 | 解决模块边界混乱的核心手段 |
```

这张表让后来的 AI（或人）理解当时为什么做这个选择，避免推翻重来。

### Tip 2: Plan 中给出精确的文件路径和命令

```markdown
- Modify: `mateclaw-dev/pom.xml:15-17`
```

模糊指令 = AI 猜测 = 出错。精确指令 = 可复现。

### Tip 3: 每次变更后立即编译验证

```
Task → 执行 → mvn compile → 失败 → 修复 → 再编译 → 提交
```

这确保每一步的质量，避免错误累积到最后才暴露。

### Tip 4: 架构决策随代码一起提交

```bash
git commit -m "refactor: merge domain+infra to resolve circular Maven dependency"
```

让 commit 历史成为决策日志的一部分。
