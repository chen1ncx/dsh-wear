#!/usr/bin/env node
/**
 * Retry-friendly release uploader: pushes main, then cuts (or reuses) the
 * release for the current package version and attaches the APK.
 *
 *   GITHUB_TOKEN=ghp_xxx node upload-release.mjs
 */
import { readFile, stat } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'

const OWNER = process.env.GITHUB_OWNER ?? 'chen1ncx'
const REPO = process.env.GITHUB_REPO ?? 'dsh-wear'
const TOKEN = process.env.GITHUB_TOKEN
if (!TOKEN) {
  console.error('缺少 GITHUB_TOKEN')
  process.exit(1)
}

const pkg = JSON.parse(await readFile(new URL('./package.json', import.meta.url), 'utf8'))
const tag = `v${pkg.version}`
const api = (path) => `https://api.github.com${path}`
const headers = {
  authorization: `Bearer ${TOKEN}`,
  accept: 'application/vnd.github+json',
  'user-agent': 'dsh-wear-publisher',
  'content-type': 'application/json',
}

function git(args) {
  return execFileSync('git', args, { cwd: new URL('.', import.meta.url), encoding: 'utf8', env: { ...process.env, GIT_TERMINAL_PROMPT: '0' } }).trim()
}

async function json(path, init = {}) {
  const res = await fetch(api(path), { ...init, headers: { ...headers, ...(init.headers ?? {}) }, signal: AbortSignal.timeout(30000) })
  const text = await res.text()
  let body
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  return { status: res.status, body }
}

console.log(`[1/3] 推送 main（${tag}）`)
git(['add', '-A'])
try {
  git(['commit', '-m', `release ${tag}`])
} catch {}
const pushUrl = `https://x-access-token:${TOKEN}@github.com/${OWNER}/${REPO}.git`
git(['push', pushUrl, 'main'])
console.log('      pushed')

console.log(`[2/3] 准备 release ${tag}`)
let release = await json(`/repos/${OWNER}/${REPO}/releases/tags/${tag}`)
if (release.status === 404) {
  release = await json(`/repos/${OWNER}/${REPO}/releases`, {
    method: 'POST',
    body: JSON.stringify({
      tag_name: tag,
      target_commitish: 'main',
      name: `DSH wear ${pkg.version}`,
      draft: false,
      prerelease: false,
      body: [
        '手表上的 DSH：看当前任务、最近几条回复、**点一下批准权限**。',
        '',
        '## 装什么',
        '- `dsh-watch.apk` —— 手表 App（Android 5.0+，实测 OPPO Watch 2 / Android 8.1 / 372×430 / 320dpi）',
        '- 插件用仓库源码：`./install.sh`',
        '',
        '## 快速开始',
        '```bash',
        './install.sh',
        '# 在 DSH 对话里说「给我手表配对码」',
        'adb install -r dsh-watch.apk',
        '```',
        '',
        '配对码只从对话里来：网页、日志、HTTP 接口都不显示。',
        '',
        '## 界面',
        '- 四页横滑：退出 / 对话 / 配对 / 主题，底部玻璃胶囊指示',
        '- 8 套主题色，点了立刻生效',
        '- 会话跑完出红点 + 系统通知',
      ].join('\n'),
    }),
  })
  if (release.status >= 300) {
    console.error('创建 release 失败:', release.status, JSON.stringify(release.body))
    process.exit(1)
  }
}
console.log('      ', release.body.html_url)

console.log('[3/3] 上传 APK')
const apkPath = new URL('./dist/dsh-watch.apk', import.meta.url)
const info = await stat(apkPath)
const apk = await readFile(apkPath)
const existing = await json(`/repos/${OWNER}/${REPO}/releases/${release.body.id}/assets`)
for (const asset of existing.body ?? []) {
  if (asset.name === 'dsh-watch.apk') {
    await json(`/repos/${OWNER}/${REPO}/releases/assets/${asset.id}`, { method: 'DELETE' })
    console.log('      删除旧附件')
  }
}

for (let attempt = 1; attempt <= 4; attempt += 1) {
  try {
    const res = await fetch(
      `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.body.id}/assets?name=dsh-watch.apk`,
      {
        method: 'POST',
        headers: {
          authorization: `Bearer ${TOKEN}`,
          'content-type': 'application/vnd.android.package-archive',
          'user-agent': 'dsh-wear-publisher',
          'content-length': String(info.size),
        },
        body: apk,
        signal: AbortSignal.timeout(120000),
      },
    )
    if (res.status < 300) {
      const body = await res.json()
      console.log('      上传成功:', body.browser_download_url)
      console.log(`\n✅ ${release.body.html_url}`)
      process.exit(0)
    }
    console.log(`      第 ${attempt} 次失败: HTTP ${res.status} ${(await res.text()).slice(0, 160)}`)
  } catch (err) {
    console.log(`      第 ${attempt} 次失败: ${err.message}`)
  }
  await new Promise((r) => setTimeout(r, 2000 * attempt))
}
console.error('上传最终失败')
process.exit(1)
