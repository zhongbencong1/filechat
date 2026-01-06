#!/bin/bash
# 快速启动脚本 - 解决 npm install 卡住问题

cd "$(dirname "$0")"

echo "=========================================="
echo "前端项目快速启动脚本"
echo "=========================================="

# 检查 Node.js 版本
echo "检查 Node.js 版本..."
node --version
npm --version

# 停止可能卡住的进程
echo "清理可能卡住的进程..."
pkill -f "npm install" 2>/dev/null || true

# 清理缓存和依赖
echo "清理旧的依赖..."
rm -rf node_modules package-lock.json
npm cache clean --force

# 使用国内镜像安装
echo "使用国内镜像安装依赖..."
npm install --registry=https://registry.npmmirror.com --loglevel=info

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "依赖安装成功！"
    echo "=========================================="
    echo "启动开发服务器..."
    npm run dev
else
    echo ""
    echo "=========================================="
    echo "依赖安装失败，尝试使用 cnpm..."
    echo "=========================================="
    
    # 检查是否有 cnpm
    if ! command -v cnpm &> /dev/null; then
        echo "安装 cnpm..."
        npm install -g cnpm --registry=https://registry.npmmirror.com
    fi
    
    echo "使用 cnpm 安装依赖..."
    cnpm install
    
    if [ $? -eq 0 ]; then
        echo "依赖安装成功！启动开发服务器..."
        npm run dev
    else
        echo "安装失败，请检查网络连接或查看错误信息"
        exit 1
    fi
fi

