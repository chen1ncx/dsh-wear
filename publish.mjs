#!/usr/bin/env node
/**
 * Publish DSH wear to GitHub: create the repo if needed, push main, cut a
 * release and attach the APK.
 *
 *   GITHUB_TOKEN=ghp_xxx node publish.mjs
 *
 * The token is only used in memory and in the one `git push` URL; nothing is
 * written to .git/config.
 */
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'

const OWNER = process.env.GITHUB_OWNER ?? 'chen1ncx'
const REPO = process.env.GITHUB_REPO ?? 'dsh-wear'
const TOKEN = process.env.GITHUB_TOKEN
const API = 'https://api.github.com'

if (!TOKEN) {
  console.error('缺少 GITHUB_TOKEN，例如: GITHUB_TOKEN=ghp_xxx node publish.mjs')
  process.exit(1)
}

const headers = {
  authorization: `Bearer ${TOKEN}`,
  accept: 'application/vnd.github+json',
  'user-agent': 'dsh-wear-publisher',
  'content-type': 'application/json',
}

async function api(path, init = {}) {
  const response = await fetch(`${API}${path}`, { ...init, headers: { ...headers, ...(init.headers ?? {}) } })
  const text = await response.text()
  let body = null
  try {
    body = text === '' ? null : JSON.parse(text)
  } catch {
    body = text
  }
  return { status: response.status, body }
}

function git(args, extraEnv = {}) {
  return execFileSync('git', args, {
    cwd: new URL('.', import.meta.url),
    encoding: 'utf8',
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0', ...extraEnv },
  }).trim()
}

const version = JSON.parse(execFileSync('node', ['-p', 'JSON.stringify(require("./package.json"))'], { encoding: 'utf8' })).version
const tag = `v${version}`

console.log(`[1/5] 检查仓库 ${OWNER}/${REPO}`)
let repo = await api(`/repos/${OWNER}/${REPO}`)
if (repo.status === 404) {
  console.log('      不存在，创建中…')
  const created = await api('/user/repos', {
    method: 'POST',
    body: JSON.stringify({
      name: REPO,
      description: 'Wrist-side client for DeepSeek Harness: approve permission prompts, read the tail of a session, stop a runaway turn — from a smartwatch.',
      private: false,
      has_issues: true,
      has_wiki: false,
      has_projects: false,
    }),
  })
  if (created.status >= 300) {
    console.error('创建仓库失败:', created.status, JSON.stringify(created.body))
    process.exit(1)
  }
  repo = created
} else if (repo.status !== 200) {
  console.error('读取仓库失败:', repo.status, JSON.stringify(repo.body))
  process.exit(1)
}
const htmlUrl = repo.body.html_url
console.log('      ', htmlUrl)

console.log('[2/5] 提交本地改动')
git(['add', '-A'])
try {
  git(['commit', '-m', `release ${tag}`])
} catch {
  console.log('      没有新改动')
}

console.log('[3/5] 推送 main')
const pushUrl = `https://x-access-token:${TOKEN}@github.com/${OWNER}/${REPO}.git`
git(['branch', '-M', 'main'])
git(['push', '--force', pushUrl, 'main'])
git(['remote', 'remove', 'origin'], {})
git(['remote', 'add', 'origin', `${htmlUrl}.git`])

console.log(`[4/5] 创建 release ${tag}`)
let release = await api(`/repos/${OWNER}/${REPO}/releases/tags/${tag}`)
if (release.status === 404) {
  release = await api(`/repos/${OWNER}/${REPO}/releases`, {
    method: 'POST',
    body: JSON.stringify({
      tag_name: tag,
      target_commitish: 'main',
      name: `DSH wear ${version}`,
      body: [
        '手表上的 DSH：看当前任务、最近几条回复、**点一下批准权限**。',
        '',
        '## 装什么',
        '- `dsh-watch.apk` —— 手表 App（Android 5.0+，实测 OPPO Watch 2 / 8.1 / 372×430）',
        '- 插件用仓库源码：`./install.sh` 装进 DSH profile',
        '',
        '## 快速开始',
        '```bash',
        './install.sh          # 装插件',
        '# 在 DSH 对话里说「给我手表配对码」',
        'adb install -r dsh-watch.apk',
        '```',
        '',
        '配对码只从对话里来：网页、日志、HTTP 接口都不显示。',
      ].join('\n'),
      draft: false,
      prerelease: false,
    }),
  })
  if (release.status >= 300) {
    console.error('创建 release 失败:', release.status, JSON.stringify(release.body))
    process.exit(1)
  }
} else if (release.status !== 200) {
  console.error('查询 release 失败:', release.status, JSON.stringify(release.body))
  process.exit(1)
}

const assetPath = new URL('./dist/dsh-watch.apk', import.meta.url)
if (!existsSync(assetPath)) {
  console.error('找不到 dist/dsh-watch.apk')
  process.exit(1)
}
console.log('[5/5] 上传 APK')
const { readFile } = await import('node:fs/promises')
const apk = await readFile(assetPath)
const upload = await fetch(
  `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.body.id}/assets?name=dsh-watch.apk`,
  {
    method: 'POST',
    headers: {
      authorization: `Bearer ${TOKEN}`,
      'content-type': 'application/vnd.android.package-archive',
      'user-agent': 'dsh-wear-publisher',
    },
    body: apk,
  },
)
if (upload.status >= 300) {
  console.error('上传失败:', upload.status, await upload.text())
  process.exit(1)
}
console.log('\n完成 ✅')
console.log(`仓库: ${htmlUrl}`)
console.log(`发布: ${htmlUrl}/releases/tag/${tag}`)
