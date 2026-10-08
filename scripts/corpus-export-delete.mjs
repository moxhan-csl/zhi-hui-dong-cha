// 导出/清理语料：先只读导出正文，确认拿到后再删。用法（PowerShell）：
//   $env:ZD_BASE='http://127.0.0.1:8080/api'; $env:ZD_ADMIN='…'; $env:ZD_PASS='…'
//   node scripts/corpus-export-delete.mjs export
//   node scripts/corpus-export-delete.mjs delete --dry-run   # 只列要删什么，不动手
//   node scripts/corpus-export-delete.mjs delete             # 需手输 delete-all 确认
//   node scripts/corpus-export-delete.mjs delete --yes       # 免交互（给自动化用，慎用）
// 目标地址**没有缺省值**（D-27）：不带 ZD_BASE 直接退出，避免"忘了设变量就把生产删空"。
const BASE = process.env.ZD_BASE
const ACCOUNT = process.env.ZD_ADMIN || 'admin@corp.com'
const PASSWORD = process.env.ZD_PASS
if (!BASE) {
  console.error('需要 ZD_BASE（例如 http://127.0.0.1:8080/api）。本脚本不预设任何目标地址。')
  process.exit(2)
}
if (!PASSWORD) {
  console.error('需要 ZD_PASS：口令只从环境变量来，脚本不落任何凭据字面值。')
  process.exit(2)
}
const OUT = new URL('../corpus-backup.jsonl', import.meta.url)

let token = ''

async function api(path, opts = {}) {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(opts.headers || {}) },
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${res.status} ${opts.method || 'GET'} ${path}: ${text.slice(0, 200)}`)
  return text ? JSON.parse(text) : null
}

async function login() {
  const r = await api('/auth/login', { method: 'POST', body: JSON.stringify({ account: ACCOUNT, password: PASSWORD }) })
  token = r.token
  return r.user
}

async function allDocs() {
  const out = []
  for (let page = 1; page <= 20; page++) {
    const res = await api(`/documents?page=${page}&size=50`)
    out.push(...res.items)
    if (out.length >= res.total || !res.items.length) break
  }
  return out
}

async function allChunks(id) {
  const out = []
  for (let page = 1; page <= 200; page++) {
    const res = await api(`/documents/${id}/chunks?page=${page}`)
    out.push(...res)
    if (res.length < 10) break
  }
  return out
}

const fs = await import('node:fs/promises')

async function doExport() {
  const user = await login()
  console.log('logged in as', user.account, user.role)
  const docs = await allDocs()
  console.log(`docs=${docs.length}`)
  const lines = []
  for (const d of docs) {
    const chunks = await allChunks(d.id)
    lines.push(JSON.stringify({ doc: { id: d.id, name: d.name, type: d.type, kbName: d.kbName, chunkCount: d.chunkCount }, chunks }))
    console.log(`  ${d.kbName} / ${d.name}: ${chunks.length} chunks (declared ${d.chunkCount})`)
  }
  const outPath = OUT.pathname.replace(/^\//, '')
  await fs.writeFile(outPath, lines.join('\n') + '\n')
  const size = (await fs.stat(outPath)).size
  console.log(`written -> ${outPath} (${size} bytes)`)
}

async function doDelete({ dryRun, yes }) {
  const stat = await fs.stat(OUT).catch(() => null)
  if (!stat || stat.size < 1000) throw new Error('未找到正文备份 corpus-backup.jsonl，拒绝删除')
  const user = await login()
  console.log('logged in as', user.account, user.role, '→', BASE)
  const docs = await allDocs()
  const kbs = await api('/knowledge-bases')
  console.log(`将删除 ${docs.length} 篇文档、${kbs.length} 个知识库（机密库删除会自动补 confirm=true）：`)
  for (const d of docs) console.log(`  doc  ${d.kbName} / ${d.name}`)
  for (const k of kbs) console.log(`  kb   ${k.name}(${k.scope})`)
  if (dryRun) {
    console.log('--dry-run：只列出目标，未执行任何删除')
    return
  }
  if (!yes) {
    const { createInterface } = await import('node:readline/promises')
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    const ans = await rl.question('以上删除不可恢复。输入 delete-all 继续：')
    rl.close()
    if (ans.trim() !== 'delete-all') {
      console.log('未确认，退出，未删除任何东西')
      return
    }
  }
  for (const d of docs) {
    await api(`/documents/${d.id}`, { method: 'DELETE' })
    console.log(`  deleted doc ${d.name}`)
  }
  for (const k of kbs) {
    try {
      await api(`/knowledge-bases/${k.id}`, { method: 'DELETE' })
    } catch (e) {
      // 机密库需要二次确认（428），带上 confirm=true 重试
      if (!String(e.message).startsWith('428')) throw e
      await api(`/knowledge-bases/${k.id}?confirm=true`, { method: 'DELETE' })
    }
    console.log(`  deleted kb ${k.name}`)
  }
  const after = await api('/knowledge-bases')
  const left = await api('/documents?size=1')
  const health = await api('/health')
  console.log('remaining kbs=', after.length, 'docs=', left.total, 'health=', JSON.stringify(health))
}

const flags = process.argv.slice(3)
const mode = process.argv[2]
if (mode === 'export') await doExport()
else if (mode === 'delete') await doDelete({ dryRun: flags.includes('--dry-run'), yes: flags.includes('--yes') })
else console.log('usage: node scripts/corpus-export-delete.mjs export | delete [--dry-run|--yes]')
