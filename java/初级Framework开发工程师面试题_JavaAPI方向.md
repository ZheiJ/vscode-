# 初级 Framework 开发工程师面试题（Java API 方向）

> 整理时间：2026-06-13
> 面向：初级 Java Framework 开发 / API 开发方向

---

## 目录

1. [Java 核心 API](#1-java-核心-api)
2. [Spring 框架 API](#2-spring-框架-api)
3. [Spring Boot 自动配置与 Starter](#3-spring-boot-自动配置与-starter)
4. [RESTful API 设计](#4-restful-api-设计)
5. [ORM 框架 API（MyBatis / JPA）](#5-orm-框架-apimybatis--jpa)
6. [数据库与缓存 API](#6-数据库与缓存-api)
7. [JSON / 序列化 API](#7-json--序列化-api)
8. [工具类库 API](#8-工具类库-api)
9. [设计模式](#9-设计模式)
10. [实战场景题](#10-实战场景题)

---

## 1. Java 核心 API

### 1.1 集合框架（Collection Framework）

**Q: HashMap 的底层实现原理？**

- JDK 7：数组 + 链表（头插法）
- JDK 8+：数组 + 链表 + 红黑树（尾插法）
- 默认初始容量 16，负载因子 0.75
- 链表长度 ≥ 8 且数组长度 ≥ 64 时转为红黑树
- key 可为 null（放在 table[0]）

**Q: ConcurrentHashMap 如何保证线程安全？**

- JDK 7：Segment 分段锁（继承 ReentrantLock）
- JDK 8+：CAS + synchronized + Node 数组
- put 时先 CAS 尝试，失败则 synchronized 锁头节点
- 扩容时多线程协同（transfer）

**Q: ArrayList vs LinkedList 区别？**

| 维度 | ArrayList | LinkedList |
|:---|---:|---:|
| 底层结构 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 尾部插入 | O(1) 均摊 | O(1) |
| 中间插入 | O(n) 移动元素 | O(1) 修改指针 |
| 内存占用 | 连续空间 | 节点额外开销 |

**Q: List 遍历时删除元素会怎样？**

- for-each 遍历中 remove → `ConcurrentModificationException`
- 正确做法：`Iterator.remove()` 或 `for` 倒序遍历

### 1.2 多线程与并发 API

**Q: 创建线程的几种方式？**

1. 继承 `Thread` 重写 `run()`
2. 实现 `Runnable` 接口
3. 实现 `Callable` + `FutureTask`
4. 线程池 `ExecutorService`

**Q: `synchronized` 和 `ReentrantLock` 的区别？**

| synchronized | ReentrantLock |
|:---|---:|
| 关键字，JVM 层面 | API 层面，需手动 lock/unlock |
| 自动释放锁 | 需 finally 中 unlock |
| 不可中断 | 可中断 lockInterruptibly |
| 非公平锁 | 公平 / 非公平可选 |
| 无法超时 | tryLock(timeout) |

**Q: 线程池的核心参数（ThreadPoolExecutor）？**

```
corePoolSize       — 核心线程数
maximumPoolSize    — 最大线程数
keepAliveTime      — 空闲线程存活时间
workQueue          — 阻塞队列（ArrayBlockingQueue / LinkedBlockingQueue / SynchronousQueue）
threadFactory      — 线程工厂
rejectedHandler    — 拒绝策略（Abort / Discard / DiscardOldest / CallerRuns）
```

**Q: volatile 关键字的作用？**

- 保证可见性：每次读取都从主存读取
- 禁止指令重排序（内存屏障）
- 不保证原子性

### 1.3 I/O API

**Q: BIO / NIO / AIO 区别？**

| | BIO | NIO | AIO |
|:---|---|---|---|
| 模型 | 同步阻塞 | 同步非阻塞 | 异步非阻塞 |
| 核心类 | InputStream/OutputStream | Channel/Selector/Buffer | CompletionHandler |
| 线程模型 | 一连接一线程 | 多路复用（一个 Selector 管理多 Channel） | 回调通知 |
| 适用 | 连接少、并发低 | 高并发连接数多 | 连接数极大 |

**Q: Java NIO 的核心组件？**

- **Channel**：FileChannel、SocketChannel、ServerSocketChannel
- **Buffer**：ByteBuffer、CharBuffer、IntBuffer 等
- **Selector**：单线程管理多 Channel 的事件（OP_ACCEPT / OP_READ / OP_WRITE）

### 1.4 反射 API（Reflection）

**Q: 获取 Class 对象的三种方式？**

```java
Class<?> clazz1 = Class.forName("com.example.User");
Class<?> clazz2 = User.class;
Class<?> clazz3 = userInstance.getClass();
```

**Q: 反射的常见用途？**

- Spring IoC 依赖注入
- 动态代理（JDK Proxy）
- ORM 框架（MyBatis 结果映射）
- 注解处理（@RequestMapping、@Autowired）

### 1.5 泛型（Generics）

**Q: 什么是泛型擦除？**

- Java 泛型在编译后擦除为原始类型（`List<String>` → `List`）
- 运行时无法获取泛型类型参数
- 可通过 `ParameterizedType` 获取（如 Spring `ResolvableType`）

**Q: ? extends T 和 ? super T 区别（PECS 原则）？**

- `? extends T`：生产者（Producer），只能 get 不能 add
- `? super T`：消费者（Consumer），只能 add 不能 get
- `PECS` = Producer Extends, Consumer Super

---

## 2. Spring 框架 API

### 2.1 Spring IoC 容器

**Q: 什么是 IoC 和 DI？**

- **IoC**（控制反转）：对象的创建权从程序本身反转给容器
- **DI**（依赖注入）：容器在运行时将依赖注入到对象中
- **注入方式**：构造器注入、Setter 注入、字段注入（@Autowired）

**Q: Bean 的生命周期？**

```
实例化 → 属性赋值 → Aware 接口回调 → BeanPostProcessor#postProcessBeforeInitialization 
→ @PostConstruct / InitializingBean → BeanPostProcessor#postProcessAfterInitialization 
→ 使用中 → @PreDestroy / DisposableBean → 销毁
```

**Q: Bean 的作用域（Scope）？**

| Scope | 说明 |
|:---|---:|
| singleton | 单例（默认），整个容器共享一个实例 |
| prototype | 每次获取都创建新实例 |
| request | 每次 HTTP 请求创建一个 |
| session | 每个 HTTP Session 创建一个 |
| application | ServletContext 级别 |

**Q: @Component、@Service、@Controller、@Repository 的区别？**

- 功能上等价，都是 `@Component` 的派生注解
- 语义上区分：Service 层、Controller 层、DAO 层
- `@Repository` 额外支持持久化异常转换

### 2.2 Spring AOP API

**Q: Spring AOP 的实现原理？**

- **JDK 动态代理**：基于接口，目标类实现接口时使用
- **CGLIB 代理**：基于子类，目标类未实现接口时使用
- Spring Boot 2.x+ 默认使用 CGLIB（proxy-target-class=true）

**Q: 常用通知类型（Advice）？**

| 注解 | 说明 |
|:---|---:|
| @Before | 方法执行前 |
| @AfterReturning | 方法正常返回后 |
| @AfterThrowing | 方法抛出异常后 |
| @After | 方法结束后（finally） |
| @Around | 环绕通知，最强大 |

**Q: 切点表达式（Pointcut）示例？**

```java
@Pointcut("execution(* com.example.service.*.*(..))")
@Pointcut("@annotation(org.springframework.transaction.annotation.Transactional)")
```

### 2.3 Spring MVC API

**Q: 从请求到响应的完整流程？**

```
请求 → DispatcherServlet → HandlerMapping → HandlerAdapter → 
拦截器 preHandle → Controller 处理 → 拦截器 postHandle → 
ViewResolver 解析视图 → 拦截器 afterCompletion → 响应
```

**Q: @RequestMapping 相关注解？**

- `@RequestMapping`：通用映射
- `@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`：HTTP 方法限定
- `@PathVariable`：路径变量
- `@RequestParam`：查询参数
- `@RequestBody`：请求体 JSON 绑定
- `@ResponseBody`：返回 JSON（消息转换器）

**Q: @ExceptionHandler 全局异常处理？**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public Result<?> handle(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }
}
```

### 2.4 事务管理 API

**Q: @Transactional 的原理？**

- 基于 AOP，通过代理拦截方法调用
- 默认只对 RuntimeException 回滚，checked exception 不回滚
- 可配置 `rollbackFor`、`propagation`、`isolation`、`timeout`

**Q: 事务传播行为（Propagation）？**

| 传播行为 | 说明 |
|:---|---:|
| REQUIRED（默认） | 有则加入，无则新建 |
| REQUIRES_NEW | 挂起当前事务，新建一个 |
| NESTED | 嵌套事务（Savepoint） |
| SUPPORTS | 有则加入，无则不开启 |
| MANDATORY | 必须有事务否则抛异常 |
| NOT_SUPPORTED | 挂起当前事务，不开启事务 |
| NEVER | 有事务则抛异常 |

---

## 3. Spring Boot 自动配置与 Starter

**Q: Spring Boot 自动配置原理？**

- `@SpringBootApplication` 包含 `@EnableAutoConfiguration`
- 通过 `AutoConfigurationImportSelector` 加载 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 配合 `@ConditionalOnClass`、`@ConditionalOnMissingBean` 等条件注解按需装配

**Q: 常用的条件注解？**

| 注解 | 说明 |
|:---|---:|
| @ConditionalOnClass | 类存在时加载 |
| @ConditionalOnMissingBean | Bean 不存在时加载 |
| @ConditionalOnProperty | 配置项存在时加载 |
| @ConditionalOnExpression | SpEL 表达式为 true 时加载 |

**Q: 如何自定义一个 Starter？**

1. 创建自动配置类，使用 `@Configuration` + 条件注解
2. 定义 `XXXProperties` 类（`@ConfigurationProperties`）
3. `spring.factories` 或 `AutoConfiguration.imports` 注册
4. 可选：`spring-autoconfigure-metadata.properties` 增加过滤优化

---

## 4. RESTful API 设计

**Q: RESTful 设计规范有哪些？**

```
GET    /users          — 查询用户列表
GET    /users/{id}     — 查询单个用户
POST   /users          — 创建用户
PUT    /users/{id}     — 全量更新
PATCH  /users/{id}     — 部分更新
DELETE /users/{id}     — 删除用户
```

**Q: 如何做 API 版本控制？**

1. URL 路径：`/api/v1/users`
2. 请求头：`Accept: application/vnd.company.v1+json`
3. 请求参数：`/api/users?version=1`

**Q: 状态码使用规范？**

| 状态码 | 含义 |
|:---|---:|
| 200 | OK |
| 201 | Created（POST 创建成功） |
| 204 | No Content（DELETE 成功） |
| 400 | Bad Request（参数校验失败） |
| 401 | Unauthorized（未认证） |
| 403 | Forbidden（无权限） |
| 404 | Not Found |
| 409 | Conflict（资源冲突） |
| 422 | Unprocessable Entity（业务校验失败） |
| 500 | Internal Server Error |

**Q: API 参数校验用什么？**

- `@Valid` / `@Validated` + `@NotBlank`、`@NotNull`、`@Size`、`@Pattern`、`@Email`
- 分组校验（Group Validation）
- 自定义校验注解

---

## 5. ORM 框架 API（MyBatis / JPA）

### 5.1 MyBatis

**Q: #{} 和 ${} 的区别？**

- `#{}`：预编译，生成 `?` 占位符，防止 SQL 注入（推荐）
- `${}`：直接拼接字符串，有 SQL 注入风险
- `${}` 用于动态表名、列名等场景

**Q: MyBatis 缓存机制？**

- **一级缓存**（SqlSession 级别）：默认开启，同一会话中相同查询直接返回
- **二级缓存**（Mapper 级别）：需手动开启，跨 SqlSession 共享
- 缓存失效：执行 insert/update/delete 后清空

**Q: MyBatis 分页怎么实现？**

- `PageHelper` 插件（拦截 `Executor`，自动拼接 `LIMIT`）
- 手写 `LIMIT #{offset}, #{limit}`
- MyBatis-Plus `Page` 对象

### 5.2 JPA / Hibernate

**Q: @Entity 注解的作用？**

- 标注 JPA 实体类，与数据库表映射
- `@Table(name)` 指定表名
- `@Id` + `@GeneratedValue` 指定主键生成策略

**Q: N+1 问题是什么？怎么解决？**

- 查询一个实体后，遍历时每条记录都触发额外查询
- 解决：`@EntityGraph`、`@Query("JOIN FETCH")`、`@BatchSize`

---

## 6. 数据库与缓存 API

**Q: JDBC 连接池常用哪些？**

- **HikariCP**（Spring Boot 2.x 默认）：性能最好
- **Druid**（阿里）：自带监控、防御 SQL 注入
- **DBCP2**、**Tomcat JDBC Pool**

**Q: Spring 操作 Redis 的 API？**

- `StringRedisTemplate` / `RedisTemplate<K, V>`
- `opsForValue()`、`opsForList()`、`opsForHash()`、`opsForSet()`、`opsForZSet()`
- `@Cacheable`、`@CachePut`、`@CacheEvict` 注解缓存

**Q: Redis 分布式锁怎么实现？**

```java
// 推荐 Redisson
RLock lock = redissonClient.getLock("myLock");
lock.lock(10, TimeUnit.SECONDS);
try {
    // 业务逻辑
} finally {
    lock.unlock();
}
```

---

## 7. JSON / 序列化 API

**Q: Jackson 常用注解？**

| 注解 | 说明 |
|:---|---:|
| @JsonProperty | 指定 JSON 字段名 |
| @JsonIgnore | 忽略字段 |
| @JsonFormat | 日期格式化 |
| @JsonInclude | 包含条件（非 null / 非空） |
| @JsonNaming | 命名策略（下划线转驼峰） |

**Q: 如何处理循环引用（双向关联）？**

- `@JsonIgnoreProperties`：类级别忽略
- `@JsonBackReference` / `@JsonManagedReference`
- `@JsonIgnore`：一方忽略
- 使用 DTO 而不是实体直接作为响应

**Q: 常用的序列化框架对比？**

| 框架 | 优点 | 缺点 |
|:---|---:|---:|
| Jackson | Spring Boot 默认，功能全面，社区活跃 | 配置复杂 |
| Gson | 轻量，API 简洁 | 性能一般 |
| Fastjson | 国产，性能好 | 版本兼容问题多 |
| Kotlinx.serialization | Kotlin 原生 | 仅限 Kotlin |

---

## 8. 工具类库 API

**Q: Guava 常用的 API？**

- `Lists.newArrayList()`、`Maps.newHashMap()`——集合工具
- `ImmutableList`、`ImmutableMap`——不可变集合
- `Multimap`、`BiMap`、`Table`——特殊集合
- `Preconditions.checkNotNull()`——参数校验
- `RateLimiter`——限流（令牌桶）
- `CacheBuilder`——本地缓存（过期、最大大小）

**Q: Apache Commons 常用 API？**

- `StringUtils.isBlank()`、`StringUtils.join()`
- `CollectionUtils.isEmpty()`
- `FileUtils.copyFile()`、`IOUtils.toString()`
- `ObjectUtils.equals()`、`ObjectUtils.defaultIfNull()`

**Q: Lombok 常用注解？**

| 注解 | 作用 |
|:---|---:|
| @Data | @Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor |
| @Builder | 建造者模式 |
| @Slf4j | 注入 log 对象 |
| @AllArgsConstructor | 全参构造 |
| @NoArgsConstructor | 无参构造 |

---

## 9. 设计模式

**Q: Spring 中使用了哪些设计模式？**

| 模式 | 示例 |
|:---|---:|
| 工厂模式 | BeanFactory / ApplicationContext |
| 单例模式 | Bean 默认 singleton |
| 代理模式 | AOP 动态代理 |
| 模板方法 | JdbcTemplate、RestTemplate |
| 观察者模式 | ApplicationListener / @EventListener |
| 策略模式 | Resource 解析策略 |
| 适配器模式 | HandlerAdapter |
| 装饰器模式 | BeanWrapper |

**Q: 单例模式的三种实现？**

```java
// 1. 饿汉式
public class Singleton1 {
    private static final Singleton1 INSTANCE = new Singleton1();
    private Singleton1() {}
    public static Singleton1 getInstance() { return INSTANCE; }
}

// 2. 双重检查锁定（DCL）
public class Singleton2 {
    private static volatile Singleton2 instance;
    private Singleton2() {}
    public static Singleton2 getInstance() {
        if (instance == null) {
            synchronized (Singleton2.class) {
                if (instance == null) instance = new Singleton2();
            }
        }
        return instance;
    }
}

// 3. 静态内部类
public class Singleton3 {
    private Singleton3() {}
    private static class Holder {
        static final Singleton3 INSTANCE = new Singleton3();
    }
    public static Singleton3 getInstance() { return Holder.INSTANCE; }
}
```

---

## 10. 实战场景题

**Q: 设计一个短链接生成服务，API 该如何设计？**

```
POST   /api/shorten    { "longUrl": "https://..." } → { "shortUrl": "https://s.co/abc123" }
GET    /api/{code}     302 重定向到原始 URL
GET    /api/{code}/stats  返回点击数、来源等统计

核心：发号器（雪花算法 / Redis incr / 62进制编码）
```

**Q: 一个接口响应慢，如何排查？**

1. 查看日志定位耗时区间
2. 数据库慢查询分析（慢 SQL 日志 + EXPLAIN）
3. Redis 缓存是否命中
4. 远程调用（第三方 API）是否超时
5. 是否死锁或线程池阻塞（jstack）
6. GC 停顿是否频繁（jstat）

**Q: 如何设计一个高可用的 API 网关？**

- **功能**：路由转发、限流、鉴权、熔断、日志
- **技术选型**：Spring Cloud Gateway / Zuul / Kong
- **限流**：令牌桶（RateLimiter）+ 分布式（Redis Lua）
- **熔断**：Sentinel / Hystrix / Resilience4j
- **鉴权**：JWT / OAuth2 / API Key

**Q: API 返回结果如何统一封装？**

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
```

**Q: 谈谈你对 RestTemplate / WebClient / OpenFeign 的理解？**

| 组件 | 类型 | 说明 |
|:---|---|---|
| RestTemplate | 同步 HTTP 客户端 | Spring 传统方案，Spring Boot 3.x 标记为 deprecated |
| WebClient | 响应式 HTTP 客户端 | Spring WebFlux 推荐，支持同步/异步 |
| OpenFeign | 声明式 HTTP 客户端 | Spring Cloud 集成，注解声明接口即可调用 |

---

## 推荐学习资料

- ✅ [Spring 官方文档](https://spring.io/docs)
- ✅ [Baeldung Java 教程](https://www.baeldung.com/)
- ✅ 牛客网 / LeetCode 面试高频题
- ✅ GitHub：`crossoverJie/Java-Interview`、`doocs/advanced-java`
- ✅ 掘金 / 思否 Java 面试专栏
