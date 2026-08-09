# 油漆账号后台

需要 Node.js 22.5 或更高版本，使用 Node 内置 SQLite，无第三方运行依赖。

首次启动前设置环境变量：

```powershell
$env:YOUQI_JWT_SECRET="至少32位随机字符串"
$env:YOUQI_ADMIN_USER="admin"
$env:YOUQI_ADMIN_PASSWORD="至少8位强密码"
npm start
```

管理员控制台：`http://127.0.0.1:8787/admin`

生产环境必须放在 HTTPS 反向代理后，设置固定的 `YOUQI_JWT_SECRET`，备份 `backend/data/youqi.sqlite`，并限制数据库目录权限。
