# 基础配置

1. pom 导入依赖，yml 配置微服务名称
2. 在 nacos/bin 目录下用 cmd 命令
   ```cmd
   startup.cmd -m standalone
   ```
3. 高版本在子服务里只需要配置 application 就可以了,为了符合规范在主函数上写这个注解 @EnableDiscoveryClient//开启 nacos 的服务发现功能 ，就能注册到 nacos 了

## 远程调用

1. 对于多个服务都会用到的类，例如实体类，要单独创一个服务模块叫 model，同时需要在其他服务的 pom 里导入 model 依赖
2. RestTemplate 是主动发送求情的，可以用来请求其他服务（这叫编程式 rest 客户端）
3. @LoadBalanced 可以自动完成负载均衡

   ```java
   @Configuration
   public class OrderConfig {
       @LoadBalanced
       @Bean
       RestTemplate restTemplate(){
               return new RestTemplate();
           }
   }

   ```

   ```java
   private Product getProductFromRemoteWithLoadBalanceAnnotation(Long productId){
       String url = "http://service-product/product/"+productId;
       //2、给远程发送请求； service-product 会被动态替换为对应机器的IP+port
       Product product = restTemplate.getForObject(url, Product.class);
       return product;
   }
   ```

4. 更常用的是 openfeign
   - 使用
     在启动项上加@EnableFeginClient 注解
     在需要发送请求的类最外面加@FeignClient 注解
     用@GetMapping 注解发送请求

# nacos

1. 注册中心宕机，远程调用还能成功吗？
   如果不是第一次调用，有缓存则可以。
   是第一次调用没有缓存则不行
2. 配置列表的值可以热修改
   - nacos 的配置列表里的配置是用来代替 application 的本地配置的
   ```java
    @component
    //自动装配值（配置的前缀为order）
    @configurationProperties(prefix = "order" )
    @Data
    public class OrderProperties {
        string timeout;
        string autoConfirm;
    }
   ```
   在要用的地方用 Autowire 注入

# openFeign

1. 远程调用
2. 日志
3. 超时
4. 重试
5. 拦截器（主要是加请求头）

# Geteway 网关

负责把前端的请求转发给对应服务

1. 过滤器
   1. 路由重写：可以重写转发过来的路径
