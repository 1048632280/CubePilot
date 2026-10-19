## 文件处理

- 所有文件读写使用 UTF-8 编码；修改文件时不要改变原有编码。
- 执行本地 shell 命令默认使用 PowerShell 7（`pwsh`）。
- 代码注释使用中文。

## GitHub 网络访问

- 访问 GitHub 的网络请求必须通过本机 `7897` 代理端口，代理地址使用 `http://127.0.0.1:7897`。
- 使用 Git 或其他命令行工具访问 GitHub 时，为对应命令配置该代理；PowerShell 会话可通过 `HTTP_PROXY` 和 `HTTPS_PROXY` 环境变量配置。
