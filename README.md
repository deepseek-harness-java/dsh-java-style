# DSH Tone Tuner（文风调音台）

> 独立 AI 产品案例 · deepseek-harness-java Java Native Plugin
> 文风画像 · 双文本对比 · 改写建议 · 可读性评分 · 术语一致性（不动原稿）

一个基于 DSH（deepseek-harness-java）Java Native Plugin 的独立 AI 产品：把样本文本变成可量化的文风画像，按目标风格给出改写建议清单。AI 只做分析和建议，**红线是不覆盖原稿、不替用户发送**。

## 架构

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│  dsh-java-style-app         │        │  deepseek-harness-java:8090  │
│  (Spring Boot, port 8118)   │        │                              │
│  ├─ REST: /api/style/*      │◄───────┤  DshToneStylePlugin          │
│  ├─ /api/assistant/stream ──┼───────►│  (JAVA_NATIVE, in-process)   │
│  └─ static/ 文风调音台 UI    │  SSE   │   ├─ st_profile_text         │
└─────────────────────────────┘        │   ├─ st_compare_two          │
                                       │   ├─ st_rewrite_suggest      │
                                       │   ├─ st_readability          │
                                       │   └─ st_glossary_check       │
                                       └──────────────────────────────┘
```

- **App**（`dsh-java-style-app`）：Spring Boot 应用，提供风格分析 REST API + 工作台前端 + SSE 助手转发。
- **Plugin**（`dsh-java-style-plugin`）：`AbstractHarnessPlugin` Java Native 插件，5 个工具通过 REST 回调 App。

## 核心功能

| 工具 | 说明 |
|------|------|
| `st_profile_text` | 样本文本 → 文风画像：平均句长/语气类型（指令型/谨慎型/热情型/书面型/平实型）/词汇风格（书面/口语/中性）/标点习惯 |
| `st_compare_two` | 双文本风格对比：句长/语气/词汇/感叹号差异清单 + 能否混排结论 |
| `st_rewrite_suggest` | 按目标风格（简洁/专业/正式/活泼）出改写建议：长句拆分 + 口语词替换 + 通用建议；**不修改原稿** |
| `st_readability` | 可读性评分：A/B/C/D 等级 + 分数（长句占比/超长段落/括号嵌套扣分）+ 优化提示 |
| `st_glossary_check` | 术语一致性：常见错别字（登陆→登录、帐号→账号、其它→其他、做为→作为）+ 大小写规范（javascript→JavaScript、mysql→MySQL）+ 自定义词表命中 |

## 安全红线

- 所有工具**只读分析**：不修改、不删除、不发送任何文本。
- systemPrompt + PRE_TOOL_USE hook 强制约束；用户要求"改完自动发出去"时，AI 拒绝发送并说明红线，只提供改写建议草稿。
- E2E `T8` 验证 AI 拒绝"直接改写并自动发送"请求。

## 运行

```bash
# 1. 构建
mvn package -DskipTests

# 2. 启动 App（端口 8118）
SERVER__PORT= java -jar dsh-java-style-app/target/dsh-java-style-app-0.1.0-SNAPSHOT.jar --server.port=8118

# 3. 安装插件（harness 运行在 8090）
cp dsh-java-style-plugin/target/dsh-java-style-plugin-0.1.0-SNAPSHOT.jar ~/.dsh/standalone/plugins/dsh-java-style-plugin.jar
curl -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId":"dsh-java-style-plugin",
  "jarName":"dsh-java-style-plugin.jar",
  "displayName":"Tone Tuner",
  "pluginVersion":"0.1.0",
  "runtimeType":"JAVA_NATIVE",
  "sourcePath":"/Users/<you>/.dsh/standalone/plugins/dsh-java-style-plugin.jar",
  "entrypoint":"cn.xiaofuge.dsh.com.dsh.style.plugin.DshToneStylePlugin",
  "installMode":"REPLACE"
}'
curl -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' -d '{"pluginId":"dsh-java-style-plugin"}'

# 4. 打开 http://127.0.0.1:8118
```

> 注意：替换插件 jar 后需要重启 harness 进程，否则 JVM 缓存旧 jar 句柄会报 `ZipFile invalid LOC header`。

## E2E

```bash
python3 tone_tuner_e2e.py   # 27/27 PASS
```

覆盖：画像生成/短文本拒绝、双文本对比、改写建议（含"不覆盖原稿"红线标注）、可读性评分（口语 vs 书面文本）、术语检查（错别字命中 + 干净文本 0 问题 + 自定义词表）、SSE AI 工具调用（st_readability / st_rewrite_suggest）、红线（拒绝改写并自动发送）。

## 截图

| 文件 | 场景 |
|------|------|
| `screenshots/01-home.png` | 工作台首页：分析工作台（体检/术语/画像库） |
| `screenshots/02-ai-drawer.png` | AI 调用 st_profile_text + st_readability 出画像与评分 |
| `screenshots/03-safety.png` | AI 拒绝"改写并自动发送"，只提供建议清单 |
