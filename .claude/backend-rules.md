# Loom 后端开发规范

> 基于 Spring Boot 3.5 + MyBatis Plus 3.5 的多 Agent 协同平台后端规范。
> 通用原则见根目录 CLAUDE.md，本文件聚焦后端技术规范。

---

## 1. 分层架构（FATAL）

**Controller → Service → Mapper，不可跨层。**

### 1.1 Controller 层
- 只做请求解析、参数校验、调用 Service、封装响应
- **绝不**包含业务逻辑、数据库访问、事务注解
- 统一返回 `R<T>`，异常由全局 `RHttpStatusAdvice` 处理，**不要在 Controller 里写 try-catch**

```java
// ✅ 正确
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agentService;

    @GetMapping("/{id}")
    public R<AgentEntity> getAgent(@PathVariable Long id) {
        return R.ok(agentService.getById(id));
    }
}

// ❌ 错误：Controller 里写业务逻辑 + try-catch
@GetMapping("/{id}")
public ResponseEntity<?> getAgent(@PathVariable Long id) {
    try {
        AgentEntity agent = agentMapper.selectById(id);  // 跨层调 Mapper
        if (agent == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(agent);
    } catch (Exception e) {
        return ResponseEntity.internalServerError().build();
    }
}
```

### 1.2 Service 层
- 所有业务逻辑在 Service 中
- 写操作必须加 `@Transactional`
- 通过注入 Mapper 访问数据库
- 抛出 `MateClawException` 而非通用 RuntimeException

### 1.3 Mapper 层
- 必须在 `vip.mate.domain.**.repository` 包下（`@MapperScan` 扫描路径）
- 继承 MyBatis Plus 的 `BaseMapper<T>`
- 复杂查询用 `@Select` / XML 或 LambdaQueryWrapper

---

## 2. 依赖注入

- **始终使用构造器注入**，配合 Lombok `@RequiredArgsConstructor`
- **禁止**字段注入（`@Autowired` on fields）
- **禁止**在 Controller 里直接注入 Mapper

```java
// ✅ 正确
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {
    private final AgentMapper agentMapper;
}

// ❌ 错误
@Autowired
private AgentMapper agentMapper;
```

---

## 3. API 设计规范

### 3.1 路径
- 基路径：`/api/v1/`
- 资源命名用复数名词，路径中不要动词
- ✅ `/api/v1/agents/{id}`  ❌ `/api/v1/getAgent`

### 3.2 HTTP 方法
- `GET` — 查询
- `POST` — 创建
- `PUT` / `PATCH` — 更新
- `DELETE` — 删除

### 3.3 分页
统一使用 MyBatis Plus 的 `IPage<T>`，传入 `page` 和 `size` 参数。

### 3.4 响应格式
```json
{ "code": 200, "message": "success", "data": {} }
```

---

## 4. 数据库 & 事务

### 4.1 事务边界
- `@Transactional` 加在 Service 层方法上，**不要加在 Controller 上**
- 多步写操作用 `@Transactional` 保证原子性
- 只读操作标记 `@Transactional(readOnly = true)` 以优化性能

### 4.2 N+1 问题
- MyBatis Plus 的关联查询使用 `<association>` / `<collection>` 的 `select` 属性时要警惕 N+1
- 批量场景优先用 `selectBatchIds` 或 `in` 查询替代逐条查询
- **Loop 内调用远程服务务必做批量化或并发控制**（多 Agent 场景高频出现）

### 4.3 迁移
- Flyway 迁移脚本放在 `mateclaw-server/src/main/resources/db/migration/h2/`
- 命名：`V{序号}__{描述}.sql`
- H2 兼容 MySQL 模式（`MODE=MySQL`）
- 禁用 Flyway `${}` 占位符（项目已全局禁用）

---

## 5. 多 Agent 协同平台专项规范

### 5.1 SSE 流式推送
- SSE 通道务必处理 `lastEventId` 实现断线重连
- 长时间无数据时发送心跳 comment（`: heartbeat\n\n`）防止代理超时断开
- SSE emitter 超时时间要大于 LLM 调用最长响应时间
- 使用 `Utf8SseEmitter`（项目封装）处理中文

### 5.2 异步 & 并发
- 虚拟线程已启用，IO 密集操作天然适合虚拟线程
- Agent 编排任务用 `ExecutorService` 提交，不阻塞主线程
- 共享状态访问注意线程安全：优先用不可变对象 + 消息传递，而非加锁
- Orchestrator 分配 Agent 任务时考虑幂等性，避免重复分派

### 5.3 Agent 状态机
- Agent 状态枚举统一管理（`AgentState`）
- 状态转换在 Service 层显式处理，不要散落在各处
- 终态（COMPLETED / FAILED / CANCELLED）原则上不可逆转

### 5.4 消息顺序
- 对话消息的时间戳使用数据库时间（`NOW()`），不依赖应用服务器时钟
- 同一 conversation 内的消息严格按创建时间排序

### 5.5 工作空间隔离
- 所有资源访问通过 workspaceId 隔离
- Service 层使用 `@RequireWorkspaceRole` 注解做权限校验

---

## 6. 安全规范

### 6.1 认证 & 授权
- 所有 API 端点默认需要认证（Spring Security 已配置）
- 使用 JWT + PAT 双令牌机制
- PAT 的 secret 只存储哈希值（bcrypt），明文仅在创建时返回一次

### 6.2 输入校验
- Controller 入参使用 Jakarta Validation（`@NotNull`、`@NotBlank`、`@Size` 等）
- 不要信任客户端传来的 ID 所属关系，Service 层校验资源归属

### 6.3 密钥管理
- 密钥、Token、数据库密码一律走环境变量或配置中心
- **禁止**在代码、注释、日志中硬编码任何凭证
- `.env`、含密钥的配置文件加入 `.gitignore`

### 6.4 SQL 注入防护
- MyBatis Plus 的方法已参数化，直接使用是安全的
- 手写 SQL（`@Select` / XML）务必使用 `#{}`（参数化），**禁止 `${}` 拼接用户输入**

---

## 7. 异常处理

- 业务异常统一抛 `MateClawException`
- 全局异常由 `RHttpStatusAdvice` 处理，自动转为 `R` 格式
- 异常消息对用户友好，不要在 message 中暴露堆栈或数据库信息
- 区分业务异常和系统异常：业务异常可展示给用户，系统异常记录日志后返回通用错误

---

## 8. 日志

- 使用 Lombok `@Slf4j`
- 关键业务节点记录 info 日志（Agent 状态变更、Orchestrator 任务分派）
- 异常记录 error 日志，包含足够上下文（userId、workspaceId、操作类型）
- **禁止**日志中打印 Token、密钥、完整用户输入（敏感内容截断）

---

## 9. 测试

- 业务逻辑（Service 层）必须写单元测试
- 测试类放在对应模块的 `src/test/java` 下，包路径与源码一致
- 使用真实 H2 数据库测试，不 mock Mapper
- 命名：`{被测类名}{场景}Test`
