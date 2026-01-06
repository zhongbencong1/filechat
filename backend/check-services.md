# 服务诊断指南

## 问题：前端登录返回 500 错误

### 检查步骤：

1. **检查服务是否正常启动**
   - Gateway 服务：http://localhost:8080
   - Auth 服务：http://localhost:8084

2. **检查数据库连接**
   - 确保 MySQL 已启动
   - 检查数据库 `smart_doc_qa` 是否存在
   - 检查用户表是否已创建

3. **检查 Redis 连接**
   - 确保 Redis 已启动
   - 检查 Redis 连接配置

4. **检查日志**
   - Gateway 日志：查看是否有路由错误
   - Auth 服务日志：查看是否有异常堆栈

### 常见问题：

1. **路由配置问题**
   - Gateway 路由已配置 StripPrefix=0，保留完整路径
   - 前端请求：`/api/auth/login` -> Gateway -> `http://localhost:8084/api/auth/login`

2. **Redis 连接问题**
   - Gateway 在响应式环境中使用阻塞式 RedisTemplate 已修复
   - 确保 Redis 服务正常运行

3. **数据库连接问题**
   - 检查 application-common.yml 中的数据库配置
   - 确保数据库已创建并初始化

4. **跨域问题**
   - Gateway 已配置 CORS，允许所有来源

### 测试命令：

```bash
# 测试 Gateway 健康检查
curl http://localhost:8080/actuator/health

# 测试 Auth 服务直接访问
curl -X POST http://localhost:8084/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'

# 测试通过 Gateway 访问
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'
```

