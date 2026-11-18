/**
 * dsh-watch-bridge — a tiny HTTP bridge that lets a smartwatch (or any small
 * screen) watch and steer DeepSeek Harness: list sessions, read the tail of a
 * conversation, answer approval prompts, answer ask_user_question prompts,
 * send a short message, stop a running turn, and switch the approval policy.
 *
 * It is a Cordis plugin for a DSH profile, so it runs inside the same process
 * as the engine and talks to the host services directly. No dependencies
 * beyond Node builtins, so it installs the same on a phone and on a server.
 *
 * Pairing: the server shows a 6-digit code (web page /watch, log line, or
 * from the `watch_pair_code` tool the agent exposes). The watch sends it once and keeps the
 * long-lived token it gets back.
 *
 * @module dsh-watch-bridge
 */

import http from 'node:http'
import https from 'node:https'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/** Cordis plugin name (the loader row id is usually the same). */
export const name = 'watch-bridge'

/** Host services this plugin needs. Everything else is looked up defensively. */
export const inject = ['agents', 'sessions']

const VERSION = '0.2.2'

const STARTED_AT = Date.now()
const PROTOCOL = 1
const MAX_DEVICES = 8
const PAIR_TTL_MS = 10 * 60 * 1000
const PAIR_MAX_ATTEMPTS = 5
const ONLINE_MS = 3 * 60 * 1000
const MESSAGE_CAP = 480
const COMMAND_CAP = 600

// --------------------------------------------------------------------- utils

const now = () => Date.now()

function defaults(config) {
  const raw = typeof config?.get === 'function' ? config.get() : config
  const c = raw && typeof raw === 'object' ? raw : {}
  const dshHome = process.env.DSH_HOME || path.join(os.homedir(), '.dsh')
  return {
    host: typeof c.host === 'string' && c.host !== '' ? c.host : '0.0.0.0',
    port: Number.isFinite(Number(c.port)) ? Number(c.port) : 8787,
    name: typeof c.name === 'string' && c.name !== '' ? c.name : os.hostname(),
    stateDir: typeof c.stateDir === 'string' && c.stateDir !== '' ? c.stateDir : path.join(dshHome, 'watch-bridge'),
    approvalWaitMs: Number.isFinite(Number(c.approvalWaitMs)) ? Number(c.approvalWaitMs) : 120000,
    questionWaitMs: Number.isFinite(Number(c.questionWaitMs)) ? Number(c.questionWaitMs) : 300000,
    relayApprovals: c.relayApprovals !== false,
    allowDebug: c.allowDebug === true,
    tlsCert: typeof c.tlsCert === 'string' && c.tlsCert !== '' ? c.tlsCert : undefined,
    tlsKey: typeof c.tlsKey === 'string' && c.tlsKey !== '' ? c.tlsKey : undefined,
    relayQuestions: c.relayQuestions !== false,
    logLevel: typeof c.logLevel === 'string' ? c.logLevel : 'info',
  }
}

function log(cfg, level, ...args) {
  if (level === 'debug' && cfg.logLevel !== 'debug') return
  const stamp = new Date().toISOString().slice(11, 19)
  // eslint-disable-next-line no-console
  console.log(`[watch-bridge ${stamp}]`, ...args)
}

function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex')
}

function randomToken() {
  return crypto.randomBytes(24).toString('hex')
}

function randomCode() {
  // 6 digits, no leading zero, so a tiny keypad cannot lose a digit.
  return String(crypto.randomInt(100000, 1000000))
}

function json(res, status, body, extraHeaders) {
  const text = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    ...extraHeaders,
  })
  res.end(text)
}

async function readBody(req, limit = 1 << 20) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > limit) throw new Error('body too large')
    chunks.push(chunk)
  }
  if (chunks.length === 0) return {}
  const text = Buffer.concat(chunks).toString('utf8')
  if (text.trim() === '') return {}
  return JSON.parse(text)
}

function clampText(value, cap) {
  if (typeof value !== 'string') return ''
  const text = value.replace(/\r\n/g, '\n').trim()
  return text.length > cap ? `${text.slice(0, cap)}…` : text
}

function blocksText(blocks) {
  if (!Array.isArray(blocks)) return ''
  const parts = []
  for (const block of blocks) {
    if (!block || typeof block !== 'object') continue
    if (block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join('').trim()
}

function localAddresses() {
  const out = []
  try {
    for (const [iface, list] of Object.entries(os.networkInterfaces())) {
      for (const entry of list ?? []) {
        if (entry.family !== 'IPv4' || entry.internal) continue
        out.push({ iface, address: entry.address })
      }
    }
  } catch {
    /* ignore */
  }
  return out
}

// ---------------------------------------------------------------- the plugin

/**
 * Mount the bridge.
 * @param ctx - the plugin's cordis context.
 * @param config - live or plain config object.
 */
export function apply(ctx, config) {
  const cfg = defaults(config)
  const statePath = path.join(cfg.stateDir, 'devices.json')

  /** @type {{version:number, devices:Array<object>, auto:Record<string,boolean>}} */
  let store = readStore()
  /** Sessions whose approvals the watch answered with "自动通过". */
  const autoSessions = new Set(Object.keys(store.auto ?? {}))
  let codeState = newCode()
  const touched = new Map()

  /** @type {Map<string, object>} live approval requests */
  const approvals = new Map()
  /** @type {Map<string, object>} live ask_user_question requests */
  const questions = new Map()

  /** @type {Map<string, {at:number, items:any[]}>} */
  const listCache = new Map()

  fs.mkdirSync(cfg.stateDir, { recursive: true })

  function readStore() {
    try {
      const raw = JSON.parse(fs.readFileSync(statePath, 'utf8'))
      const devices = Array.isArray(raw?.devices) ? raw.devices.filter((d) => d && typeof d.tokenHash === 'string') : []
      const auto = raw?.auto && typeof raw.auto === 'object' ? raw.auto : {}
      return { version: 1, devices, auto }
    } catch {
      return { version: 1, devices: [], auto: {} }
    }
  }

  function setAuto(sessionId, on) {
    if (on) autoSessions.add(sessionId)
    else autoSessions.delete(sessionId)
    store.auto = Object.fromEntries([...autoSessions].map((id) => [id, true]))
    writeStore()
  }

  function writeStore() {
    try {
      const tmp = `${statePath}.tmp`
      fs.writeFileSync(tmp, JSON.stringify(store, null, 2), { mode: 0o600 })
      fs.renameSync(tmp, statePath)
    } catch (err) {
      log(cfg, 'warn', '写设备表失败:', String(err).slice(0, 160))
    }
  }

  function newCode() {
    return { code: randomCode(), expiresAt: now() + PAIR_TTL_MS, attempts: 0 }
  }

  function currentCode(rotateIfExpired = true) {
    if (now() > codeState.expiresAt) {
      if (!rotateIfExpired) return null
      codeState = newCode()
    }
    return codeState
  }

  function onlineDevices() {
    const t = now()
    return store.devices.filter((d) => !d.revoked && touched.get(d.id) && t - touched.get(d.id) < ONLINE_MS)
  }

  function authDevice(req) {
    const header = req.headers['authorization']
    let token = ''
    if (typeof header === 'string' && header.startsWith('Bearer ')) token = header.slice(7).trim()
    if (token === '') return undefined
    const hash = sha256(token)
    const device = store.devices.find((d) => !d.revoked && d.tokenHash === hash)
    if (!device) return undefined
    const prev = touched.get(device.id) ?? 0
    touched.set(device.id, now())
    if (now() - prev > 60000) {
      device.lastSeen = now()
      writeStore()
    }
    return device
  }

  function isLocal(req) {
    // Behind a reverse proxy every peer looks like 127.0.0.1, so a forwarded
    // header means "not local" — otherwise the admin routes would be public.
    if (req.headers['x-forwarded-for'] !== undefined || req.headers['x-real-ip'] !== undefined || req.headers['forwarded'] !== undefined) {
      return false
    }
    const addr = req.socket.remoteAddress ?? ''
    if (addr !== '127.0.0.1' && addr !== '::1' && addr !== '::ffff:127.0.0.1') return false
    // DNS rebinding: a browser page on evil.com can resolve to 127.0.0.1 and
    // reach us, but it cannot fake the Host header. Accept only literals.
    const host = String(req.headers.host ?? '')
    const hostname = host.startsWith('[') ? host.slice(1, host.indexOf(']')) : host.split(':')[0]
    if (hostname === '' ) return false
    if (hostname === 'localhost') return true
    return /^[0-9.]+$/.test(hostname) || /^[0-9a-f:]+$/i.test(hostname)
  }

  // ------------------------------------------------------------ host access

  function svc(key) {
    try {
      return ctx.get(key)
    } catch {
      return undefined
    }
  }

  const sessionsQuery = () => svc('sessionQuery')

  function archivedIds() {
    try {
      const registry = svc('workspaceRegistry')
      const ids = registry?.archivedSessionIds
      return new Set(Array.isArray(ids) ? ids.map(String) : [])
    } catch {
      return new Set()
    }
  }
  const sessionController = () => svc('sessionController')

  function liveAgent(sessionId) {
    try {
      const agent = ctx.agents.get(sessionId)
      if (agent) return agent
    } catch {
      /* ignore */
    }
    return undefined
  }

  function isRunning(sessionId) {
    const agent = liveAgent(sessionId)
    return agent?.status === 'running'
  }

  async function listSessions() {
    const cache = listCache.get('all')
    if (cache && now() - cache.at < 1500) return cache.items
    const sq = sessionsQuery()
    /** @type {any[]} */
    let rows = []
    if (sq && typeof sq.listSessions === 'function') {
      try {
        const records = await sq.listSessions()
        const ids = records.map((r) => r.header.id)
        let titles = []
        if (ids.length > 0) {
          try {
            titles = await sq.readTitleSnapshots(ids)
          } catch {
            titles = []
          }
        }
        rows = records.map((record, index) => {
          const settled = titles[index]
          const title = settled?.status === 'fulfilled' ? settled.value?.title?.title : undefined
          return {
            id: String(record.header.id),
            title: clampText(title ?? '', 80),
            createdAt: Number(record.header.createdAt ?? 0),
            cwd: record.header.cwd,
          }
        })
      } catch (err) {
        log(cfg, 'debug', 'listSessions 失败:', String(err).slice(0, 160))
      }
    }
    if (rows.length === 0) {
      try {
        rows = ctx.agents.list().map((agent) => ({
          id: String(agent.id),
          title: '',
          createdAt: 0,
          cwd: agent.session?.header?.cwd,
        }))
      } catch {
        rows = []
      }
    }
    const archived = archivedIds()
    const items = rows.map((row) => {
      const running = isRunning(row.id)
      const live = liveAgent(row.id) !== undefined
      const pending = pendingFor(row.id)
      return {
        id: row.id,
        title: row.title || (row.cwd ? path.basename(row.cwd) : row.id.slice(0, 8)),
        running,
        live,
        pending,
        archived: archived.has(String(row.id)),
        createdAt: row.createdAt,
        cwd: row.cwd,
      }
    })
    // An archived id whose session the session list does not return would vanish
    // from the watch entirely. Keep it, with a best-effort title, so the "已归档"
    // group always matches the archive.
    const listed = new Set(items.map((item) => item.id))
    const missing = [...archived].filter((id) => !listed.has(id))
    if (missing.length > 0) {
      const titles = await readTitlesBestEffort(missing)
      for (const id of missing) {
        items.push({
          id,
          title: titles.get(id) || id.slice(0, 8),
          running: false,
          live: false,
          pending: 0,
          archived: true,
          createdAt: 0,
          missing: true,
        })
      }
    }
    items.sort((a, b) => Number(a.archived) - Number(b.archived) || rank(b) - rank(a) || b.createdAt - a.createdAt)
    listCache.set('all', { at: now(), items })
    return items
  }

  async function readTitlesBestEffort(ids) {
    /** @type {Map<string,string>} */
    const out = new Map()
    const sq = sessionsQuery()
    for (const id of ids) {
      try {
        const title = await sq?.readTitle?.(String(id))
        const text = typeof title === 'string' ? title : title?.title ?? title?.value
        if (typeof text === 'string' && text !== '') out.set(id, clampText(text, 80))
      } catch {
        /* gone or unreadable: keep the short id as the label */
      }
    }
    return out
  }

  function rank(row) {
    return (row.pending > 0 ? 100 : 0) + (row.running ? 10 : 0) + (row.live ? 1 : 0)
  }

  function pendingFor(sessionId) {
    let count = 0
    for (const entry of approvals.values()) if (entry.sessionId === sessionId && !entry.settled) count += 1
    for (const entry of questions.values()) if (entry.sessionId === sessionId && !entry.settled) count += 1
    return count
  }

  async function readMessages(sessionId, limit = 10) {
    const sq = sessionsQuery()
    if (!sq || typeof sq.readSurface !== 'function') return { messages: [], activity: '' }
    let surface
    try {
      surface = await sq.readSurface(sessionId)
    } catch (err) {
      log(cfg, 'debug', 'readSurface 失败:', String(err).slice(0, 160))
      return { messages: [], activity: '' }
    }
    const events = Array.isArray(surface?.events) ? surface.events : []
    const messages = []
    let activity = ''
    for (const event of events) {
      const type = event?.type
      if (type === 'user/message') {
        const text = clampText(blocksText(event.data?.content), MESSAGE_CAP)
        if (text !== '') messages.push({ role: 'u', text, at: event.time })
      } else if (type === 'assistant/message') {
        const text = clampText(blocksText(event.data?.message?.content), MESSAGE_CAP)
        if (text !== '') messages.push({ role: 'a', text, at: event.time })
      } else if (type === 'tool/call') {
        activity = clampText(String(event.data?.name ?? ''), 60)
      }
    }
    return { messages: messages.slice(-limit), activity }
  }

  async function commandForCall(sessionId, callId) {
    const sq = sessionsQuery()
    if (!sq || typeof sq.readSurface !== 'function' || callId === undefined) return ''
    try {
      const surface = await sq.readSurface(sessionId)
      const events = Array.isArray(surface?.events) ? surface.events : []
      for (let index = events.length - 1; index >= 0; index -= 1) {
        const event = events[index]
        if (event?.type === 'tool/call' && String(event.data?.callId) === String(callId)) {
          return clampText(String(event.data?.arguments ?? ''), COMMAND_CAP)
        }
      }
    } catch {
      /* ignore */
    }
    return ''
  }

  async function sessionTitle(sessionId) {
    const rows = await listSessions()
    return rows.find((row) => row.id === sessionId)?.title ?? sessionId.slice(0, 8)
  }

  // -------------------------------------------------------- the relays

  if (cfg.relayApprovals) {
    ctx.on('approval/request', (req, next) => relayApproval(req, next))
  }
  if (cfg.relayQuestions) {
    ctx.on('user-questions/request', (req, next) => relayQuestion(req, next))
  }

  async function relayApproval(req, next) {
    const autoSession = String(req.agent?.id ?? '')
    if (autoSession !== '' && autoSessions.has(autoSession)) {
      log(cfg, 'info', `审批自动通过 ${clampText(req.toolName ?? 'tool', 40)} @ ${autoSession.slice(0, 8)}`)
      return 'allowed-once'
    }
    let devices = []
    try {
      devices = onlineDevices()
    } catch {
      devices = []
    }
    if (devices.length === 0) return next()

    const id = crypto.randomUUID()
    const sessionId = autoSession
    const entry = {
      kind: 'approval',
      id,
      sessionId,
      toolName: clampText(req.toolName ?? 'tool', 60),
      reason: clampText(req.reason ?? '', 200),
      command: await commandForCall(sessionId, req.callId),
      at: now(),
      settled: false,
      resolve: null,
    }
    entry.promise = new Promise((resolve) => {
      entry.resolve = resolve
    })
    approvals.set(id, entry)
    log(cfg, 'info', `审批请求 ${entry.toolName} @ ${sessionId.slice(0, 8)} → ${devices.length} 台设备`)

    const nextPromise = Promise.resolve().then(() => next()).catch(() => 'unavailable')
    try {
      const first = await Promise.race([
        entry.promise.then((outcome) => ({ from: 'watch', outcome })),
        nextPromise.then((outcome) => ({ from: 'web', outcome })),
      ])
      if (first.from === 'watch') {
        log(cfg, 'info', `审批由手表决定: ${first.outcome}`)
        return first.outcome
      }
      if (first.outcome !== 'unavailable') return first.outcome
      // No browser answerer: give the watch its own window before failing closed.
      const waited = await withTimeout(entry.promise, cfg.approvalWaitMs)
      return waited === TIMEOUT ? 'unavailable' : waited
    } finally {
      entry.settled = true
      approvals.delete(id)
      entry.resolve = entry.resolve ?? (() => {})
    }
  }

  async function relayQuestion(req, next) {
    let devices = []
    try {
      devices = onlineDevices()
    } catch {
      devices = []
    }
    let nextError = null
    const nextPromise = Promise.resolve()
      .then(() => next())
      .catch((err) => {
        nextError = err
        return undefined
      })
    if (devices.length === 0) {
      const answer = await nextPromise
      if (nextError !== null) throw nextError
      return answer
    }

    const id = crypto.randomUUID()
    const sessionId = String(req.agent?.id ?? '')
    const entry = {
      kind: 'question',
      id,
      sessionId,
      questions: (req.questions ?? []).map((question) => ({
        id: String(question.id ?? ''),
        question: clampText(question.question ?? '', 160),
        detail: clampText(question.detail ?? '', 160),
        options: (question.options ?? []).map((option) => ({
          label: clampText(option.label ?? '', 60),
          description: clampText(option.description ?? '', 120),
        })),
        multiSelect: question.multiSelect === true,
        intent: question.intent?.kind,
      })),
      at: now(),
      settled: false,
      resolve: null,
    }
    entry.promise = new Promise((resolve) => {
      entry.resolve = resolve
    })
    questions.set(id, entry)
    log(cfg, 'info', `提问 @ ${sessionId.slice(0, 8)} → ${devices.length} 台设备`)

    try {
      const first = await Promise.race([
        entry.promise.then((answer) => ({ from: 'watch', answer })),
        nextPromise.then((answer) => ({ from: 'web', answer })),
      ])
      if (first.from === 'watch') return first.answer
      if (first.answer !== undefined) return first.answer
      const waited = await withTimeout(entry.promise, cfg.questionWaitMs)
      if (waited === TIMEOUT) {
        if (nextError !== null) throw nextError
        throw new Error('ask_user_question: 手表没在时限内回答，网页端也没有接收者')
      }
      return waited
    } finally {
      entry.settled = true
      questions.delete(id)
    }
  }

  const TIMEOUT = Symbol('timeout')
  function withTimeout(promise, ms) {
    return Promise.race([promise, new Promise((resolve) => setTimeout(() => resolve(TIMEOUT), ms).unref?.())])
  }

  // ------------------------------------------------------------- http api

  async function statePayload(url) {
    const sessions = await listSessions()
    const wanted = url.searchParams.get('session') ?? ''
    const limit = Math.max(1, Math.min(30, Number(url.searchParams.get('limit') ?? 8) || 8))
    let session = null
    // An explicit request wins even when the id is unknown: never substitute a
    // different session, or the watch ends up showing one conversation twice.
    const target = wanted !== ''
      ? wanted
      : sessions.find((row) => row.pending > 0)?.id || sessions.find((row) => row.running)?.id || sessions[0]?.id
    if (target) {
      const { messages, activity } = await readMessages(target, limit)
      const row = sessions.find((item) => item.id === target)
      let policy = null
      try {
        const agent = liveAgent(target)
        const approval = svc('approval')
        if (agent && approval) policy = approval.overrideOf(agent.session) ?? 'ask'
      } catch {
        policy = null
      }
      session = {
        id: target,
        title: row?.title ?? target.slice(0, 8),
        auto: autoSessions.has(target),
        running: row?.running === true,
        live: row?.live === true,
        activity,
        policy,
        messages,
        pending: [...approvals.values(), ...questions.values()]
          .filter((entry) => entry.sessionId === target && !entry.settled)
          .map(publicPending),
      }
    }
    return {
      ok: true,
      protocol: PROTOCOL,
      version: VERSION,
      server: { name: cfg.name, host: os.hostname() },
      now: now(),
      sessions,
      session,
    }
  }

  function publicPending(entry) {
    if (entry.kind === 'approval') {
      return {
        kind: 'approval',
        id: entry.id,
        session: entry.sessionId,
        tool: entry.toolName,
        reason: entry.reason,
        command: entry.command,
        at: entry.at,
      }
    }
    return {
      kind: 'question',
      id: entry.id,
      session: entry.sessionId,
      questions: entry.questions,
      at: entry.at,
    }
  }

  function requireAuth(req, res) {
    const device = authDevice(req)
    if (device === undefined) {
      json(res, 401, { ok: false, error: 'unauthorized' })
      return undefined
    }
    return device
  }

  const handleRequest = async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost')
    const route = `${req.method} ${url.pathname}`
    try {
      if (route === 'GET /' || route === 'GET /index.html') {
        res.writeHead(302, { location: '/watch' })
        res.end()
        return
      }
      if (route === 'GET /watch') {
        sendWatchPage(res, req)
        return
      }
      if (route === 'GET /health') {
        json(res, 200, { ok: true, version: VERSION, devices: store.devices.length, startedAt: STARTED_AT, codeExpiresAt: codeState.expiresAt })
        return
      }
      if (route === 'GET /api/hello') {
        json(res, 200, {
          ok: true,
          protocol: PROTOCOL,
          version: VERSION,
          server: { name: cfg.name },
          paired: store.devices.some((d) => !d.revoked),
        })
        return
      }
      if (route === 'GET /api/selftest') {
        if (!isLocal(req)) {
          json(res, 403, { ok: false, error: 'localhost only' })
          return
        }
        const live = []
        try {
          for (const agent of ctx.agents.list()) {
            let policy = null
            try {
              policy = svc('approval')?.overrideOf(agent.session) ?? null
            } catch (err) {
              policy = `err:${String(err).slice(0, 60)}`
            }
            live.push({ id: String(agent.id), status: agent.status, policy })
          }
        } catch (err) {
          live.push({ error: String(err).slice(0, 120) })
        }
        json(res, 200, {
          ok: true,
          services: { sessionQuery: sessionsQuery() !== undefined, sessionController: sessionController() !== undefined },
          live,
        })
        return
      }
      if (route === 'POST /api/pair') {
        const body = await readBody(req)
        const state = currentCode()
        if (body.code !== undefined && String(body.code) !== state.code) {
          state.attempts += 1
          if (state.attempts >= PAIR_MAX_ATTEMPTS) {
            codeState = newCode()
            log(cfg, 'warn', '配对码错误次数过多，已作废')
          }
          json(res, 403, { ok: false, error: '配对码不对' })
          return
        }
        const token = randomToken()
        // Same watch re-pairing replaces its old entry instead of piling up.
        const claimedId = clampText(String(body.id ?? ''), 40)
        const existing = claimedId === '' ? undefined : store.devices.find((d) => d.id === claimedId)
        let device
        if (existing !== undefined) {
          existing.tokenHash = sha256(token)
          existing.name = clampText(String(body.name ?? existing.name), 40) || existing.name
          existing.lastSeen = now()
          existing.revoked = false
          device = existing
        } else {
          device = {
            id: claimedId !== '' ? claimedId : crypto.randomUUID().slice(0, 8),
            name: clampText(String(body.name ?? 'watch'), 40) || 'watch',
            tokenHash: sha256(token),
            createdAt: now(),
            lastSeen: now(),
          }
          store.devices.push(device)
        }
        writeStore()
        touched.set(device.id, now())
        codeState = newCode()
        log(cfg, 'info', `新设备配对: ${device.name} (${device.id})`)
        json(res, 200, {
          ok: true,
          token,
          deviceId: device.id,
          server: { name: cfg.name, version: VERSION },
          protocol: PROTOCOL,
        })
        return
      }

      if (route === 'POST /api/devices/revoke') {
        if (!isLocal(req) && authDevice(req) === undefined) {
          json(res, 403, { ok: false, error: 'unauthorized' })
          return
        }
        const body = await readBody(req)
        const id = String(body.id ?? '')
        const before = store.devices.length
        store.devices = store.devices.filter((device) => device.id !== id)
        writeStore()
        touched.delete(id)
        log(cfg, 'info', `撤销设备 ${id}`)
        json(res, 200, { ok: true, removed: before - store.devices.length })
        return
      }

      if (route === 'POST /api/debug/approval') {
        if (!cfg.allowDebug || !isLocal(req)) {
          json(res, 404, { ok: false, error: 'debug disabled' })
          return
        }
        const body = await readBody(req)
        const rows = await listSessions()
        const sessionId = String(body.session ?? rows[0]?.id ?? '')
        const entry = {
          kind: 'approval',
          id: crypto.randomUUID(),
          sessionId,
          toolName: clampText(body.tool ?? 'Bash', 40),
          reason: clampText(body.reason ?? '模拟：手表上点一下，看看卡片长什么样', 120),
          command: clampText(body.command ?? 'npm run build -- --watch', 200),
          at: now(),
          settled: false,
          resolve: () => {},
        }
        entry.promise = new Promise(() => {})
        approvals.set(entry.id, entry)
        setTimeout(() => {
          if (!entry.settled) {
            entry.settled = true
            approvals.delete(entry.id)
          }
        }, 90000).unref?.()
        log(cfg, 'info', `模拟审批 ${entry.id}`)
        json(res, 200, { ok: true, id: entry.id, session: sessionId })
        return
      }

      // Everything below needs a device token.
      if (url.pathname.startsWith('/api/') && requireAuth(req, res) === undefined) return

      if (route === 'GET /api/devices') {
        const me = requireAuth(req, res)
        if (me === undefined) return
        json(res, 200, {
          ok: true,
          current: me.id,
          devices: store.devices
            .filter((d) => !d.revoked)
            .map((d) => ({ id: d.id, name: d.name, lastSeen: d.lastSeen, current: d.id === me.id })),
        })
        return
      }

      if (route === 'GET /api/state') {
        json(res, 200, await statePayload(url))
        return
      }
      if (route === 'GET /api/sessions') {
        json(res, 200, { ok: true, sessions: await listSessions() })
        return
      }
      if (route === 'GET /api/pending') {
        json(res, 200, {
          ok: true,
          pending: [...approvals.values(), ...questions.values()].filter((e) => !e.settled).map(publicPending),
        })
        return
      }
      if (route === 'POST /api/approve') {
        const body = await readBody(req)
        const entry = approvals.get(String(body.id ?? ''))
        if (!entry || entry.settled) {
          json(res, 404, { ok: false, error: '这条审批已经不在了' })
          return
        }
        entry.settled = true
        entry.resolve(body.allow === true ? 'allowed-once' : 'rejected')
        json(res, 200, { ok: true })
        return
      }
      if (route === 'POST /api/answer') {
        const body = await readBody(req)
        const entry = questions.get(String(body.id ?? ''))
        if (!entry || entry.settled) {
          json(res, 404, { ok: false, error: '这条提问已经不在了' })
          return
        }
        entry.settled = true
        entry.resolve({ answers: Array.isArray(body.answers) ? body.answers : [] })
        json(res, 200, { ok: true })
        return
      }
      if (route === 'POST /api/say') {
        const body = await readBody(req)
        const sessionId = String(body.session ?? '')
        const text = clampText(String(body.text ?? ''), 2000)
        if (sessionId === '' || text === '') {
          json(res, 400, { ok: false, error: '缺少会话或内容' })
          return
        }
        const sent = await say(sessionId, text)
        json(res, sent.ok ? 200 : 400, sent)
        return
      }
      if (route === 'POST /api/stop') {
        const body = await readBody(req)
        const sessionId = String(body.session ?? '')
        const controller = sessionController()
        if (!controller || typeof controller.cancel !== 'function') {
          json(res, 400, { ok: false, error: '这个 DSH 没有 sessionController' })
          return
        }
        try {
          controller.cancel({ sessionId })
          json(res, 200, { ok: true })
        } catch (err) {
          json(res, 400, { ok: false, error: String(err).slice(0, 160) })
        }
        return
      }
      if (route === 'POST /api/policy') {
        const body = await readBody(req)
        const sessionId = String(body.session ?? '')
        const policy = body.policy === 'never' ? 'never' : body.policy === 'ask' ? 'ask' : undefined
        const agent = liveAgent(sessionId)
        if (policy === undefined || agent === undefined) {
          json(res, 400, { ok: false, error: '会话没开着，或者 policy 只能是 ask / never' })
          return
        }
        const approval = svc('approval')
        if (!approval) {
          json(res, 400, { ok: false, error: '这个 DSH 没有 approval 服务' })
          return
        }
        approval.setPolicy(agent, policy)
        json(res, 200, { ok: true, policy })
        return
      }
      if (route === 'POST /api/auto') {
        const body = await readBody(req)
        const sessionId = String(body.session ?? '')
        if (sessionId === '') {
          json(res, 400, { ok: false, error: '缺少会话' })
          return
        }
        const on = body.on === true
        setAuto(sessionId, on)
        log(cfg, 'info', `审批自动通过 ${on ? '打开' : '关闭'} @ ${sessionId.slice(0, 8)}`)
        json(res, 200, { ok: true, auto: on })
        return
      }
      if (route === 'POST /api/new') {
        const body = await readBody(req)
        const controller = sessionController()
        if (!controller || typeof controller.create !== 'function') {
          json(res, 400, { ok: false, error: '这个 DSH 不能新建会话' })
          return
        }
        let cwd = clampText(String(body.cwd ?? ''), 300)
        if (cwd === '') {
          const rows = await listSessions()
          cwd = String(rows.find((row) => row.cwd)?.cwd ?? '')
        }
        if (cwd === '') cwd = process.cwd()
        try {
          const created = await controller.create({ cwd })
          const id = String(created?.sessionId ?? created?.id ?? '')
          listCache.clear()
          log(cfg, 'info', `新建会话 ${id.slice(0, 8)} @ ${cwd}`)
          json(res, 200, { ok: true, session: id })
        } catch (err) {
          json(res, 400, { ok: false, error: String(err?.message ?? err).slice(0, 160) })
        }
        return
      }
      if (route === 'POST /api/delete') {
        const body = await readBody(req)
        const sessionId = String(body.session ?? '')
        if (sessionId === '') {
          json(res, 400, { ok: false, error: '缺少会话' })
          return
        }
        const seam = svc('sessionPersistence')?.dshGoDeletion
        if (!seam || typeof seam.delete !== 'function') {
          json(res, 400, { ok: false, error: '这个 DSH 不支持删除会话' })
          return
        }
        if (liveAgent(sessionId) !== undefined) {
          json(res, 409, { ok: false, error: '这个会话还开着，等它停下再删' })
          return
        }
        try {
          await seam.delete(sessionId, {})
          approvals.forEach((entry) => {
            if (entry.sessionId === sessionId) entry.settled = true
          })
          autoSessions.delete(sessionId)
          store.auto = Object.fromEntries([...autoSessions].map((id) => [id, true]))
          writeStore()
          listCache.clear()
          log(cfg, 'info', `删除会话 ${sessionId.slice(0, 8)}`)
          json(res, 200, { ok: true })
        } catch (err) {
          const code = String(err?.code ?? '')
          const reason =
            code === 'session-active'
              ? '这个会话还有任务在跑，先在网页上停掉'
              : code === 'session-related'
                ? '这个会话带着子会话，先在网页上删'
                : String(err?.message ?? err).slice(0, 160)
          json(res, 409, { ok: false, error: reason })
        }
        return
      }
      if (route === 'GET /dsh-watch.apk') {
        sendApk(res)
        return
      }
      json(res, 404, { ok: false, error: 'not found' })
    } catch (err) {
      log(cfg, 'warn', `${route} 出错:`, String(err).slice(0, 200))
      if (!res.headersSent) json(res, 500, { ok: false, error: String(err).slice(0, 200) })
    }
  }

  let tls
  try {
    if (cfg.tlsCert !== undefined && cfg.tlsKey !== undefined) {
      tls = { cert: fs.readFileSync(cfg.tlsCert), key: fs.readFileSync(cfg.tlsKey) }
    }
  } catch (err) {
    log(cfg, 'error', 'TLS 证书读取失败，退回明文 HTTP:', String(err).slice(0, 160))
  }
  const server = tls === undefined ? http.createServer(handleRequest) : https.createServer(tls, handleRequest)

  async function say(sessionId, text) {
    const controller = sessionController()
    if (!controller || typeof controller.prompt !== 'function') {
      return { ok: false, error: '这个 DSH 没有 sessionController' }
    }
    const running = isRunning(sessionId)
    try {
      await controller.prompt(
        {
          requestId: crypto.randomUUID(),
          sessionId,
          mode: running ? 'steer' : 'queue',
          content: [{ type: 'text', text }],
        },
        new AbortController().signal,
      )
      return { ok: true, mode: running ? 'steer' : 'queue' }
    } catch (err) {
      return { ok: false, error: String(err).slice(0, 200) }
    }
  }

  function sendWatchPage(res, req) {
    // The pairing code and the device list are admin material: only a real
    // loopback caller gets them. Everyone else gets a stub page, because this
    // port may sit on a public address.
    if (!isLocal(req)) {
      const html = `<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH on wear</title>
<style>
 body{font:15px/1.6 system-ui,-apple-system,"PingFang SC",sans-serif;margin:0;padding:22px;background:#0e1116;color:#e6e9ef}
 h1{font-size:18px;margin:0 0 6px} .muted{color:#9aa4b2;font-size:13px} code{background:#1a2028;padding:1px 6px;border-radius:5px}
 a{color:#7cc4ff}
</style>
<h1>DSH on wear</h1>
<div class="muted">配对页只在本机（127.0.0.1）打开时才显示配对码。</div>
<p class="muted">在 DSH 对话里说「给我手表配对码」，助手会调用 <code>watch_pair_code</code> 发给你。</p>
<p class="muted">或者先把端口隧道到本地再打开这个页面。</p>
<p><a href="/dsh-watch.apk">下载手表 App</a></p>`
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
      res.end(html)
      return
    }
    const port = cfg.port
    const addrs = localAddresses()
    const rows = addrs
      .map((entry) => `<li><code>http://${entry.address}:${port}</code> <span>${entry.iface}</span></li>`)
      .join('')
    const local = isLocal(req)
    const devices = store.devices
      .filter((d) => !d.revoked)
      .map((d) => `<li>${d.name} · ${d.id} · 最近 ${new Date(touched.get(d.id) ?? d.lastSeen).toLocaleString()}`
        + (local ? ` · <a href="#" onclick="revoke('${d.id}');return false">撤销</a>` : '')
        + '</li>')
      .join('')
    const html = `<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH on wear 配对</title>
<style>
 body{font:15px/1.5 system-ui,-apple-system,"PingFang SC",sans-serif;margin:0;padding:20px;background:#0e1116;color:#e6e9ef}
 h1{font-size:18px;margin:0 0 4px} h2{font-size:14px;color:#9aa4b2;margin:22px 0 8px;font-weight:600}
 .code{font:700 44px/1.1 ui-monospace,Menlo,monospace;letter-spacing:8px;color:#ffd166;margin:6px 0 2px}
 .sub{color:#9aa4b2;font-size:13px} ul{padding-left:18px;margin:6px 0;color:#c6ccd7} li{margin:2px 0}
 code{background:#1a2028;padding:1px 6px;border-radius:5px;font-size:13px}
 .card{background:#151b23;border:1px solid #1f2731;border-radius:12px;padding:14px 16px;margin:10px 0}
 a{color:#7cc4ff}
</style>
<h1>DSH on wear</h1>
<div class="sub">${cfg.name} · v${VERSION} · 已配对 ${store.devices.filter((d) => !d.revoked).length} 台</div>

<div class="card">
  <h2 style="margin-top:0">配对码</h2>
  <div class="sub">配对码不在网页上显示。在 DSH 对话里说「给我手表配对码」，助手会调用 <code>watch_pair_code</code> 发给你（10 分钟有效）。</div>
</div>

<div class="card">
  <h2 style="margin-top:0">手表连接地址</h2>
  <ul>${rows || '<li>没有检测到局域网地址</li>'}</ul>
  <div class="sub">手表上填这个地址 + 上面的配对码。公网访问需要在服务器安全组放行 ${port} 端口。</div>
</div>

<div class="card">
  <h2 style="margin-top:0">DSH on wear APK</h2>
  <div class="sub">下载后 <code>adb install</code>：<a href="/dsh-watch.apk">dsh-watch.apk</a></div>
</div>

<div class="card">
  <h2 style="margin-top:0">已配对设备（${store.devices.filter((d) => !d.revoked).length}）</h2>
  <ul>${devices || '<li>还没有设备</li>'}</ul>
</div>
<script>
function revoke(id){fetch('/api/devices/revoke',{method:'POST',body:JSON.stringify({id})}).then(r=>r.json()).then(()=>location.reload())}
setTimeout(()=>location.reload(), 20000)
</script>`
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
    res.end(html)
  }

  function sendApk(res) {
    const apk = path.join(new URL('.', import.meta.url).pathname, '..', 'dist', 'dsh-watch.apk')
    try {
      const stat = fs.statSync(apk)
      res.writeHead(200, {
        'content-type': 'application/vnd.android.package-archive',
        'content-length': stat.size,
        'content-disposition': 'attachment; filename="dsh-watch.apk"',
      })
      fs.createReadStream(apk).pipe(res)
    } catch {
      json(res, 404, { ok: false, error: '没有找到 APK' })
    }
  }

  // Cheap slowloris protection: a stuck client cannot hold a socket forever.
  server.headersTimeout = 15000
  server.requestTimeout = 30000
  server.keepAliveTimeout = 5000

  server.on('error', (err) => {
    log(cfg, 'error', `监听 ${cfg.host}:${cfg.port} 失败:`, String(err).slice(0, 200))
  })

  server.listen(cfg.port, cfg.host, () => {
    log(cfg, 'info', `已启动 ${tls === undefined ? 'http' : 'https'}://${cfg.host}:${cfg.port}`)
    log(cfg, 'info', '配对码不打印：在对话里让助手调用 watch_pair_code 工具获取')
    log(cfg, 'info', `手表地址: ${localAddresses().map((a) => `http://${a.address}:${cfg.port}`).join('  ') || '(无局域网地址)'}`)
  })

  // ------------------------------------------------------- the pairing tool
  // The code is never printed or served over HTTP: the only way to get it is
  // to ask the agent, which calls this tool.

  {
    const toolsService = svc('tools')
    if (toolsService === undefined) {
      log(cfg, 'warn', 'tools 服务不存在，watch_pair_code 未注册')
    } else {
      // Hand-written descriptor (the same shape `defineTool` produces) so the
      // plugin has no import of @deepseek-ai/dsh-tools and works from any path.
      const pairTool = {
        name: 'watch_pair_code',
        description:
          'Get a one-time pairing code (10 minutes) for the DSH on wear watch app, plus the server addresses the watch can use. '
          + 'Call this ONLY when the user asks for the pairing code — the code is a secret, never show it on your own.',
        parameters: { type: 'object', properties: {}, additionalProperties: false },
        output: {
          schema: {
            type: 'object',
            additionalProperties: false,
            required: ['code', 'expiresAt', 'text'],
            properties: {
              code: { type: 'string' },
              expiresAt: { type: 'number' },
              text: { type: 'string' },
            },
          },
          render: (_args, value) => [{ type: 'text', text: String(value?.text ?? '') }],
        },
        async execute() {
          // Reuse the live code while it still has some time left, otherwise mint one.
          if (now() > codeState.expiresAt - 2 * 60 * 1000) codeState = newCode()
          const port = cfg.port
          const addrs = localAddresses()
          const lines = [
            `配对码：${codeState.code}`,
            `有效期：${new Date(codeState.expiresAt).toLocaleTimeString()}（10 分钟内有效）`,
            '',
            '手表上填的地址（同一个网络才能用）：',
            ...(addrs.length === 0 ? ['  （没检测到局域网地址）'] : addrs.map((entry) => `  http://${entry.address}:${port}`)),
            '',
            `一条命令配对：adb shell am start -n io.dsh.watch/.MainActivity --es url http://${addrs[0]?.address ?? '127.0.0.1'}:${port} --es code ${codeState.code}`,
          ]
          return { code: codeState.code, expiresAt: codeState.expiresAt, text: lines.join('\n') }
        },
      }
      try {
        ctx.effect(() => toolsService.register(pairTool))
        log(cfg, 'info', 'watch_pair_code 已注册：配对码只能通过对话获取')
      } catch (err) {
        // Without the tool the code would be unreachable, so print it once as a
        // last resort instead of locking the user out.
        log(cfg, 'error', 'watch_pair_code 注册失败:', String(err).slice(0, 200))
        log(cfg, 'error', `临时配对码（请报告这个错误）：${currentCode()?.code ?? '-'}`)
      }
    }
  }

  // Nudge the model to hand the code over only when asked.
  const promptService = svc('systemPrompt')
  if (promptService !== undefined) {
    try {
      ctx.effect(() =>
        promptService.section({
          name: 'watch-bridge',
          order: 78,
          text: () =>
            'DSH on wear（手表端）：用户问「手表配对码」时，调用 watch_pair_code 工具，把返回的配对码和地址原样告诉用户。'
            + '配对码是密钥，不要主动展示、不要写进文件或截图。',
        }),
      )
    } catch (err) {
      log(cfg, 'warn', '提示段落注册失败（不影响使用）:', String(err).slice(0, 160))
    }
  }

  // Cordis disposes the listener when the plugin unloads.
  ctx.effect(() => () => {
    try {
      server.close()
    } catch {
      /* ignore */
    }
  })

  // A page in the web UI at /watch when this profile has a web server.
  ctx.inject(['webServer'], (scoped) => {
    try {
      scoped.effect(
        () =>
          scoped.webServer.register({
            kind: 'exact',
            path: '/watch',
            handler(req, res) {
              sendWatchPage(res, req)
            },
          }),
        'watch-bridge: /watch page',
      )
    } catch (err) {
      // A reloaded plugin can collide with its own previous route; the bridge's
      // own :port/watch page is the one that always works.
      log(cfg, 'debug', '注册 /watch 路由失败（可忽略）:', String(err).slice(0, 120))
    }
  })

  // Keep the console code current even if nobody opens the page.
  const timer = setInterval(() => {
    if (now() > codeState.expiresAt) {
      codeState = newCode()
      log(cfg, 'info', '配对码已过期，需要时让助手调用 watch_pair_code')
    }
  }, 60000)
  timer.unref?.()
  ctx.effect(() => () => clearInterval(timer))
}
