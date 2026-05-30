# MateClaw 分层架构重构设计

## 概述

将当前单 Maven 模块 `mateclaw-server`（901 个 Java 文件）重构为四个 Maven 模块的四层架构，解决 Service 过厚和模块边界混乱两大痛点。

## 四模块架构

```
mateclaw-dev/
├── mateclaw-common/            # 公共层 — 无业务语义的基础代码
├── mateclaw-domain/            # 领域层 — 核心业务逻辑
├── mateclaw-infrastructure/    # 基础设施层 — 持久化、外部适配器
└── mateclaw-server/            # 表现层 — Controller + 启动入口
```

## 依赖方向（严格单向）

```
mateclaw-server ────────→ mateclaw-domain ──────→ mateclaw-common
      │                          ↑                       ↑
      └──→ mateclaw-infrastructure ────────────────────→  ┘
```

- **mateclaw-common**：无项目内依赖
- **mateclaw-domain**：仅依赖 common
- **mateclaw-infrastructure**：依赖 domain + common
- **mateclaw-server**：依赖 domain + infrastructure（需要 infrastructure 的 Bean 实现完成依赖注入）

## 各模块详细设计

### 1. mateclaw-common — 公共层

**职责**：提供全项目共用的无业务语义基础能力。

**包含内容**：
- `vip.mate.common.result.R` — 统一 HTTP 响应体
- `vip.mate.common.exception.MateClawException` — 业务异常基类
- `vip.mate.common.security.*` — SecurityContext、权限注解
- `vip.mate.common.base.*` — BaseEntity、基础常量
- `vip.mate.common.util.*` — 纯工具函数

**排除内容**：任何 Entity、Mapper、Service 接口或实现。

**注意**：`I18nAutoConfig`（国际化自动配置）放入 common 层，因为它是全项目通用的基础设施配置。

### 2. mateclaw-domain — 领域层

**职责**：存放全部业务逻辑。这是项目核心，其他层围绕它服务。

**内部结构**：按业务领域分包（约 30 个域），每个域包内结构统一：

```
vip/mate/domain/
├── agent/
│   ├── AgentService.java          # 接口
│   ├── AgentServiceImpl.java      # 实现
│   ├── AgentRepository.java       # 持久化接口（只定义）
│   └── model/
│       ├── AgentEntity.java
│       └── AgentVO.java
├── workspace/
│   ├── conversation/
│   │   ├── ConversationService.java
│   │   ├── ConversationServiceImpl.java
│   │   ├── ConversationRepository.java
│   │   └── model/
│   │       ├── ConversationEntity.java
│   │       ├── MessageEntity.java
│   │       └── ConversationVO.java
│   ├── core/
│   └── document/
├── channel/
│   ├── ChannelService.java        # 接口（infrastructure 实现具体渠道）
│   └── model/ChannelEntity.java
├── llm/
│   ├── LlmClient.java             # 接口（infrastructure 实现具体 LLM 调用）
│   └── model/
├── memory/
├── tool/
├── skill/
├── wiki/
├── orchestrator/
├── artifact/
├── auth/
├── dashboard/
├── group/
├── planning/
├── approval/
├── audit/
├── message/
├── task/
├── notification/
└── system/
```

**关键约束**：

1. **Service 必须接口 + 实现分离**，接口公开，实现包私有或模块内可见
2. **Repository 只定义接口**，不在 domain 层提供实现
3. **跨域调用只能依赖其他域的 Service 接口**，绝不能直接注入 Mapper
4. **Entity 使用 MyBatis Plus 注解**（`@TableName`、`@TableId` 等），这些注解是持久化描述，不算泄漏
5. **VO 定义在此层**，因为它是领域数据的视图表达
6. **仅依赖 mateclaw-common + Spring 注解层**（`@Service`、`@Component`、`@Transactional` 等）。MyBatis Plus 注解在 Entity 上允许，但不可注入 Mapper 实现类（Mapper 实现在 infrastructure 层）

	7. 领域级 AutoConfiguration（如 `MemoryAutoConfiguration`、`SkillLifecycleAutoConfiguration`）保留在 domain 层各自领域包内，Spring Bean 组装定义属于领域逻辑
### 3. mateclaw-infrastructure — 基础设施层

**职责**：实现 domain 层定义的接口，封装所有外部系统通信。

**内部结构**：

```
mateclaw-infrastructure/
├── src/main/java/vip/mate/infra/
│   ├── agent/AgentRepositoryImpl.java
│   ├── workspace/
│   │   └── conversation/ConversationRepositoryImpl.java
│   ├── channel/                        # 渠道适配器（实现 ChannelService）
│   │   ├── dingtalk/DingTalkAdapter.java
│   │   ├── feishu/FeishuAdapter.java
│   │   ├── wecom/WecomAdapter.java
│   │   ├── discord/DiscordAdapter.java
│   │   ├── slack/SlackAdapter.java
│   │   ├── telegram/TelegramAdapter.java
│   │   ├── qq/QQAdapter.java
│   │   ├── webchat/WebChatAdapter.java
│   │   └── weixin/WeixinAdapter.java
│   ├── llm/
│   │   ├── chatmodel/OpenAiClient.java  # 实现 LlmClient
│   │   ├── embedding/
│   │   ├── routing/
│   │   └── failover/
│   ├── tool/mcp/runtime/
│   ├── tool/browser/
│   ├── wiki/storage/
│   ├── skill/acp/
│   ├── memory/search/
│   └── config/
│       ├── MybatisPlusConfig.java       # MyBatis Plus 分页插件等
│       ├── FlywayConfig.java            # 数据库迁移
│       └── SchedulerConfig.java         # 定时任务
└── src/main/resources/
    └── adapters/
        ├── claude-adapter.mjs           # CLI 进程适配脚本
        └── opencode-adapter.mjs
```

**包含内容**：
- 所有 MyBatis Mapper 实现（实现 domain 层的 Repository 接口）
- 所有渠道适配器（实现 domain 层的 ChannelService 接口）
- LLM 客户端实现（实现 domain 层的 LlmClient 接口）
- MCP 运行时、浏览器自动化、搜索引擎等工具实现
- 基础设施相关 Spring 配置（MyBatis、Flyway、Scheduler、ShedLock）
- **外部 CLI 适配脚本**（`adapters/` 目录下的 `.mjs` 文件）移到 `src/main/resources/adapters/`

**排除内容**：MVC 配置（SecurityConfig、WebMvcConfig 等属于表现层配置，应在 server 模块）。

### 4. mateclaw-server — 表现层

**职责**：HTTP 接口层 + Spring Boot 启动入口。

**内部结构**：

```
mateclaw-server/src/main/java/vip/mate/server/
├── agent/AgentController.java
├── agent/TemplateController.java
├── workspace/conversation/ConversationController.java
├── workspace/conversation/TokenUsageController.java
├── channel/controller/
│   ├── ChannelController.java
│   └── ChannelWebhookController.java
├── llm/controller/
├── memory/controller/
├── tool/controller/
├── skill/controller/
├── wiki/controller/
├── artifact/controller/
├── auth/controller/
├── orchestrator/controller/
├── ...
├── MateClawApplication.java           # @SpringBootApplication 入口
├── config/
│   ├── SecurityConfig.java
│   ├── WebMvcConfig.java
│   ├── WebSocketConfig.java
│   ├── AsyncSecurityConfig.java
│   └── JacksonConfig.java
└── sse/
    └── ChatStreamTracker.java
```

**Controller 约束**：
- Controller 只做三件事：参数校验 → 调用 domain Service 接口 → 组装响应
- **禁止**在 Controller 中包含业务逻辑
- **禁止**直接注入 Mapper（Mapper 属于 infrastructure 层，不应在 server 层可见）
- **禁止**直接操作 Entity 持久化

**全局配置**：

```
mateclaw-server/src/main/resources/
├── application.yml
├── application-mysql.yml
├── logback-spring.xml
├── messages.properties
└── messages_en.properties
```

以及所有样板资源（prompts、skill-templates、templates 等）保留在 server 模块的 resources 中。

## 重构约束

**重构以文件移动为主，不修改业务逻辑代码。** 只允许以下修改：

1. **移动文件** — 将 Java 文件和资源文件从 `mateclaw-server` 移动到对应新模块
2. **修改包名** — 文件移动后包路径变更，对应修改 `package` 声明和 import 语句
3. **修改 pom.xml** — 父子模块的依赖声明、模块间依赖
4. **编译修复** — 因包路径变更、模块拆分导致的编译错误（如跨模块 import 路径更新）
5. **去掉违规依赖** — Controller 直接注入 Mapper 等违反分层约束的代码，改为注入 Service 接口

**禁止修改**：任何业务逻辑、方法实现、算法、流程控制代码。

## 迁移策略

### 阶段一：创建模块骨架
1. 在 `mateclaw-dev/pom.xml` 中添加四个子模块声明
2. 创建各模块的 `pom.xml`，声明各自的依赖和模块间依赖
3. 创建各模块的 `src/main/java` 和 `src/main/resources` 目录

### 阶段二：拆分 common
1. 从 `mateclaw-server` 中提取公共代码到 `mateclaw-common`
2. 包括：`R.java`、`MateClawException`、SecurityContext、BaseEntity、工具类
3. 验证：`mateclaw-common` 编译通过

### 阶段三：拆分 domain
1. 将各领域的 Entity、VO、Service 接口、Service 实现、Repository 接口移入 domain
2. 修改跨域引用为接口依赖
3. Repository 接口保留 MyBatis Plus 注解在 Entity 上，但不含实现
4. 验证：`mateclaw-domain` 编译通过

### 阶段四：拆分 infrastructure
1. 将 Mapper 实现移入 infrastructure，实现 domain Repository 接口
2. 将渠道适配器移入 infrastructure
3. 将 LLM 客户端移入 infrastructure
4. 将 CLI 适配脚本移入 `resources/adapters/`
5. 验证：`mateclaw-infrastructure` 编译通过

### 阶段五：收尾 server
1. 清理 server 模块，只保留 Controller 和全局配置
2. 移除 Controller 中的 Mapper 直接注入
3. 验证：全量编译通过、应用启动正常

### 阶段六：验证与测试
1. 运行全部现有测试（如有）
2. 启动应用，验证 HTTP API 正常响应
3. 验证 SSE 流式推送正常
4. 验证渠道适配器（飞书、钉钉等）正常工作

## Service 拆分原则

针对当前 Service 过厚的问题，在迁移过程中同步拆分：

1. **按职责拆分**：一个 Service 文件超过 300 行时，审视是否可以拆出独立的子 Service
2. **跨域逻辑归属**：涉及多个领域的编排逻辑，放在调用方 Service 中，不塞进被调用方
3. **持久化逻辑下沉**：复杂查询、批量操作封装在 Repository 接口中，Service 只调 Repository
4. **外部调用隔离**：任何外部 API 调用（LLM、消息渠道、搜索引擎）通过 infrastructure 层接口完成

## 跨域调用规范

```
// ✅ 正确：Controller 调 Service 接口
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversationService;  // 接口
}

// ✅ 正确：Service 调其他领域 Service 接口
public class AgentServiceImpl implements AgentService {
    private final MemoryService memoryService;   // 接口，来自 memory 域
    private final LlmClient llmClient;           // 接口，来自 llm 域
}

// ❌ 错误：Controller 直接调 Mapper
public class ConversationController {
    private final ConversationMapper mapper;  // 禁止
}

// ❌ 错误：Service 直接调其他域的 Mapper
public class AgentServiceImpl implements AgentService {
    private final MemoryRecallMapper mapper;  // 禁止，应调 MemoryService
}
```

## 关键决策记录

| 决策 | 结论 | 理由 |
|------|------|------|
| Maven 模块数量 | 4 个 | 平衡分离度和管理成本 |
| Service 是否必须接口化 | 是 | 解决模块边界混乱的核心手段 |
| Entity 注解放哪层 | domain 层 | MyBatis Plus 注解是持久化描述，不构成基础设施泄漏 |
| Controller 能否直接调 Mapper | 否 | 破坏分层，违反依赖方向 |
| 渠道适配器归属 | infrastructure | 属于外部系统通信实现 |
| CLI 适配脚本归属 | infrastructure/resources/adapters/ | 运行时资源，由 infrastructure 管理 |
| Maven 模块粒度 | 合并关联领域到 4 个大模块 | 约 30 个领域不宜各自独立为模块 |
