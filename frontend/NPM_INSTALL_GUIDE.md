# npm install 卡住问题解决方案

## 问题原因
npm install 卡住通常是由于：
1. 网络连接慢或超时
2. npm 官方源访问慢
3. Node.js 版本过旧（当前 v10.19.0）

## 解决方案

### 方案1：使用已配置的镜像源（推荐）
已创建 `.npmrc` 文件配置了淘宝镜像，直接运行：

```bash
cd /mnt/c/companyproject/cursor/2026010401/frontend
npm install
```

### 方案2：使用 cnpm（更快）
```bash
# 安装 cnpm
npm install -g cnpm --registry=https://registry.npmmirror.com

# 使用 cnpm 安装依赖
cd /mnt/c/companyproject/cursor/2026010401/frontend
cnpm install
```

### 方案3：清理缓存后重试
```bash
cd /mnt/c/companyproject/cursor/2026010401/frontend
npm cache clean --force
rm -rf node_modules package-lock.json
npm install
```

### 方案4：使用 yarn（替代方案）
```bash
# 安装 yarn
npm install -g yarn --registry=https://registry.npmmirror.com

# 使用 yarn 安装依赖
cd /mnt/c/companyproject/cursor/2026010401/frontend
yarn install
```

### 方案5：分步安装（如果某个包卡住）
```bash
cd /mnt/c/companyproject/cursor/2026010401/frontend

# 先安装核心依赖
npm install vue@^3.3.4 vue-router@^4.2.5 --save

# 再安装其他依赖
npm install
```

### 方案6：使用启动脚本
```bash
cd /mnt/c/companyproject/cursor/2026010401/frontend
bash quickstart.sh
```

## 如果仍然卡住

1. **检查网络连接**：确保能访问外网或镜像源
2. **升级 Node.js**：当前版本 v10.19.0 较旧，建议升级到 v16+ 或 v18+
3. **使用代理**：如果有代理，配置 npm 代理
4. **手动下载**：如果某个特定包卡住，可以手动下载后安装

## 验证安装
安装完成后，检查：
```bash
ls node_modules/.bin/vite
```

如果文件存在，说明安装成功。

