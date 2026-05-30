# MateClaw 分层架构重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 mateclaw-dev 从单模块（mateclaw-server，901+297 个 Java 文件）重构为四层 Maven 模块架构（common / domain / infrastructure / server），文件移动为主，不改业务逻辑。

**Architecture:** 四 Maven 模块单向依赖。server → domain + infrastructure，infrastructure → domain，domain → common。

**Tech Stack:** Maven 多模块、Spring Boot 3.5、MyBatis Plus、Java 21

**约束:** 只允许：移动文件、改 package/import、改 pom.xml、编译修复、去掉 Controller 直调 Mapper。禁止改任何业务逻辑。

---

## 文件归类规则

重构前包路径 `vip.mate.<domain>.<layer>`，重构后如下：

### mateclaw-common（包路径不变：`vip.mate.common.*`）
- `vip/mate/common/result/R.java`
- `vip/mate/common/result/RHttpStatusAdvice.java`
- `vip/mate/common/result/ResultCode.java`
- `vip/mate/common/security/SecretEquals.java`
- `vip/mate/exception/GlobalExceptionHandler.java`
- `vip/mate/exception/MateClawException.java`
- `vip/mate/i18n/I18nAutoConfig.java`
- `vip/mate/i18n/I18nService.java`
- `vip/mate/i18n/LocaleAwareToolCallback.java`

### mateclaw-domain（包路径：`vip.mate.domain.<domain>.*`）
属于 domain 层的文件（移入时 `vip.mate.xxx` → `vip.mate.domain.xxx`）：
- 所有 `model/` 下的 Entity、VO、DTO、Request 类
- 所有 `service/` 下的 Service 接口和实现
- 所有 `repository/` 下的 Mapper 接口（MyBatis Plus 接口即 Repository）
- 所有 `event/` 下的领域事件
- 没有 controller 子包的业务类（如 `vip/mate/agent/AgentService.java`）
- 业务配置类：`ConversationWindowProperties`、`GraphObservationProperties`、`ToolTimeoutProperties`
- 领域 AutoConfiguration：`MemoryAutoConfiguration`、`SkillLifecycleAutoConfiguration`、`SkillSynthesisAutoConfiguration`、`SkillWorkspaceAutoConfiguration`、`WikiAutoConfiguration`、`LlmCacheAutoConfiguration`
- `vip/mate/agent/graph/` 全部（状态图引擎，核心业务逻辑）
- `vip/mate/channel/` 的接口和抽象类：`ChannelAdapter.java`、`StreamingChannelAdapter.java`、`AbstractChannelAdapter.java`、`ChannelMessage.java` 等
- `vip/mate/tool/` 的业务接口和模型（非具体外部实现部分）
- `vip/mate/workspace/conversation/config/ConversationSchemaMigration.java`
- `vip/mate/workspace/core/config/WorkspaceSchemaMigration.java`

### mateclaw-infrastructure（包路径：`vip.mate.infra.<domain>.*`）
属于 infrastructure 层的文件（移入时 `vip.mate.xxx` → `vip.mate.infra.xxx`）：
- 所有渠道适配器具体实现：`vip/mate/channel/dingtalk/`、`vip/mate/channel/feishu/`、`vip/mate/channel/wecom/`、`vip/mate/channel/discord/`、`vip/mate/channel/slack/`、`vip/mate/channel/telegram/`、`vip/mate/channel/qq/`、`vip/mate/channel/webchat/`、`vip/mate/channel/weixin/`
- 渠道基础设施：`vip/mate/channel/web/WebChannelAdapter.java`、`vip/mate/channel/leader/`、`vip/mate/channel/health/`、`vip/mate/channel/media/`、`vip/mate/channel/verifier/`、`vip/mate/channel/qrcode/`、`vip/mate/channel/cards/`
- LLM 客户端实现：`vip/mate/llm/chatgpt/`、`vip/mate/llm/chatmodel/`、`vip/mate/llm/embedding/`、`vip/mate/llm/gemini/`、`vip/mate/llm/anthropic/`、`vip/mate/llm/oauth/`、`vip/mate/llm/failover/`、`vip/mate/llm/config/OllamaAutoDiscoveryRunner.java`
- 搜索引擎实现：`vip/mate/tool/search/`
- 图片/视频/音乐/3D 具体 Provider：`vip/mate/tool/image/provider/`、`vip/mate/tool/video/provider/`、`vip/mate/tool/music/provider/`、`vip/mate/tool/model3d/provider/`
- MCP 运行时：`vip/mate/tool/mcp/runtime/`
- 浏览器：`vip/mate/tool/browser/`
- ACP 客户端：`vip/mate/acp/client/`
- Agent CLI：`vip/mate/agent/cli/`
- Agent bridge：`vip/mate/agent/bridge/`（WebSocket 远程代理通信）
- 基础设施配置：`vip/mate/config/MybatisPlusConfig.java`、`vip/mate/config/ShedLockConfig.java`、`vip/mate/config/FlywayRepairConfig.java`、`vip/mate/config/DatabaseBootstrapRunner.java`
- `vip/mate/skill/acp/`、`vip/mate/skill/installer/`、`vip/mate/skill/mcp/`
- 外部 CLI 适配脚本：`adapters/claude-adapter.mjs`、`adapters/opencode-adapter.mjs` → `src/main/resources/adapters/`

### mateclaw-server（包路径：`vip.mate.server.<domain>.*`）
属于表现层的文件（移入时 `vip.mate.xxx.controller.` → `vip.mate.server.xxx.`）：
- 所有 `controller/` 目录下的 Controller 类
- `vip/mate/channel/web/ChatController.java`
- `vip/mate/channel/web/ChatStreamTracker.java`
- `vip/mate/channel/web/TalkModeWebSocketHandler.java`
- `vip/mate/channel/web/Utf8SseEmitter.java`
- `vip/mate/channel/web/SegmentSupersedeDetector.java`
- `vip/mate/channel/webchat/WebChatController.java`
- `vip/mate/agent/runtime/AgentRuntimeController.java`
- `vip/mate/approval/ApprovalController.java`
- `vip/mate/auth/pat/PersonalAccessTokenController.java`
- `vip/mate/channel/qrcode/ChannelQRCodeController.java`
- `vip/mate/notification/NotificationController.java`
- `vip/mate/skill/secret/SkillSecretController.java`
- `vip/mate/skill/template/SkillTemplateController.java`
- `vip/mate/system/featureflag/FeatureFlagController.java`
- `vip/mate/tool/browser/BrowserHealthController.java`
- `vip/mate/tool/document/GeneratedFileController.java`
- `vip/mate/MateClawApplication.java`
- `vip/mate/config/SecurityConfig.java`、`vip/mate/config/WebMvcConfig.java`、`vip/mate/config/WebSocketConfig.java`、`vip/mate/config/JacksonConfig.java`、`vip/mate/config/AsyncSecurityConfig.java`、`vip/mate/config/JwtAuthFilter.java`、`vip/mate/config/LoginRateLimitFilter.java`、`vip/mate/config/SpaForwardController.java`、`vip/mate/config/SecurityStartupValidator.java`、`vip/mate/config/WorkspaceAccessInterceptor.java`、`vip/mate/config/AgentBridgeHandshakeInterceptor.java`
- 所有 `src/main/resources/` 下的文件（application.yml、messages、prompts、skills、templates 等）
- 所有 `src/test/` 下的测试文件（按对应被测类所在模块分配）

---

### Task 1: 创建模块骨架和 pom.xml

**Files:**
- Modify: `mateclaw-dev/pom.xml:15-17`（modules 声明）
- Create: `mateclaw-dev/mateclaw-common/pom.xml`
- Create: `mateclaw-dev/mateclaw-domain/pom.xml`
- Create: `mateclaw-dev/mateclaw-infrastructure/pom.xml`
- Modify: `mateclaw-dev/mateclaw-server/pom.xml`（精简依赖，增加模块间依赖）

- [ ] **Step 1: 更新根 pom.xml 的 modules 声明**

修改 `mateclaw-dev/pom.xml` 第 15-18 行：

```xml
<modules>
    <module>mateclaw-common</module>
    <module>mateclaw-domain</module>
    <module>mateclaw-infrastructure</module>
    <module>mateclaw-server</module>
</modules>
```

同时在 `dependencyManagement` 的 "MateClaw Modules" 注释后添加内部模块版本管理：

```xml
<!-- ==================== MateClaw Modules ==================== -->
<dependency>
    <groupId>vip.mate</groupId>
    <artifactId>mateclaw-common</artifactId>
    <version>${revision}</version>
</dependency>
<dependency>
    <groupId>vip.mate</groupId>
    <artifactId>mateclaw-domain</artifactId>
    <version>${revision}</version>
</dependency>
<dependency>
    <groupId>vip.mate</groupId>
    <artifactId>mateclaw-infrastructure</artifactId>
    <version>${revision}</version>
</dependency>
```

- [ ] **Step 2: 创建 mateclaw-common/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mateclaw</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mateclaw-common</artifactId>
    <packaging>jar</packaging>
    <name>MateClaw Common</name>
    <description>Shared result types, exceptions, security, i18n, and utilities</description>

    <dependencies>
        <!-- Spring Boot starter for annotations -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 创建 mateclaw-domain/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mateclaw</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mateclaw-domain</artifactId>
    <packaging>jar</packaging>
    <name>MateClaw Domain</name>
    <description>Business domain logic — entities, services, repository interfaces</description>

    <dependencies>
        <!-- Internal -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mateclaw-common</artifactId>
        </dependency>

        <!-- Spring -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- MyBatis Plus (annotations on Entity) -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-jsqlparser</artifactId>
        </dependency>

        <!-- Spring AI -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-anthropic</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-webflux</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-graph-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-webflux</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

        <!-- JJWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- Caffeine Cache -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>

        <!-- JGraphT -->
        <dependency>
            <groupId>org.jgrapht</groupId>
            <artifactId>jgrapht-core</artifactId>
        </dependency>

        <!-- Pebble -->
        <dependency>
            <groupId>io.pebbletemplates</groupId>
            <artifactId>pebble</artifactId>
        </dependency>

        <!-- ShedLock (annotations) -->
        <dependency>
            <groupId>net.javacrumbs.shedlock</groupId>
            <artifactId>shedlock-spring</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- SnakeYAML -->
        <dependency>
            <groupId>org.yaml</groupId>
            <artifactId>snakeyaml</artifactId>
        </dependency>

        <!-- Hutool -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: 创建 mateclaw-infrastructure/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mateclaw</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mateclaw-infrastructure</artifactId>
    <packaging>jar</packaging>
    <name>MateClaw Infrastructure</name>
    <description>Persistence implementations, channel adapters, LLM clients, external integrations</description>

    <dependencies>
        <!-- Internal -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mateclaw-domain</artifactId>
        </dependency>

        <!-- MyBatis Plus Runtime -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-jsqlparser</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>

        <!-- Channel SDKs -->
        <dependency>
            <groupId>com.dingtalk.open</groupId>
            <artifactId>dingtalk-stream</artifactId>
        </dependency>
        <dependency>
            <groupId>com.larksuite.oapi</groupId>
            <artifactId>oapi-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>net.dv8tion</groupId>
            <artifactId>JDA</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>club.minnced</groupId>
                    <artifactId>opus-java</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>com.slack.api</groupId>
            <artifactId>slack-api-client</artifactId>
        </dependency>
        <dependency>
            <groupId>com.slack.api</groupId>
            <artifactId>bolt-socket-mode</artifactId>
        </dependency>
        <dependency>
            <groupId>org.glassfish.tyrus.bundles</groupId>
            <artifactId>tyrus-standalone-client</artifactId>
        </dependency>

        <!-- Browser & QR -->
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.zxing</groupId>
            <artifactId>core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.zxing</groupId>
            <artifactId>javase</artifactId>
        </dependency>

        <!-- Document processing -->
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.xmlgraphics</groupId>
            <artifactId>batik-transcoder</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.xmlgraphics</groupId>
            <artifactId>batik-codec</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-parser-pdf-module</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-parser-microsoft-module</artifactId>
        </dependency>

        <!-- Markdown/PDF -->
        <dependency>
            <groupId>org.xhtmlrenderer</groupId>
            <artifactId>flying-saucer-pdf</artifactId>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark</artifactId>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-gfm-tables</artifactId>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-yaml-front-matter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-gfm-strikethrough</artifactId>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark-ext-autolink</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
        </dependency>

        <!-- JJWT Runtime -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- ShedLock JDBC -->
        <dependency>
            <groupId>net.javacrumbs.shedlock</groupId>
            <artifactId>shedlock-provider-jdbc-template</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: 精简 mateclaw-server/pom.xml**

替换 server 模块的 pom.xml，只保留表现层和启动所需的依赖：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mateclaw</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mateclaw-server</artifactId>
    <packaging>jar</packaging>
    <name>MateClaw Server</name>
    <description>HTTP controllers, web config, Spring Boot entry point</description>

    <dependencies>
        <!-- Internal -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mateclaw-domain</artifactId>
        </dependency>
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mateclaw-infrastructure</artifactId>
        </dependency>

        <!-- Spring Boot Web + Security -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- JJWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- SpringDoc -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>

        <!-- Hutool -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Database (runtime only, for H2 console / direct JDBC) -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway (schema migration at startup) -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <id>resolve-test-classpath-properties</id>
                        <goals>
                            <goal>properties</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>-javaagent:${net.bytebuddy:byte-buddy-agent:jar}</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>media-gen</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <groups>media-gen</groups>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 6: 创建目录骨架**

每个新模块需要的基础目录：

```bash
# mateclaw-common
mkdir -p mateclaw-dev/mateclaw-common/src/main/java/vip/mate/common
mkdir -p mateclaw-dev/mateclaw-common/src/test/java

# mateclaw-domain
mkdir -p mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain
mkdir -p mateclaw-dev/mateclaw-domain/src/test/java

# mateclaw-infrastructure
mkdir -p mateclaw-dev/mateclaw-infrastructure/src/main/java/vip/mate/infra
mkdir -p mateclaw-dev/mateclaw-infrastructure/src/main/resources/adapters
mkdir -p mateclaw-dev/mateclaw-infrastructure/src/test/java

# server (keep existing src/main/resources, move Java source)
mkdir -p mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server
```

- [ ] **Step 7: 验证模块骨架编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn compile -pl mateclaw-common
```

预期: BUILD SUCCESS（common 编译通过，即使还没移入任何代码）

- [ ] **Step 8: 提交**

```bash
git -C D:/code/Loom add mateclaw-dev/mateclaw-common mateclaw-dev/mateclaw-domain mateclaw-dev/mateclaw-infrastructure mateclaw-dev/mateclaw-server/pom.xml mateclaw-dev/pom.xml
git -C D:/code/Loom commit -m "feat: create four-module skeleton with pom.xml and dependency declarations"
```

---

### Task 2: 迁移 mateclaw-common 文件

**Files:**
- Move: `vip/mate/common/` → `mateclaw-common/src/main/java/vip/mate/common/`
- Move: `vip/mate/exception/` → `mateclaw-common/src/main/java/vip/mate/common/exception/`
- Move: `vip/mate/i18n/` → `mateclaw-common/src/main/java/vip/mate/common/i18n/`

- [ ] **Step 1: 移动 common 包文件**

```bash
# 移动 common/result/
cp -r "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/common" \
      "D:/code/Loom/mateclaw-dev/mateclaw-common/src/main/java/vip/mate/common"
```

- [ ] **Step 2: 移动 exception/ 包（重命名为 vip.mate.common.exception）**

创建目标目录并移动：
```bash
mkdir -p "D:/code/Loom/mateclaw-dev/mateclaw-common/src/main/java/vip/mate/common/exception"
cp "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/exception/"*.java \
   "D:/code/Loom/mateclaw-dev/mateclaw-common/src/main/java/vip/mate/common/exception/"
```

- [ ] **Step 3: 移动 i18n/ 包（重命名为 vip.mate.common.i18n）**

```bash
mkdir -p "D:/code/Loom/mateclaw-dev/mateclaw-common/src/main/java/vip/mate/common/i18n"
cp "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/i18n/"*.java \
   "D:/code/Loom/mateclaw-dev/mateclaw-common/src/main/java/vip/mate/common/i18n/"
```

- [ ] **Step 4: 更新已移动文件的 package 声明**

对 mateclaw-common 中的每个 Java 文件：
- `vip/mate/exception/MateClawException.java`: `package vip.mate.exception` → `package vip.mate.common.exception`
- `vip/mate/exception/GlobalExceptionHandler.java`: 同上
- `vip/mate/i18n/I18nAutoConfig.java`: `package vip.mate.i18n` → `package vip.mate.common.i18n`
- `vip/mate/i18n/I18nService.java`: 同上
- `vip/mate/i18n/LocaleAwareToolCallback.java`: 同上
- `vip/mate/common/result/*.java`: 包路径不变

同时更新文件内所有 import 引用（如原代码中 import `vip.mate.exception.MateClawException` 的地方，后续在 Task 3-5 中会统一处理）。

- [ ] **Step 5: 验证 common 模块编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn compile -pl mateclaw-common -am
```

预期: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git -C D:/code/Loom add mateclaw-dev/mateclaw-common
git -C D:/code/Loom commit -m "refactor: move common, exception, i18n into mateclaw-common"
```

---

### Task 3: 迁移 mateclaw-domain 文件

**Files:** 约 500+ Java 文件，包括所有 Entity/VO/Service/Mapper/Event/业务逻辑类

- [ ] **Step 1: 编写 Python 迁移脚本**

由于文件量巨大，编写 `migrate_domain.py` 自动化脚本：

```python
"""
将 mateclaw-server 中的 domain 层文件移动到 mateclaw-domain 模块。
包路径变更: vip.mate.<domain>.<rest> → vip.mate.domain.<domain>.<rest>

移动规则:
- 所有 model/ 下的文件
- 所有 service/ 下的文件
- 所有 repository/ 下的文件
- 所有 event/ 下的文件（领域事件）
- 没有 controller 子包的业务根文件
- agent/graph/ 全部

排除规则:
- controller/ 目录（属于 server 层）
- 渠道适配器具体实现（属于 infrastructure 层）
- LLM 客户端实现（属于 infrastructure 层）
- 配置类（根据具体类型分到不同模块）
"""

import os
import re
import shutil
from pathlib import Path

SERVER_SRC = Path("D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate")
DOMAIN_SRC = Path("D:/code/Loom/mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain")

# 需要在 domain 层保留的包
DOMAIN_PACKAGES = {
    "acp", "agent", "approval", "artifact", "audit", "auth",
    "dashboard", "group", "message", "notification",
    "orchestrator", "planning", "system", "task",
    "workspace",
}

# 需要特殊处理的包（部分文件到 domain，部分到 infrastructure）
SPLIT_PACKAGES = {
    "channel": {
        "to_domain": [
            "ChannelAdapter.java", "StreamingChannelAdapter.java",
            "AbstractChannelAdapter.java", "ChannelMessage.java",
            "ChannelManager.java", "ChannelMessageRouter.java",
            "ChannelHealthMonitor.java", "ChannelErrorClassifier.java",
            "ChannelSessionStore.java", "ChannelChatOriginFactory.java",
            "ChannelMessageRenderer.java", "SendContext.java",
            "DeliveryOptions.java", "ExponentialBackoff.java",
            "MediaPathGuard.java",
            "model/", "event/", "service/", "repository/",
            "tool/ChannelToolCallback.java", "tool/ChannelToolContext.java",
            "tool/ChannelToolDescriptor.java", "tool/ChannelToolProvider.java",
            "tool/ChannelToolService.java",
            "notification/",
        ],
    },
    "llm": {
        "to_domain": [
            "model/", "event/", "service/", "repository/",
            "controller/",  # controller goes to server
            "routing/",      # routing logic stays in domain
            "cache/",        # cache strategy is domain logic
        ],
    },
    "tool": {
        "to_domain": [
            "model/", "service/", "repository/",
            "ConcurrencyUnsafe.java", "ToolConcurrencyRegistry.java",
            "ToolRegistry.java",
            "guard/model/", "guard/service/", "guard/repository/",
            "guard/DangerousPattern.java", "guard/DefaultToolGuard.java",
            "guard/ToolGuard.java", "guard/ToolGuardEngineAdapter.java",
            "guard/ToolGuardResult.java", "guard/WorkspacePathGuard.java",
            "guard/ToolExecutionGuardHelper.java",
            "image/ImageCapability.java", "image/ImageGenerationProvider.java",
            "image/ImageGenerationRequest.java", "image/ImageGenerationResult.java",
            "image/ImageGenerationService.java", "image/ImageModelSpec.java",
            "image/ImageProviderCapabilities.java", "image/ImageProviderRegistry.java",
            "image/ImageReference.java", "image/ImageReferenceLoader.java",
            "image/PayloadBuilder.java", "image/SizeStyle.java",
            "image/ImageFileDownloader.java", "image/ImageSubmitResult.java",
            "image/vision/",
            "video/VideoCapability.java", "video/VideoGenerationProvider.java",
            "video/VideoGenerationRequest.java", "video/VideoGenerationResult.java",
            "video/VideoGenerationService.java", "video/VideoProviderCapabilities.java",
            "video/VideoProviderRegistry.java", "video/VideoFileDownloader.java",
            "video/VideoSubmitResult.java",
            "music/MusicGenerationProvider.java", "music/MusicGenerationRequest.java",
            "music/MusicGenerationResult.java", "music/MusicGenerationService.java",
            "music/MusicProviderRegistry.java",
            "model3d/Model3dCapability.java", "model3d/Model3dGenerationProvider.java",
            "model3d/Model3dGenerationRequest.java", "model3d/Model3dGenerationResult.java",
            "model3d/Model3dGenerationService.java", "model3d/Model3dProviderCapabilities.java",
            "model3d/Model3dProviderRegistry.java", "model3d/Model3dFileDownloader.java",
            "model3d/Model3dSubmitResult.java",
            "document/", "builtin/",
        ],
    },
    "skill": {
        "to_domain": [
            "model/", "service/", "repository/", "event/",
            "manifest/", "runtime/", "lessons/", "lifecycle/",
            "template/", "usage/", "workspace/", "secret/",
            "knowledge/", "synthesis/",
        ],
    },
    "memory": {
        "to_domain": [
            "model/", "service/", "repository/", "event/",
            "lifecycle/", "listener/", "provider/", "spi/",
            "fact/", "nudge/", "archive/", "tool/",
        ],
    },
    "wiki": {
        "to_domain": [
            "model/", "service/", "repository/", "event/",
            "dto/", "relation/", "retrieval/", "metrics/",
            "job/", "sse/", "hotcache/", "tool/",
        ],
    },
}

# Config files by module
CONFIG_ASSIGNMENT = {
    "ConversationWindowProperties.java": "domain",
    "GraphObservationProperties.java": "domain",
    "ToolTimeoutProperties.java": "domain",
    "MybatisPlusConfig.java": "infrastructure",
    "ShedLockConfig.java": "infrastructure",
    "FlywayRepairConfig.java": "infrastructure",
    "DatabaseBootstrapRunner.java": "infrastructure",
    # rest go to server
}

def move_file(src: Path, dst: Path):
    """Move file and update its package declaration and imports."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    content = src.read_text(encoding="utf-8")

    # Calculate old and new package names
    rel = src.relative_to(SERVER_SRC)
    old_pkg = "vip.mate." + str(rel.parent).replace("/", ".").replace("\\", ".")

    dst_rel = dst.relative_to(DOMAIN_SRC)
    new_pkg = "vip.mate.domain." + str(dst_rel.parent).replace("/", ".").replace("\\", ".")

    # Update package declaration
    content = re.sub(
        rf'^package\s+{re.escape(old_pkg)}\s*;',
        f'package {new_pkg};',
        content, flags=re.MULTILINE
    )

    # Update imports for moved domain packages
    # vip.mate.<domain>.* → vip.mate.domain.<domain>.*
    for domain_pkg in DOMAIN_PACKAGES | {"channel", "llm", "tool", "skill", "memory", "wiki"}:
        content = content.replace(
            f"import vip.mate.{domain_pkg}.",
            f"import vip.mate.domain.{domain_pkg}."
        )

    dst.write_text(content, encoding="utf-8")
    src.unlink()
    print(f"  Moved: {rel} → {dst_rel}")

def main():
    # ... walk SERVER_SRC and apply rules
    pass
```

- [ ] **Step 2: 运行迁移脚本**

```bash
cd D:/code/Loom
python migrate_domain.py
```

- [ ] **Step 3: 验证编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn compile -pl mateclaw-domain -am 2>&1 | head -100
```

- [ ] **Step 4: 根据编译错误修复遗漏的 import**

检查编译错误中是否有对原包路径的引用需要更新：

```bash
# 查看有多少个文件引用了旧包路径
grep -r "import vip\.mate\.exception\." mateclaw-dev/mateclaw-domain/src/ | wc -l
grep -r "import vip\.mate\.common\." mateclaw-dev/mateclaw-domain/src/ | head -20
```

对 domain 模块中所有引用 `vip.mate.exception` 的 import，更新为 `vip.mate.common.exception`。对 `vip.mate.i18n` 的引用，更新为 `vip.mate.common.i18n`。

```bash
# 批量更新 domain 模块中的 import
find mateclaw-dev/mateclaw-domain/src -name "*.java" -exec sed -i \
  -e 's/import vip\.mate\.exception\./import vip.mate.common.exception./g' \
  -e 's/import vip\.mate\.i18n\./import vip.mate.common.i18n./g' \
  {} +
```

- [ ] **Step 5: 再次验证编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn compile -pl mateclaw-domain -am 2>&1 | tail -20
```

- [ ] **Step 6: 提交**

```bash
git -C D:/code/Loom add mateclaw-dev/mateclaw-domain mateclaw-dev/mateclaw-server/src
git -C D:/code/Loom commit -m "refactor: move domain layer files to mateclaw-domain"
```

---

### Task 4: 迁移 mateclaw-infrastructure 文件

**Files:** 约 250+ Java 文件，包括渠道适配器、LLM 客户端、搜索 Provider、图片/视频 Provider、MCP 运行时、基础设施配置

- [ ] **Step 1: 编写并运行 infrastructure 迁移脚本**

类似 Task 3 的脚本逻辑，将剩余文件从 server 移入 infrastructure：

```python
"""
移动规则:
- channel/ (排除已在 domain 的接口文件)
- llm/ (排除已在 domain 的 model/event/service/repository/routing/cache/controller)
- tool/search/, tool/image/provider/, tool/video/provider/, tool/music/provider/, tool/model3d/provider/
- tool/mcp/runtime/, tool/browser/
- acp/client/, agent/cli/, agent/bridge/
- skill/acp/, skill/installer/, skill/mcp/
- config/ 中的基础设施配置
"""
```

- [ ] **Step 2: 更新 infrastructure 模块中文件的 package 和 import**

所有移到 infrastructure 的文件，包名从 `vip.mate.<domain>` 变为 `vip.mate.infra.<domain>`。

对 infrastructure 模块中的文件更新 import：
```bash
find mateclaw-dev/mateclaw-infrastructure/src -name "*.java" -exec sed -i \
  -e 's/import vip\.mate\.exception\./import vip.mate.common.exception./g' \
  -e 's/import vip\.mate\.i18n\./import vip.mate.common.i18n./g' \
  -e 's/import vip\.mate\.agent\./import vip.mate.domain.agent./g' \
  -e 's/import vip\.mate\.channel\./import vip.mate.domain.channel./g' \
  -e 's/import vip\.mate\.llm\./import vip.mate.domain.llm./g' \
  -e 's/import vip\.mate\.tool\./import vip.mate.domain.tool./g' \
  -e 's/import vip\.mate\.skill\./import vip.mate.domain.skill./g' \
  -e 's/import vip\.mate\.memory\./import vip.mate.domain.memory./g' \
  -e 's/import vip\.mate\.wiki\./import vip.mate.domain.wiki./g' \
  -e 's/import vip\.mate\.workspace\./import vip.mate.domain.workspace./g' \
  {} +
```

- [ ] **Step 3: 移动 CLI 适配脚本**

```bash
cp "D:/code/Loom/mateclaw-dev/adapters/"*.mjs \
   "D:/code/Loom/mateclaw-dev/mateclaw-infrastructure/src/main/resources/adapters/"
```

- [ ] **Step 4: 移动数据库迁移文件**

```bash
cp -r "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db" \
      "D:/code/Loom/mateclaw-dev/mateclaw-infrastructure/src/main/resources/db"
```

- [ ] **Step 5: 验证编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn compile -pl mateclaw-infrastructure -am 2>&1 | tail -30
```

- [ ] **Step 6: 提交**

```bash
git -C D:/code/Loom add mateclaw-dev/mateclaw-infrastructure mateclaw-dev/mateclaw-server/src
git -C D:/code/Loom commit -m "refactor: move infrastructure layer files to mateclaw-infrastructure"
```

---

### Task 5: 收尾 mateclaw-server

**Files:** 约 50+ Controller 文件 + 配置文件 + 启动类

- [ ] **Step 1: 移动 MateClawApplication.java**

MateClawApplication.java 在 `vip/mate/` 根目录，移到 server 模块的 `vip/mate/server/`：

```bash
mkdir -p "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server"
mv "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/MateClawApplication.java" \
   "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/MateClawApplication.java"
```

更新 package 声明：`package vip.mate` → `package vip.mate.server`

- [ ] **Step 2: 更新 server 模块中剩余文件的 package 声明**

server 模块中剩余的文件（Controller、Config）需要更新包名：
- `vip.mate.xxx.controller` → `vip.mate.server.xxx`
- `vip.mate.config.*` → `vip.mate.server.config.*`

批量更新：
```bash
# 更新 package 声明和 import
find mateclaw-dev/mateclaw-server/src/main/java -name "*.java" -exec sed -i \
  -e 's/package vip\.mate\.\(.*\)\.controller/package vip.mate.server.\1/' \
  -e 's/import vip\.mate\.\(.*\)\.controller\./import vip.mate.server.\1./' \
  -e 's/package vip\.mate\.config/package vip.mate.server.config/' \
  -e 's/import vip\.mate\.config\./import vip.mate.server.config./' \
  -e 's/import vip\.mate\.exception\./import vip.mate.common.exception./g' \
  -e 's/import vip\.mate\.i18n\./import vip.mate.common.i18n./g' \
  -e 's/import vip\.mate\.common\.result\./import vip.mate.common.result./g' \
  {} +
```

- [ ] **Step 3: 去掉 Controller 中对 Mapper 的直接注入**

查找 server 模块中 Controller 文件直接注入 Mapper 的情况并改为注入 Service：

检查所有 Controller 的 import 和字段声明：
```bash
grep -r "import.*\.repository\." mateclaw-dev/mateclaw-server/src/main/java/ || echo "No Mapper refs found"
grep -r "Mapper" mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/ | grep -v "import" | grep "private final"
```

如果发现违规注入，将 `private final XxxMapper` 替换为对应的 `private final XxxService`。

- [ ] **Step 5: 更新 MateClawApplication 的 @MapperScan**

原 `@MapperScan("vip.mate.**.repository")` 扫描的是原包结构。修改为新包结构，确保能扫到 infrastructure 模块中的 Mapper：

```java
@MapperScan({"vip.mate.domain.**.repository", "vip.mate.infra.**.repository"})
```

- [ ] **Step 6: 更新 MateClawApplication 的 @SpringBootApplication scanBasePackages**

显式指定扫描包路径，确保 Spring 能发现四个模块中的 Bean：

```java
@SpringBootApplication(scanBasePackages = {
    "vip.mate.common",
    "vip.mate.domain",
    "vip.mate.infra",
    "vip.mate.server"
})
```

- [ ] **Step 7: 更新 application.yml 中的配置引用**

检查 `application.yml` 中是否有对类路径的引用需要更新。

- [ ] **Step 8: 验证全量编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn compile -pl mateclaw-server -am 2>&1 | tail -50
```

预期: 全模块编译通过，或仅有少量非业务逻辑相关的编译错误需要修复。

- [ ] **Step 9: 提交**

```bash
git -C D:/code/Loom add mateclaw-dev/mateclaw-server
git -C D:/code/Loom commit -m "refactor: finalize mateclaw-server with controllers and configs"
```

---

### Task 6: 迁移测试文件

**Files:** 约 297 个测试 Java 文件

- [ ] **Step 1: 按被测类所在模块分配测试文件**

测试文件与被测类放在同一个模块中。测试文件按以下规则分配：
- 被测类在 common → 测试在 mateclaw-common/src/test/
- 被测类在 domain → 测试在 mateclaw-domain/src/test/
- 被测类在 infrastructure → 测试在 mateclaw-infrastructure/src/test/
- 被测类在 server → 测试在 mateclaw-server/src/test/

```bash
# 将测试文件移动到对应模块
# 根据被测类所在包的包名判断目标模块
```

- [ ] **Step 2: 更新测试文件中的 import**

```bash
find mateclaw-dev/mateclaw-*/src/test -name "*.java" -exec sed -i \
  -e 's/import vip\.mate\.exception\./import vip.mate.common.exception./g' \
  -e 's/import vip\.mate\.i18n\./import vip.mate.common.i18n./g' \
  -e 's/import vip\.mate\.\([a-z]*\)\.controller\./import vip.mate.server.\1./' \
  {} +
```

- [ ] **Step 3: 运行测试**

```bash
cd D:/code/Loom/mateclaw-dev
mvn test 2>&1 | tail -50
```

- [ ] **Step 4: 提交**

```bash
git -C D:/code/Loom add mateclaw-dev/*/src/test
git -C D:/code/Loom commit -m "test: redistribute test files across four modules"
```

---

### Task 7: 全量验证

- [ ] **Step 1: 全量编译**

```bash
cd D:/code/Loom/mateclaw-dev
mvn clean compile 2>&1 | tail -30
```

预期: BUILD SUCCESS

- [ ] **Step 2: 运行全部测试**

```bash
mvn test 2>&1 | tail -30
```

- [ ] **Step 3: 启动应用验证**

```bash
cd mateclaw-server
mvn spring-boot:run
```

验证:
- 应用成功启动，无 Bean 注入错误
- `curl http://localhost:18088/api/v1/system/health` 返回正常

- [ ] **Step 4: 验证 SSE 流式推送**

启动前端开发服务器并测试 SSE 聊天功能。

- [ ] **Step 5: 提交最终版本**

```bash
git -C D:/code/Loom add -A
git -C D:/code/Loom commit -m "refactor: complete four-module layered architecture migration"
```
