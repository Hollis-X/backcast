# 回译 Backcast：协作约定

Android 上的 AI 代理 App（Java、AppCompat + Material Components、Gradle KTS）。
功能、架构和各模块的详细说明在 `项目配置.md`，改动功能时要同步更新它。

## 语言

- 回复、提交说明、PR 描述和文档都用简体或繁体书写，跟随用户当次使用的写法。
- 写规则或说明时，语言一项写「简体或繁体」，不要笼统写「中文」。

## 提交与推送

- 提交说明用简体或繁体逐项写清楚：标题一句话概括；正文列出改了哪些文件或方法、为什么改、测试有什么变化、对数据有没有影响。
  会清空或迁移数据的改动必须写明。
- 提交说明和 PR 描述里不加任何署名行（如 `Co-Authored-By`、`Generated with ...`）。
- 日常开发分支是 `debug`，远端是 `origin/debug`。
- 用户没有明确要求，就不要 `git push`；可以在本地提交。
  - 「推送」「推 github」「提交」「合并提交」「本地合并提交」：把本地未推送的提交压成一条，推到 `origin/debug`。
  - 用户说「先不推送」或「只在本地」时，只在本地提交。
- 仓库根目录的 `debug.apk` 受 git 跟踪。提交或推送前要先执行 `:app:assembleDebug`，
  把 `app/build/outputs/apk/debug/app-debug.apk` 复制成根目录的 `debug.apk`，一起提交。只构建、不复制，不算更新。

## 构建（限制资源占用）

在这台机器上构建时，只用两个核心、最低优先级、两个 worker 跑 Gradle：

```bash
taskset -c 0,1 nice -n 19 ionice -c3 timeout 1500 ./gradlew --no-daemon --console=plain --max-workers=2 :app:assembleDebug
```

本机的 JDK 17 和 Android SDK 在 `~/.cache/backcast-build-runtime/`，`JAVA_HOME`、`ANDROID_HOME` 指向那里。

## 测试

- JVM 回归测试：先执行 `:app:writeAgentTestClasspath` 生成 `app/build/agent-test-classpath.txt`，然后运行
  `tests/run_agent_tests.sh "$PWD" <org.json JAR> [测试类名]`（需要 JDK 17；本机的 JAR 在 `~/.cache/backcast-test-runtime/`）。
  不带测试类名时跑全部。
- Android 单元测试（Robolectric）：`:app:testDebugUnitTest`。设置 `BACKCAST_SCREENSHOTS=<目录>` 会输出各页面截图
  （ScreenshotTour、ConversationShot、AgentChatShot、InputBarLayoutTest 等），改界面后要对照检查浅色和深色。
- 资源检查：`python3 -B tests/ResourceReachabilityCheck.py "$PWD"` 必须输出 PASS，删掉不再使用的资源。
- 已知情况：`ToolkitRegressionTest` 的 readelf 一项在没有对应工具链的机器上会失败，与业务改动无关。

## 界面

- 全局主题是 Material 3（`Theme.Material3.DayNight`），颜色只用 `res/values*/colors.xml` 里的 `md_*` 角色，深浅色各一套。
- 代码里动态创建的视图从 `ui/M3` 取颜色和形状，每个页面在 `onCreate` 里调用 `M3.load(this)`。
- 按钮和文本框用主题属性 `appButtonFilled` / `appButtonTonal` / `appButtonOutlined` / `appButtonText` / `appFieldStyle`。
