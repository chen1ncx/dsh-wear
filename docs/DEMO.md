# 拍视频用的说明 / Demo script

这份文档是给「AI 生成介绍视频」用的：拍什么、说什么、先准备什么。
图片素材都在 [`docs/shots/`](shots)，全是演示数据，没有真实会话和 IP。

## 一句话定位

> 「在手表上看 DSH、批权限、停任务 —— 一条命令装好。」

## 先准备

1. **一台跑 DSH 的机器**（电脑就行，云服务器也行），能 SSH。
2. **一块手表**（Android 7+，本机验证的是 OPPO Watch 2 / Android 8.1 / 372×430）。
3. **录屏**：
   - 电脑：OBS 或系统自带录屏；
   - 手表：`adb shell screenrecord /sdcard/demo.mp4`（最长 3 分钟），或者直接用手机拍手表。
4. **一个干净的工作区**：视频里不要出现真实项目名、私聊内容、内网 IP。
   演示可以用 `docs/shots/` 里的截图，或者临时建一个空目录跑 DSH。
5. **配对码**：录之前先在 DSH 里说「给我手表配对码」，拿到码再开始录，码 10 分钟有效。
   ⚠️ 配对码是密钥，视频里要么打码，要么用完之后在「设备」里撤销掉。

## 分镜（约 75 秒）

| # | 时长 | 画面 | 旁白 / 字幕 |
| --- | --- | --- | --- |
| 1 | 8s | 电脑上 DSH 正在跑一个长任务 | 「DSH 跑任务的时候，人得一直盯着屏幕回权限。」 |
| 2 | 6s | 切到手表抬腕 | 「所以我把 DSH 搬到了手表上。」 |
| 3 | 12s | 终端敲一条命令 | 「装插件只需要一条命令。」 |
| 4 | 10s | 手表配对页输 6 位码 | 「在对话里问助手要配对码，手表上输一次，之后不用再配。」 |
| 5 | 15s | 手表列表 → 点进会话 → 滑到审批卡 → 点允许 | 「列表、对话、权限确认，抬手就能点，跟网页端抢答，谁先点算谁的。」 |
| 6 | 8s | 点「自动通过」→ 确认卡 | 「不想被打断，就让这个会话自动通过。」 |
| 7 | 6s | 顶栏 ＋ 新建 / 长按删除 | 「新建对话、长按删除，都在手表上。」 |
| 8 | 6s | 打开 GitHub 仓库页 | 「插件和 App 的源码都在这，MIT。」 |
| 9 | 4s | 结尾 logo + 仓库地址 | 「AI 生成的，代码都能看。」 |

## 第 3 镜：那条命令

```bash
git clone --depth 1 https://github.com/chen1ncx/dsh-wear.git ~/dsh-wear && cd ~/dsh-wear && ./install.sh
```

装完重启一次 DSH，验证：

```bash
curl -s http://127.0.0.1:8787/api/hello
```

## 第 4 镜：配对

```bash
adb install -r dsh-watch.apk
```

手表 → 配对页 → 地址填 `http://192.168.0.1:8787`（换成你的机器 IP）→ 输 6 位码 → 连接。

> 录视频时把地址换成 `192.168.0.1` 这种演示 IP，别把家里/公司的内网地址放出去。

## 第 5-7 镜：手表上点一遍

按这个顺序点，每个界面停 2 秒：

1. 会话列表（含「已归档 · N」折叠行）
2. 点进「修构建脚本」→ 看到审批卡
3. 点 `允许`
4. 顶栏「设备」→ 看服务器列表（这段可以打码）
5. 详情页 → 「审批 问」→ 切「自动通过」
6. 顶栏 `＋` → 新建对话
7. 长按一个会话 → 删除确认（**别真删**，点取消）
8. 左滑到主题页，快速换两三个颜色
9. 右滑到退出页收尾

## 字幕里可以放的信息

- 仓库：`github.com/chen1ncx/dsh-wear`
- 一条命令：`git clone … && ./install.sh`
- 插件：`dsh-watch-bridge`（零依赖 Cordis 插件，单文件 `lib/index.js`）
- App：45 KB，纯 framework 控件，minSdk 21
- 声明：**本项目由 AI 生成**（README 顶部也写了）

## 不要做的事

- 不要录到真实会话标题、聊天内容、内网 IP、配对码、token。
- 不要把 `keystore.jks` 或 `devices.json` 传到任何地方。
- 演示完如果用过真实服务器，去「设备」里撤销掉临时设备。

## 仓库里现成的素材

```
docs/shots/screen-talk.png       会话列表
docs/shots/screen-detail.png     详情 + 审批卡
docs/shots/screen-approval.png   同上（审批卡）
docs/shots/screen-say.png        发一句话
docs/shots/screen-stop.png       停止确认
docs/shots/screen-delete.png     长按删除确认
docs/shots/screen-pair.png       配对页
docs/shots/screen-theme.png      主题色
docs/shots/screen-exit.png       退出页
```
