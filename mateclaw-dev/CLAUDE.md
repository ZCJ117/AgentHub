# CLAUDE.md

## 1. 编码前先思考

**不要假设。不要隐藏困惑。明确说明权衡。**

在实现之前：
- 明确陈述你的假设。如果不确定，就提问。
- 如果存在多种解释，请呈现出来——不要默默选择。
- 如果有更简单的方法，就说出来。在必要时提出异议。
- 如果有什么不清楚，停下来。指出困惑所在，然后提问。

## 2. 简单优先

**用最少的代码解决问题。不加入推测性内容。**

- 不要添加超出需求的功能。
- 不要为一次性代码创建抽象。
- 不要添加未被要求的"灵活性"或"可配置性"。
- 不要为不可能发生的场景添加错误处理。
- 如果你写了 200 行，但 50 行就能搞定，那就重写。

问问自己："资深工程师会说这过于复杂吗？" 如果会，就简化。

## 3. 手术式修改

**只触碰你必须修改的部分。只清理你自己造成的混乱。**

编辑现有代码时：
- 不要"改进"邻近的代码、注释或格式。
- 不要重构没有问题的部分。
- 与现有风格保持一致，即使你会用不同的方式。
- 如果你注意到无关的死代码，提及它——但不要删除。

当你的修改造成孤立项时：
- 移除因你的修改而变得未使用的导入、变量或函数。
- 除非要求，否则不要移除既有的死代码。

检验标准：每一处修改行都应直接追溯到用户的需求。

## 4. 目标驱动的执行

**定义成功标准。循环验证直到通过。**

将任务转化为可验证的目标：
- "添加验证" → "为无效输入编写测试，然后使其通过"
- "修复 bug" → "编写重现该 bug 的测试，然后使其通过"
- "重构 X" → "确保测试在重构前后都能通过"

对于多步骤任务，陈述一个简要计划：
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

## 5. MateClaw 后端指南

MateClaw 是 AI Agent 运行引擎 — 多智能体编排、流式通信、可扩展技能体系。

**技术栈：**
- Java 21 + Spring Boot 3.5 + Spring AI 1.1
- MyBatis Plus 3.5 + Flyway（数据库迁移）
- H2（开发，file:./data/mateclaw） / MySQL 8.0（生产）
- Maven 多模块，父 POM 版本 `1.4.0-SNAPSHOT`
- SSE 流式推送 + WebSocket
- Spring Security + JWT + PAT 个人令牌

**模块结构：**
```
mateclaw-dev/
├── pom.xml                  Maven 父 POM（reactor + dependencyManagement）
├── mateclaw-common/         公共层：异常（MateClawException）、统一响应（R）、工具类
├── mateclaw-domain/         领域层：Entity + Mapper + Service，按领域分包
│   └── vip/mate/domain/     acp/ agent/ approval/ artifact/ auth/ channel/ llm/
│                            memory/ orchestrator/ skill/ tool/ wiki/ workspace/ ...
├── mateclaw-server/         Spring Boot 主模块，API 入口
│   └── vip/mate/server/     按领域分包的 Controller 层
│   └── vip/mate/            MateClawApplication.java（启动类）
└── adapters/                外部 ACP 适配器（claude-adapter.mjs, opencode-adapter.mjs）
```

**分层约束：**
- `mateclaw-common`：不依赖其他模块，可被任意模块引用
- `mateclaw-domain`：依赖 common，包含 Entity、Mapper（MyBatis Plus）、Service 业务逻辑
- `mateclaw-server`：依赖 domain，包含 Controller、Config、过滤器/拦截器
- Controller 不写业务逻辑，委托给 domain 层的 Service
- Mapper 统一放在 `vip.mate.domain.**.repository` 包下（对应 `@MapperScan`）

**数据访问模式：**
- MyBatis Plus 的 `BaseMapper<T>` 作为 Repository，放在 `repository/` 子包
- Service 通过注入 Mapper 访问数据库，不要在 Controller 里直接调 Mapper
- Flyway 迁移脚本在 `mateclaw-server/src/main/resources/db/migration/h2/`，命名 `V{序号}__{描述}.sql`
- H2 兼容 MySQL 模式（`MODE=MySQL`），迁移 SQL 尽量与 MySQL 兼容

**API 规范：**
- 基路径：`/api/v1/`
- 统一响应格式：`R<T>` — `{ "code": 200, "message": "success", "data": {} }`
- 异常通过 `MateClawException` 抛出，由 `RHttpStatusAdvice` 全局处理
- Controller 按领域分包：`agent/controller/`、`auth/controller/` 等

**关键约束：**
- 启动类位于 `vip.mate.MateClawApplication`，端口 18088
- `@MapperScan("vip.mate.domain.**.repository")` — 新增 Mapper 必须在此路径下
- 开发默认 profile 使用 H2 文件数据库，无需 MySQL
- 运行命令：`cd mateclaw-server && mvn spring-boot:run`
- MyBatis Plus 分页插件已配置，DbType 自动检测，不要手动硬编码
- SSE 聊天接口务必处理 `lastEventId` 断线重连
- 数据库迁移禁止使用 Flyway 的 `${}` 占位符（全局已禁用 placeholder-replacement）
- 虚拟线程已启用（`spring.threads.virtual.enabled: true`）
- Swagger UI：`/swagger-ui.html`，API 文档 JSON：`/v3/api-docs`
- 父项目 CLAUDE.md 位于 `D:\code\Loom\.claude\CLAUDE.md`，API 文档位于 `D:\code\Loom\.claude\specs\API.md`
