# 交接说明（给接手的人 / 另一个 AI）

这份文档是「这个项目现在是什么状态、接下来做什么、什么不能碰」。
把它整段丢给另一个 AI 就够了。

## 一句话

**DSH on wear** —— 在 Android 手表上看 DeepSeek Harness：会话列表、对话内容、权限审批、提问、发话、停止任务。
一个零依赖 Cordis 插件 + 一个 45 KB 的框架级 App，全部由 AI 生成，MIT。

- 仓库：<https://github.com/chen1ncx/dsh-wear>
- 最新版本：**v1.0.0**（App versionName 1.0.0 / versionCode 26，插件 VERSION 0.2.2）
- 产物：`dsh-watch.apk`（release 附件）、插件 `lib/index.js`

## 现在的状态

已完成并在真机（OPPO Watch 2 / Android 8.1 / 372×430）上验证：

- 四页横滑：退出 ← 对话 → 配对 → 主题，底部透明胶囊指示
- 会话列表：运行中绿点、待确认红点+数字、归档折叠成「已归档 · N」
- 会话详情：消息卡片、审批卡（拒绝/允许）、提问卡、停止（带确认）、说一句
- 新建对话（顶栏 `＋`）、长按删除（带确认）
- 「审批 问 / 自动通过」开关（自动通过 = 插件替手表直接回 allowed-once）
- 设备命名持久化、多服务器切换、离线/空状态提示
- 配对：6 位码换 token，码只通过 `watch_pair_code` 工具给，不落网页/日志

文档与素材：

- `README.md` —— 中英说明、截图表格、一条命令安装
- `docs/DEMO.md` —— 拍介绍视频的分镜脚本
- `docs/shots/` —— 9 张演示截图（演示数据，无真实会话/IP）
- `SECURITY.md` —— 威胁模型和边界

## 唯一没做完的事

**手表连的是云上那台 DSH（`8.136.159.122:59217`），它的插件还停在 0.2.1，缺 `＋ / 长按删除 / 自动通过` 的接口。**
需要在云服务器上更新插件并重启 DSH：

```bash
cd /tmp && rm -rf dsh-wear && git clone --depth 1 https://github.com/chen1ncx/dsh-wear.git dsh-wear
P=$(readlink -f ~/.dsh/profiles/web/node_modules/dsh-watch-bridge)
[ -d "$P" ] || P=~/dsh-watch-bridge
cp -f dsh-wear/lib/index.js "$P/lib/index.js"
cp -f dsh-wear/package.json "$P/package.json"
```

重启后验证：

```bash
curl -s http://127.0.0.1:59217/api/hello     # 期望 "version":"0.2.2"
```

然后在手表上点一遍 `＋`、长按一个会话、把「审批 问」切成「自动通过」，三个都不能报错。

## 环境与命令

装插件（跑 DSH 的机器上，一条命令）：

```bash
git clone --depth 1 https://github.com/chen1ncx/dsh-wear.git ~/dsh-wear && cd ~/dsh-wear && ./install.sh
```

- 端口默认 8787：`WATCH_PORT=9000 ./install.sh`
- profile 默认 web：`DSH_PROFILE=tui ./install.sh`
- 装完**必须重启 DSH**（插件同进程加载）

装 App：

```bash
adb install -r dsh-watch.apk
adb shell am start -n io.dsh.watch/.MainActivity --es url http://192.168.0.1:8787 --es code <配对码>
```

自己构建 APK（不需要 Gradle）：

```bash
cd android && ./build.sh       # 依赖 aapt2/zipalign/apksigner + d8.jar
```

## 硬约束（不能违反）

1. **配对码是密钥**：只在用户主动问的时候给出，不要写进文件、不要截图、不要进日志。
2. **不要把真实 IP、会话标题、token、`devices.json`、`keystore.jks` 放进仓库/截图/视频。**
   演示地址统一用 `192.168.0.1`。
3. 删会话是不可逆的（DSH 侧会写墓碑、删日志），只能用户明确要求时做。
4. 改 `android/keystore.jks` 会让已装的 App 无法覆盖升级。
5. 撤销设备用「设备」里的长按删除；不要为了测试反复撤销用户的真实设备。

## 用什么数据演示

`docs/DEMO.md` 里有一个 mock 服务器的做法：本地起一个假的 `/api/state`，手表连上去就能看到
「修构建脚本 / 整理会议纪要 / 给周报配图 / README 翻译」这些演示会话。
不要拿用户的真实工作区录屏。

## 可以交给另一个 AI 的提示词

> 这个仓库 <https://github.com/chen1ncx/dsh-wear> 是「DSH on wear」：给 DeepSeek Harness 做的手表端客户端，
> 零依赖 Cordis 插件 `lib/index.js` + 单文件 Android App `android/src/io/dsh/watch/MainActivity.java`。
> 先用 `./install.sh` 在跑 DSH 的机器上装插件、重启 DSH，`curl 127.0.0.1:8787/api/hello` 看到 0.2.2 就对了。
> 然后 `adb install -r dsh-watch.apk`，在 DSH 对话里问助手要配对码，手表配对页填
> `http://192.168.0.1:8787` + 6 位码。
> 需要验证的功能：列表/详情/审批（拒绝、允许）/提问/停止（有确认）/说一句/顶栏 ＋ 新建/长按删除（有确认）/
> 「审批 问 ↔ 自动通过」/设备切换与命名/离线页提示。
> 注意：配对码不能写进任何文件或截图；仓库里不要出现真实 IP、会话标题、token。
> 拍视频的话照 `docs/DEMO.md` 的分镜来，75 秒。
