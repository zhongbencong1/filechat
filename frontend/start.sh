#!/bin/bash
# 前端启动脚本

cd "$(dirname "$0")"

echo "正在安装依赖..."
npm install

if [ $? -eq 0 ]; then
    echo "依赖安装成功，启动开发服务器..."
    npm run dev
else
    echo "依赖安装失败，请检查网络连接或使用以下命令手动安装："
    echo "npm install --registry=https://registry.npmmirror.com"
fi

