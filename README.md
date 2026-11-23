# AI Study Assistant

基于 Java 17 / Spring Boot 的本地 Markdown 学习资料问答演示。支持可解释关键词检索、带文件与行号的来源查看，以及提问和引用核对页面。

## 启动

需要 JDK 17 与 Maven。在仓库根目录执行：

```powershell
mvn -B '-Dstudy.llm.mode=fake' package
java -jar target/ai-study-assistant-0.1.0-SNAPSHOT.jar --study.llm.mode=fake
```

另开终端重建内存索引（PowerShell）：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/documents/reindex
```

打开 http://127.0.0.1:8080/ ，可尝试“联合索引如何影响排序？”或“EXPLAIN中的rows是什么意思？”。每次重启都需要重新建立索引。内置资料为通用示例；本仓库不包含个人笔记、规划、草稿或内部实验记录。

## 行为与接口

- `POST /documents/reindex`：重建索引。
- `POST /questions/search`：检索候选，JSON请求字段为 `question`。
- `POST /questions/answer`：问答，JSON请求字段为 `question`。
- `GET /chunks/{id}`：查看来源片段。
- 页面区分实际引用和其他候选，支持展开原文与定位行号。

默认 Fake 模式只演示流程，不会调用真实模型。应用默认绑定本机，没有登录与权限管理，不适合作为公网服务。

## 使用 DeepSeek 模式

从项目根目录复制配置模板：

```powershell
Copy-Item .env.example .env
```

如果已经有 `.env`，直接编辑，不要覆盖。填入自己的密钥并切换模式：

```properties
STUDY_LLM_MODE=deepseek
DEEPSEEK_API_KEY=替换为你自己的真实密钥
```

使用 UTF-8 编码，按 `KEY=value` 填写，不加引号，不写 `$env:` 或 `export`。`.env` 已被 Git 忽略，不能上传或分享；`.env.example` 不含真实密钥。应用会读取当前工作目录的 `.env`，请从项目根目录启动：

```powershell
java -jar target/ai-study-assistant-0.1.0-SNAPSHOT.jar
```

开发时也可执行 `mvn spring-boot:run`。启动时不要再附加 `--study.llm.mode=fake`。修改 `.env` 后需要重启服务，再调用 `/documents/reindex` 重建索引，之后打开页面提问。

命令行参数、Java 系统属性和系统环境变量优先于 `.env`。如果当前 PowerShell 曾设置过这两个环境变量，可执行 `Remove-Item Env:STUDY_LLM_MODE, Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue` 后再启动。没有 `.env` 时默认 Fake；DeepSeek 模式缺少密钥会启动失败。

真实模式将向 DeepSeek 发送问题及选中的资料片段，并可能产生费用。代码中的模型为 `deepseek-v4-pro`；每位使用者须自行准备可用的 DeepSeek API Key。引用编号校验不代表回答内容已被来源支持，仍需核对原文。

2026-11-23 已使用真实 DeepSeek 完成两道公开示例题、引用原文定位和无匹配资料路径的冒烟验证；范围与结果见 [真实调用验收记录](docs/deepseek-smoke-2026-11-23.md)。这不是大规模回答质量评测。

引用编号存在只表示编号有效，不保证原文支持答案；关键词命中也不代表资料足够。此项目使用单次问答和内存索引，不保留会话历史。

## 测试

```powershell
mvn -B '-Dstudy.llm.mode=fake' test
```

发布版保留独立单元与接口测试；依赖私人资料和内部历史报告的评测不包含在本仓库中。没有引用内部实验结果作为公开效果承诺。
