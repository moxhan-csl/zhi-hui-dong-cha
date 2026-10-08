#!/usr/bin/env node
/**
 * 智汇洞察验收测试套件
 * AC-1~AC-6 的判据口径见 docs/功能设计文档.md §11（含每条判据的代码出处与实测坑）。
 *
 *   node scripts/acceptance/run.mjs                 # AC-1~AC-5（会真实调用 LLM）
 *   node scripts/acceptance/run.mjs --eval          # 追加 AC-6 全量评测（30 条，产生费用）
 *   node scripts/acceptance/run.mjs --only=AC-1,AC-4
 *   node scripts/acceptance/run.mjs --bootstrap     # 缺失的 km/emp 种子账号由 admin 自动创建
 *   node scripts/acceptance/run.mjs --keep          # 保留测试期建的知识库/文档/会话
 *
 * 依赖：Node 18+（fetch/FormData/Blob）。后端需已在 --base 上运行。
 * 凭据只从环境变量来，脚本内不落任何口令（PowerShell 示例）：
 *   $env:ACCEPT_ADMIN_PW='…'; $env:ACCEPT_KM_PW='…'; $env:ACCEPT_EMP_PW='…'
 *   $env:ACCEPT_BASE='http://localhost:8080/api'   # 缺省本机 8080，指向线上请自己确认
 */
import { makePdf, makeXlsx, makeDocx, makeBrokenPdf } from './fixtures.mjs';

const argv = Object.fromEntries(process.argv.slice(2).map((a) => {
  const [k, v] = a.replace(/^--/, '').split('=');
  return [k, v === undefined ? true : v];
}));

const BASE = argv.base || process.env.ACCEPT_BASE || 'http://localhost:8080/api';
const ONLY = argv.only ? String(argv.only).split(',').map((s) => s.trim().toUpperCase()) : null;
const KEEP = !!argv.keep;
// 口令一律来自环境变量，脚本不落任何凭据字面值（D-12）：缺失时在 loginAll 立刻退出，
// 而不是带着空口令去撞 401——那会被登录冷却（5 次/60s）锁住，报错信息也指不回真因。
const ACCOUNTS = {
  admin: { account: process.env.ACCEPT_ADMIN || 'admin@corp.com', password: process.env.ACCEPT_ADMIN_PW || '', role: 'SYS_ADMIN', name: '系统管理员', empNo: 'EMP90001', dept: '信息技术部' },
  km: { account: process.env.ACCEPT_KM || 'km@corp.com', password: process.env.ACCEPT_KM_PW || '', role: 'KM_ADMIN', name: '知识管理员', empNo: 'EMP90002', dept: '知识管理部' },
  emp: { account: process.env.ACCEPT_EMP || 'emp@corp.com', password: process.env.ACCEPT_EMP_PW || '', role: 'EMPLOYEE', name: '普通员工', empNo: 'EMP90003', dept: '销售部' },
};
// 验收目标（0-100 刻度，脚本自述的判定线；不是系统实测出来的数）
const LIMITS = {
  retrievalPrecision: ['gte', 90], relevance: ['gte', 85], faithfulness: ['gte', 88],
  hallucinationRate: ['lte', 5], citationCompleteness: ['gte', 90],
};

const tok = {};
const results = [];
const created = { kbs: [], docs: [], conversations: new Set() };

// 每次运行一个 nonce：Redis 回答缓存 TTL 600s，复用问法会让 TTFT 与零命中判定失真
const NONCE = Date.now().toString(36);

function check(ac, name, pass, evidence) {
  results.push({ ac, name, pass, evidence });
  console.log(`  ${pass === null ? 'SKIP' : pass ? '\x1b[32mPASS\x1b[0m' : '\x1b[31mFAIL\x1b[0m'}  [${ac}] ${name}`);
  console.log(`        ${evidence}`);
}
const want = (ac) => !ONLY || ONLY.some((o) => ac.startsWith(o.replace(/^-/, '')));
const J = (o) => { try { return JSON.stringify(o); } catch { return String(o); } };
const clip = (s, n) => String(s ?? '').replace(/\s+/g, ' ').slice(0, n);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(path, { role = 'admin', method = 'GET', body, query } = {}) {
  const h = {};
  if (role) h.authorization = 'Bearer ' + tok[role];
  if (body !== undefined) h['content-type'] = 'application/json; charset=utf-8';
  const r = await fetch(BASE + path + (query ? '?' + query : ''), {
    method, headers: h, body: body === undefined ? undefined : J(body),
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 响应 */ }
  return { status: r.status, json, text };
}

/** 消费 SSE 流。返回逐事件序列、拼接答案、首 token 时刻、token 到达时间跨度 */
async function chat(question, { role = 'admin', kbIds = null, conversationId = null, signal = null } = {}) {
  const t0 = Date.now();
  const events = [];
  const tokenAt = [];
  let firstTokenMs = null, answer = '';
  const r = await fetch(BASE + '/chat/stream', {
    method: 'POST', signal,
    headers: { authorization: 'Bearer ' + tok[role], 'content-type': 'application/json', accept: 'text/event-stream' },
    // 请求体不带 history（D-11）：上下文由服务端从会话记录里取，客户端传的历史不会被采信
    body: J({ conversationId, question, knowledgeBaseIds: kbIds }),
  });
  if (!r.ok) return { httpStatus: r.status, events, answer: '', firstTokenMs: null, tokenSpreadMs: null };
  const rd = r.body.getReader(), dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await rd.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, i);
      buf = buf.slice(i + 2);
      let ev = 'message', data = '';
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      let parsed = data;
      try { parsed = JSON.parse(data); } catch { /* 原样保留 */ }
      const ms = Date.now() - t0;
      if (ev === 'token') {
        if (firstTokenMs === null) firstTokenMs = ms;
        tokenAt.push(ms);
        answer += parsed?.text ?? '';
      }
      events.push({ ev, data: parsed, ms });
    }
  }
  const convId = events.find((e) => e.ev === 'start')?.data?.conversationId;
  if (convId) created.conversations.add(convId);
  return {
    httpStatus: r.status, events, answer, firstTokenMs,
    tokenSpreadMs: tokenAt.length ? tokenAt[tokenAt.length - 1] - tokenAt[0] : null,
    tokenFrames: tokenAt.length,
  };
}
const evs = (s, name) => s.events.filter((e) => e.ev === name).map((e) => e.data);
const stepsOf = (s) => {
  const m = {};
  for (const u of evs(s, 'agent-update')) (m[u.step] ??= []).push(u.status);
  return m;
};

async function upload(role, kbId, filename, buf, type) {
  const fd = new FormData();
  fd.append('file', new Blob([buf], { type }), filename);
  fd.append('kbId', kbId);
  const r = await fetch(BASE + '/documents/upload', {
    method: 'POST', headers: { authorization: 'Bearer ' + tok[role] }, body: fd,
  });
  const j = await r.json().catch(() => null);
  if (j?.id) created.docs.push(j.id);
  return { status: r.status, json: j };
}

async function waitDocs(ids, { timeoutMs = 150000 } = {}) {
  const out = {};
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    let pending = 0;
    for (const id of ids) {
      const d = await api('/documents/' + id, { role: 'km' });
      out[id] = d.json;
      if (['PENDING', 'PARSING'].includes(d.json?.status)) pending++;
    }
    if (!pending) break;
    await sleep(3000);
  }
  return out;
}

// ==================== 前置 ====================
/**
 * 跑 AC 之前必须确认向量库没在降级：降级期间内存副本不含已入库的分块，全站问答会静默变成
 * "未检索到相关内容"，而前端仍显示"全部知识库已连接"，AC-2/AC-3 的失败会全是这个原因的伪装。
 * 注意降级**不是粘性的**：PgStoreRecovery 每 PG_REPROBE_MS(默认 30s) 重探一次，探通即自动切回
 * pgvector 并回放排队写（旧注释"只有重启才会恢复"已过时，权威结论见设计文档 §2.5）。
 * 真正需要在意的是：降级窗口内发生的问答，本轮结论不可信。
 */
let storeHealthy = false;
async function preflight() {
  const docs = await api('/documents?page=1&size=1', { role: 'admin' });
  const first = docs.json?.items?.[0];
  const ov = await api('/knowledge-bases/overview', { role: 'admin' });
  const chunks = ov.json?.totalChunks ?? 0;
  // 空库直接 SKIP 中止，不抛 TypeError：演示语料已删（原 'kb-policy' 库连同播种文档一起不在了），
  // 没有可检索分块时 AC-1~AC-6 的结果与产品无关，报成缺陷只会误导
  if (!first || chunks === 0) {
    check('ENV', '库里有可检索的分块', null,
      `documents=${docs.json?.total ?? '?'} totalChunks=${chunks} → 空库跑 AC 无意义，先上传并入库真实语料`);
    throw new Error('文档/分块为空，中止');
  }
  const probe = await api('/documents/' + first.id, { role: 'admin' });
  const mode = probe.json?.vectorStore;
  const expectPg = process.env.PG_ENABLED !== 'false';
  storeHealthy = chunks > 0 && (!expectPg || mode === 'pgvector');
  check('ENV', '向量库未降级且有可检索分块', storeHealthy,
    `vectorStore=${mode} totalChunks=${chunks} kbCount=${ov.json?.kbCount}`
    + (storeHealthy ? '' : ' → 当前走内存实现（PgStoreRecovery 每 30s 自动重探，等它切回 pgvector 或查 PG 健康后再跑）'));
  if (!storeHealthy) throw new Error('向量库降级，后续 AC 结果全部失真，中止');
}

async function loginAll() {
  const missing = Object.entries(ACCOUNTS).filter(([, a]) => !a.password).map(([k]) => k);
  if (missing.length) {
    throw new Error(`缺少 ${missing.join('/')} 的口令：用环境变量 ACCEPT_ADMIN_PW / ACCEPT_KM_PW / ACCEPT_EMP_PW 提供`
      + `（账号名可用 ACCEPT_ADMIN / ACCEPT_KM / ACCEPT_EMP 覆盖，默认 ${ACCOUNTS.admin.account} 等三个种子账号）`);
  }
  for (const [k, a] of Object.entries(ACCOUNTS)) {
    const r = await fetch(BASE + '/auth/login', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: J({ account: a.account, password: a.password }),
    });
    const j = await r.json().catch(() => ({}));
    if (j?.token) { tok[k] = j.token; continue; }
    if (argv.bootstrap && r.status === 401) {
      if (!tok.admin) throw new Error('需要 admin 令牌才能 --bootstrap');
      const c = await api('/settings/users', {
        role: 'admin', method: 'POST',
        body: { name: a.name, account: a.account, empNo: a.empNo, password: a.password, role: a.role, dept: a.dept },
      });
      if (c.status !== 200) throw new Error(`${a.account} 创建失败 HTTP ${c.status} ${clip(c.text, 120)}`);
      const again = await fetch(BASE + '/auth/login', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: J({ account: a.account, password: a.password }),
      });
      const jj = await again.json();
      if (!jj?.token) throw new Error(`${a.account} 创建后仍无法登录`);
      // 建号后拿到的是**这个新账号**的令牌，不是 admin 的：写成 tok.admin 会把 admin 令牌换成 KM_ADMIN 的，
      // 后面所有 role:'admin' 的调用（含 AC-4 的权限差分与建号本身）都在用错的身份跑，且一路 403 也指不回这里
      tok[k] = jj.token;
      continue;
    }
    throw new Error(`登录 ${a.account} 失败 HTTP ${r.status} ${clip(J(j), 120)}`
      + (k !== 'admin' ? '（可加 --bootstrap 由 admin 创建种子账号）' : ''));
  }
  const me = await api('/auth/me');
  console.log(`后端 ${BASE} ｜ 已登录 ${Object.keys(tok).join('/')} ｜ 当前 ${me.json?.name}(${me.json?.role})`);
  const kbs = await api('/knowledge-bases', { role: 'admin' });
  console.log(`知识库 ${kbs.json.length} 个：${kbs.json.map((k) => `${k.name}/${k.scope}`).join('、')}\n`);
}

// ==================== AC-1 ====================
async function ac1() {
  console.log('=== AC-1 智能问答全链路：提问→多智能体→流式回答→引用溯源 ===');
  // 不写死库 id：'kb-policy' 是演示期播种库的名字，语料已删、这个 id 不存在了。
  // kbIds=null 即"当前角色全部可见库"，与真实用法一致（空库情况已由 preflight 拦下）
  const q1 = `员工加班后如何计算调休？年假有几天？（回归${NONCE}）`;
  const s = await chat(q1, { kbIds: null });
  const steps = stepsOf(s);
  const pipeline = ['intent', 'retrieval', 'synthesis', 'validation'];
  const start = evs(s, 'start')[0];
  const cites = evs(s, 'citation').flat();
  const done = evs(s, 'done')[0];
  if (start?.conversationId) created.conversations.add(start.conversationId);
  check('AC-1', 'start 事件携带会话与消息 ID', !!start?.conversationId && !!start?.messageId, clip(J(start), 140));
  check('AC-1', '四段智能体管道全部 running→done',
    pipeline.every((p) => (steps[p] || []).includes('done') && (steps[p] || []).includes('running')),
    pipeline.map((p) => `${p}:${(steps[p] || []).join('>') || '缺失'}`).join('  '));
  check('AC-1', 'token 事件流式下发', s.tokenFrames > 1, `${s.tokenFrames} 帧，答案 ${s.answer.length} 字`);
  check('AC-1', '引用溯源随流下发', cites.length > 0, `citation ${cites.length} 条`);
  check('AC-1', 'done 收尾且质量校验通过', done?.qualityPassed === true,
    `qualityPassed=${done?.qualityPassed} attempts=${done?.attempts} provider=${done?.provider} model=${done?.model} vectors=${done?.vectors}`);

  // ---- D-1 低质重生成：只有判定真的没过时才有效力。attempts==1 且判定通过 → 没触发，如实 SKIP，
  //      不能把它当"重试管线验证过"（这是本项目一贯的"未测得不等于达标"口径）
  const revises = evs(s, 'revision');
  const att = done?.attempts;
  check('AC-1', '质量未过时的重生成协议自洽（revision 帧数 = attempts-1，且旧文不拼接）',
    att == null || att <= 1 ? null : revises.length === att - 1,
    `attempts=${att ?? '（缓存回放路径无此字段）'} revision帧=${revises.length}`
    + (revises.length ? ` 首条原因=${clip(revises[0]?.reason, 80)}` : ' → 本轮判定一次通过，重生成未触发，跳过'));

  // ---- D-11 多轮上下文：两条判据都读后端自己生成的 agent-update / done 字段，
  //      不去猜模型说了什么（detail 由 ChatService 拼装，它报几条就是几条进了 messages 数组）
  const convId = start?.conversationId || null;
  const followUp = `那这两种假期的申请需要提前几天提交？（回归${NONCE}）`;
  const fu = await chat(followUp, { conversationId: convId });
  const fuDone = evs(fu, 'done')[0];
  const fuCtx = evs(fu, 'agent-update')
    .filter((u) => u.step === 'synthesis').map((u) => u.detail || '').find((t) => t.includes('条会话上下文'));
  // 上下文只有真实模型才吃（本地模板按片段拼装），降级时如实 SKIP 而不是判过。
  // 追问本身可能零命中（检索只用当前问题原文、不做指代补全）→ 那条路径没有 provider，同样 SKIP
  check('AC-1', '同会话追问会带服务端历史进模型',
    fuDone?.provider === 'openai-compatible' ? !!fuCtx : null,
    `provider=${fuDone?.provider} noHit=${fuDone?.noHit} ｜ synthesis detail=${clip(fuCtx || '（未标注上下文）', 100)}`
    + (fuDone?.provider === 'openai-compatible' ? '' : ' → 未走真实模型或本轮未生成答案，上下文不参与生成，跳过'));

  // 同一句话换一种上下文就必须重新生成：历史窗口参与缓存 key，否则追问会拿首轮答案作答
  const repeat = await chat(q1, { conversationId: convId });
  const rpDone = evs(repeat, 'done')[0];
  // 要求 done 存在且不是拒答：本轮若没答题，"没回放"就不构成证据，不能算通过。
  // 另外只有首轮过了质量校验才会回填缓存（D-1 起低质答案不入缓存）——首轮没缓存可回放时这条同样测不出东西，如实 SKIP
  check('AC-1', '同问题在不同上下文下不复用缓存答案',
    done?.qualityPassed === true ? (!!rpDone && rpDone.cached !== true && rpDone.noHit !== true) : null,
    `首轮 qualityPassed=${done?.qualityPassed}（没过的答案不回填缓存，那时 cached!=true 是白给的）`
    + ` ｜ 第二次 cached=${rpDone?.cached} noHit=${rpDone?.noHit}（cached=true 即说明 key 未含历史窗口、追问会拿到首轮答案）`);

  console.log(`        答案：${clip(s.answer, 180)}`);
  return { answer: s.answer, cites };
}

// ==================== AC-2 ====================
async function ac2(ac1Result) {
  console.log('\n=== AC-2 引用溯源准确性：每个 [n] 可定位到来源文档（分页格式还要带页码） ===');
  const { answer, cites } = ac1Result;
  const markers = [...new Set([...answer.matchAll(/\[(\d+)\]/g)].map((m) => +m[1]))].sort((a, b) => a - b);
  // 只有 PDF 有真实页边界（抽取阶段逐页取文本）。docx/txt/md/xlsx 的"页"是渲染时才产生的概念，
  // 后端一律不下发 page——要它们交页码等于逼系统编造，所以按文档类型分别定判据（D-4）。
  const PAGINATED = new Set(['pdf']);
  const problems = [];
  const located = [];
  for (const n of markers) {
    const c = cites.find((x) => x.id === n);
    if (!c) { problems.push(`[${n}] 无对应引用卡片`); continue; }
    if (!c.doc) { problems.push(`[${n}] 引用卡片缺文档名`); continue; }
    const d = await api('/documents/' + c.docId, { role: 'km' });
    if (d.status !== 200) { problems.push(`[${n}] docId 不可定位 HTTP ${d.status}`); continue; }
    const type = String(d.json?.type || '').toLowerCase();
    const hasPage = typeof c.page === 'number' && c.page > 0;
    if (PAGINATED.has(type) && !hasPage) { problems.push(`[${n}] ${type} 的引用没有页码（逐页抽取未产出，page 字段缺席）`); continue; }
    if (!hasPage && typeof c.chunkIndex !== 'number') { problems.push(`[${n}] 既无页码也无分块序号，定位不到片段`); continue; }
    located.push(`[${c.id}]${c.doc}(${type || '未知类型'}) ${hasPage ? 'p.' + c.page : '无页边界'} chunk#${c.chunkIndex} score ${c.score}`);
  }
  check('AC-2', '回答含行内引用标号', markers.length > 0, `标号 [${markers.join('][')}]`);
  check('AC-2', '每个标号 → 真实存在的文档 + 页码或分块序号', problems.length === 0,
    problems.length ? problems.join(' | ') : located.join('  '));

  // 零命中必须用空库：scoreThreshold 默认 0.15 时，生僻问题仍会弱命中
  const kb = await api('/knowledge-bases', {
    role: 'km', method: 'POST',
    body: { name: `验收空库-${NONCE}`, scope: 'internal', members: [], embeddingModel: 'text-embedding-v3' },
  });
  created.kbs.push(kb.json.id);
  const z = await chat(`玛雅长计历法对应的回归年精确长度（回归${NONCE}）`, { role: 'km', kbIds: [kb.json.id] });
  const zcites = evs(z, 'citation').flat();
  const zdone = evs(z, 'done')[0];
  check('AC-2', '检索零命中 → warning(no-result) 且零引用（不编造来源）',
    evs(z, 'warning').length > 0 && zcites.length === 0,
    `warning=${clip(J(evs(z, 'warning')), 40)} citation=${zcites.length} qualityPassed=${zdone?.qualityPassed} noHit=${zdone?.noHit}`);
  check('AC-2', '零命中时不产出答案正文、不残留引用标号',
    !/\[\d+\]/.test(z.answer) && z.answer.includes('未检索到'), `答案：${clip(z.answer, 90)}`);
}

// ==================== AC-3 ====================
async function ac3() {
  console.log('\n=== AC-3 文档解析：PDF/DOCX/XLSX/TXT/MD 成功率 ≥95%，失败可重试 ===');
  const kb = await api('/knowledge-bases', {
    role: 'km', method: 'POST',
    body: { name: `验收夹具库-${NONCE}`, scope: 'internal', members: [], embeddingModel: 'text-embedding-v3' },
  });
  const kbId = kb.json.id;
  created.kbs.push(kbId);
  console.log(`  测试库 ${kbId}`);

  const fixtures = [
    ['报价制度.pdf', makePdf([
      'Expense Reimbursement Policy (Acceptance Fixture)',
      'Sales department quarterly budget is 120000 CNY approved by the sales director.',
      'RnD department quarterly budget is 200000 CNY approved by the technical director.',
      'Travel invoices must be submitted within 10 working days after the trip ends.',
      'Overtime meals after 20:00 are reimbursed at 30 CNY per occurrence.',
    ]), 'application/pdf', /120000/, 'Reimbursement'],
    ['采购规则.pdf', makePdf([
      'Procurement Rules (Acceptance Fixture)',
      'Suppliers must pass qualification review before onboarding.',
      'Purchase orders above 50000 CNY need competitive bids from three vendors.',
      'Contract amendments must be countersigned by the legal department.',
      'Payment terms default to net 30 days after goods acceptance.',
      'Urgent procurement below 5000 CNY may use a single-source waiver.',
    ]), 'application/pdf', /50000/, 'Procurement'],
    ['预算报表.xlsx', makeXlsx([
      ['Department', 'Quarterly Budget', 'Approver'],
      ['Sales', '120000 CNY', 'Sales Director'],
      ['RnD', '200000 CNY', 'Technical Director'],
      ['Admin', '50000 CNY', 'Admin Manager'],
    ]), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', /200000/, 'RnD'],
    ['行政指南.docx', makeDocx([
      '行政办公指南（验收夹具）',
      '员工可通过 OA 系统预订会议室，需提前一天提交申请。',
      '大型会议室使用需部门总监审批，设备故障请在行政部工作时间内报修。',
    ]), 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', /会议室/, 'OA'],
    ['制度说明.txt', Buffer.from('安全制度说明（验收夹具）\n生产环境口令每 90 天轮换一次，数据库账号禁止共享。\n离职当日回收全部权限并归档工作文档。', 'utf8'), 'text/plain', /90/, '口令'],
    ['运营规范.md', Buffer.from('# 知识库运营规范（验收夹具）\n\n## 一、分级\n公开、内部、部门、机密四档，机密库需二次确认。\n\n## 二、治理\n知识管理员负责抽检与下架，覆盖率低于 80% 触发工单。\n', 'utf8'), 'text/markdown', /覆盖率/, '治理'],
  ];

  const ids = {};
  for (const [name, buf, type] of fixtures) {
    const r = await upload('km', kbId, name, buf, type);
    ids[name] = r.json?.id;
    console.log(`  upload ${name} → HTTP ${r.status} ${r.json?.status}`);
  }
  const fin = await waitDocs(Object.values(ids).filter(Boolean));
  const rows = Object.entries(ids).map(([n, id]) => {
    const d = fin[id] || {};
    return `${n}:${d.status}/${d.chunkCount}块${d.failReason ? '(' + clip(d.failReason, 40) + ')' : ''}`;
  });
  const ok = Object.values(ids).filter((id) => fin[id]?.status === 'READY').length;
  check('AC-3', `${fixtures.length} 种格式解析入库成功率 ≥95%`, ok === Object.keys(ids).length,
    `${ok}/${Object.keys(ids).length} READY ｜ ${rows.join('  ')}`);

  // 抽取内容真实性：不能只"状态成功"，要确认正文进了分块
  const wrong = [];
  for (const [name, , , numRe, kw] of fixtures) {
    const id = ids[name];
    if (!id || fin[id]?.status !== 'READY') { wrong.push(`${name}(未入库)`); continue; }
    const ch = await api(`/documents/${id}/chunks?page=1`, { role: 'km' });
    const txt = (ch.json || []).map((c) => c.text).join(' ');
    if (!numRe.test(txt) || !txt.includes(kw)) wrong.push(`${name}(分块缺关键内容)`);
  }
  check('AC-3', '各格式正文真实抽取到分块（非空壳成功）', wrong.length === 0,
    wrong.length ? wrong.join('；') : `${fixtures.length} 个夹具均命中关键句`);

  const bad = await upload('km', kbId, '损坏样本.pdf', makeBrokenPdf(), 'application/pdf');
  let st = null;
  for (let i = 0; i < 25; i++) {
    await sleep(2000);
    st = (await api('/documents/' + bad.json.id, { role: 'km' })).json;
    if (['FAILED', 'READY'].includes(st?.status)) break;
  }
  const retry = await api(`/documents/${bad.json.id}/retry`, { role: 'km', method: 'POST' });
  await sleep(6000);
  const st2 = (await api('/documents/' + bad.json.id, { role: 'km' })).json;
  check('AC-3', '解析失败进入 FAILED 并可重试（重试重新入队）',
    st?.status === 'FAILED' && retry.status === 200 && ['FAILED', 'PARSING', 'READY'].includes(st2?.status),
    `坏文件=${st?.status}(${clip(st?.failReason, 45)}) retry=${retry.status} 重试后=${st2?.status}`);

  const mismatch = [];
  const all = await api('/documents?page=1&size=100', { role: 'km' });
  for (const d of (all.json?.items || [])) {
    if (d.status !== 'READY') continue;
    let got = 0;
    for (let p = 1; p <= 6; p++) {
      const c = await api(`/documents/${d.id}/chunks?page=${p}`, { role: 'km' });
      const n = (c.json || []).length;
      got += n;
      if (n < 10) break;
    }
    if (got === 0 && d.chunkCount > 0) mismatch.push(`${d.name}(声明${d.chunkCount}块/可查0块)`);
    else if (got && got !== d.chunkCount) mismatch.push(`${d.name}(声明${d.chunkCount}/可查${got})`);
  }
  check('AC-3', '全库分块预览与声明块数一致', mismatch.length === 0,
    mismatch.length ? mismatch.join('；') : `扫描 ${all.json?.total} 篇全部一致`);
}

// ==================== AC-4 ====================
async function ac4() {
  console.log('\n=== AC-4 权限隔离：员工仅检索公开库，越权接口 403 ===');
  const empKbs = (await api('/knowledge-bases', { role: 'emp' })).json;
  const adminKbs = (await api('/knowledge-bases', { role: 'admin' })).json;
  const empVisible = new Set(empKbs.map((k) => k.id));
  const nonPublic = empKbs.filter((k) => k.scope !== 'public');
  check('AC-4', '员工知识库列表仅含 public', nonPublic.length === 0,
    `emp 可见 ${empKbs.length} 个[${empKbs.map((k) => k.scope).join(',')}]，admin ${adminKbs.length} 个`);

  // 差分测试：同一问题，授权角色可检索到的范围更大，且员工引用不得越权。
  // 原来这两条判的是演示语料里的具体数字（4.82 / 2.95 亿），那批文档已随演示数据删除（D-12）——
  // 现在判的是与语料无关的不变量：员工答案里不得出现"只有管理员才引用到"的文档名。
  const Q = `公司的收入构成和订阅业务情况如何（回归${NONCE}）`;
  const priv = await chat(Q, { role: 'admin' });
  const pub = await chat(Q, { role: 'emp' });
  const pcites = evs(pub, 'citation').flat();
  const adminCites = evs(priv, 'citation').flat();
  const violations = [];
  for (const c of pcites) {
    const d = await api('/documents/' + c.docId, { role: 'admin' });
    if (!empVisible.has(d.json?.kbId)) violations.push(`${c.doc}→${d.json?.kbId}`);
  }
  check('AC-4', '员工检索结果无任何非可见库文档（引用级校验）', violations.length === 0,
    `员工引用 ${pcites.length} 条，越权 ${violations.length ? J(violations) : '0 条'}`);
  const pubDocNames = new Set(pcites.map((c) => c.doc).filter(Boolean));
  const adminOnlyDocs = [...new Set(adminCites.map((c) => c.doc).filter((d) => d && !pubDocNames.has(d)))];
  const leaked = adminOnlyDocs.filter((d) => pub.answer.includes(d));
  check('AC-4', '员工答案未出现"仅管理员引用到"的文档名', leaked.length === 0,
    `仅管理员引用的文档 ${adminOnlyDocs.length ? clip(J(adminOnlyDocs), 80) : '（本轮两侧引用相同）'}`
    + ` ｜ 泄漏 ${leaked.length ? J(leaked) : '0 个'}`);
  const adminIds = new Set(adminKbs.map((k) => k.id));
  check('AC-4', '对照：管理员可见库集合 ⊇ 员工可见库集合', empKbs.every((k) => adminIds.has(k.id)),
    `emp ${empKbs.length} 个 / admin ${adminKbs.length} 个 ｜ 管理员引用 ${adminCites.length} 条、员工 ${pcites.length} 条`);

  // 这条判的是角色矩阵：EMPLOYEE 调上传在 AuthWebFilter 就被 403，还没走到库 id 校验，
  // 所以这里给一个占位 id 即可（原先写死的 'kb-policy' 已不存在）
  const upEmp = await upload('emp', 'any-kb-id', '越权.txt', Buffer.from('越权样例内容', 'utf8'), 'text/plain');
  check('AC-4', '员工上传文档 → 403', upEmp.status === 403, `HTTP ${upEmp.status} ${clip(upEmp.text, 80)}`);

  const mon = await api('/monitor/metrics', { role: 'emp' });
  const audit = await api('/settings/audit-logs', { role: 'emp' });
  const users = await api('/settings/users', { role: 'km' });
  check('AC-4', '越权系统接口一律 403（监控/审计/用户管理）',
    mon.status === 403 && audit.status === 403 && users.status === 403,
    `monitor(emp)=${mon.status} audit(emp)=${audit.status} users(km)=${users.status}`);

  const anon = await fetch(BASE + '/chat/stream', { method: 'POST', headers: { 'content-type': 'application/json' }, body: J({ question: 'hi' }) });
  const forged = await api('/knowledge-bases', { role: null });
  const noAuth = await fetch(BASE + '/knowledge-bases', { headers: { authorization: 'Bearer bogus.token.value' } });
  check('AC-4', '未登录/伪造令牌 → 401', anon.status === 401 && noAuth.status === 401,
    `无令牌=${anon.status} 伪造令牌=${noAuth.status}（不带 authorization 头=${forged.status}）`);

  const noConfirm = await api('/knowledge-bases', {
    role: 'km', method: 'POST', body: { name: `验收机密确认-${NONCE}`, scope: 'confidential', members: [] },
  });
  if (noConfirm.json?.id) created.kbs.push(noConfirm.json.id);
  check('AC-4', '机密库创建需 ?confirm=true 二次确认', noConfirm.status === 428,
    `无 confirm → HTTP ${noConfirm.status} ${clip(J(noConfirm.json), 90)}`);
  const withConfirm = await api('/knowledge-bases', {
    role: 'km', method: 'POST', query: 'confirm=true',
    body: { name: `验收机密确认-${NONCE}b`, scope: 'confidential', members: [] },
  });
  if (withConfirm.json?.id) created.kbs.push(withConfirm.json.id);
  check('AC-4', '带 confirm=true 可正常创建机密库', withConfirm.status === 200 && !!withConfirm.json?.id,
    `HTTP ${withConfirm.status} ${clip(J(withConfirm.json), 70)}`);

  const loginFail = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: J({ account: ACCOUNTS.emp.account, password: 'wrong-password-xyz' }),
  });
  check('AC-4', '错误口令不暴露账号存在性（统一 401 文案）', loginFail.status === 401,
    `HTTP ${loginFail.status} ${clip(await loginFail.text(), 80)}`);
}

// ==================== AC-5 ====================
async function ac5() {
  console.log('\n=== AC-5 响应性能：首 Token <1s、P95 <2s、SSE 支持中断重连 ===');
  const qs = [
    '员工入职需要提交哪些材料', 'IT 账号权限申请走什么流程', '商务招待的人均费用上限是多少',
    '知识库文档治理的责任分工是什么', 'Open API 鉴权怎么做的',
  ];
  const runs = [];
  for (const q of qs) {
    const s = await chat(`${q}（回归${NONCE}）`, { role: 'admin' });
    runs.push(s);
    console.log(`  ${clip(q, 22)}｜首 token ${s.firstTokenMs}ms｜token ${s.tokenFrames} 帧跨 ${s.tokenSpreadMs}ms｜全程 ${s.events.at(-1)?.ms}ms`);
  }
  const ft = runs.map((r) => r.firstTokenMs).filter((x) => x != null).sort((a, b) => a - b);
  const med = ft[Math.floor(ft.length / 2)];
  const p95 = ft[Math.max(0, Math.ceil(ft.length * 0.95) - 1)];
  check('AC-5', '首 Token 延迟 < 1000ms', med < 1000,
    `样本 ${ft.join('/ ')}ms，中位 ${med}ms，P95 ${p95}ms（手册目标 <1000ms）`);
  const spreads = runs.map((r) => r.tokenSpreadMs).filter((x) => x != null);
  const maxSpread = Math.max(...spreads);
  check('AC-5', '回答为增量流式下发（token 在时间轴上展开）', maxSpread > 1000,
    `token 到达跨度 ${spreads.join('/ ')}ms → ${maxSpread > 1000 ? '确认逐块下发' : '末尾一次性下发，前端打字机效果未生效'}`);

  const m = (await api('/monitor/metrics', { role: 'admin' })).json;
  // 后端没有延迟样本时不报分位数（p95Ms 字段缺席）——那不是"达标"，判 SKIP 而不是 FAIL/PASS（D-19/D-22 同口径）
  const p95Verdict = m?.p95Ms == null ? null : m.p95Ms < 2000;
  check('AC-5', '监控 P95 < 2000ms（接口整体口径）', p95Verdict,
    `p50=${m?.p50Ms}ms p95=${m?.p95Ms}ms 延迟样本=${m?.latencySamples} 当日估算费用=${m?.cost?.today}`
    + (p95Verdict === null ? ' → 未测得（当前进程还没有请求样本），不参与达标' : ''));
  console.log(`        ⚠ 该口径由 MetricsWebFilter 统计全部 /api 请求（含登录/列表等轻量调用），`);
  console.log(`          问答流耗时未单独分桶，故不能据其判定问答 P95；问答实测 P95=${p95}ms。`);

  const ac = new AbortController();
  let frames = 0;
  const inflight = (async () => {
    const r = await fetch(BASE + '/chat/stream', {
      method: 'POST', signal: ac.signal,
      headers: { authorization: 'Bearer ' + tok.admin, 'content-type': 'application/json', accept: 'text/event-stream' },
      body: J({ question: `请逐项展开公司全部制度条款，越详细越好（回归${NONCE}）` }),
    });
    const rd = r.body.getReader();
    while (true) {
      const { value, done } = await rd.read();
      if (done) break;
      frames += (new TextDecoder().decode(value).match(/event:token/g) || []).length;
    }
  })();
  const t0 = Date.now();
  while (frames < 3 && Date.now() - t0 < 45000) await sleep(200);
  ac.abort();
  await inflight.catch(() => { });
  check('AC-5', '生成中可打断（客户端断开即终止）', frames >= 3,
    `收到 ${frames} 个 token 帧后主动 abort，请求已终止`);
  const after = await chat(`请假需要提前几天申请（回归${NONCE}）`, { role: 'admin' });
  check('AC-5', '中断后可重新发起并完整回答', after.firstTokenMs != null && evs(after, 'done').length === 1,
    `重连首 token ${after.firstTokenMs}ms done=${clip(J(evs(after, 'done')[0]), 80)}`);
  const convs = await api('/chat/conversations', { role: 'admin' });
  check('AC-5', '中断未污染会话状态', convs.status === 200,
    `GET /chat/conversations → HTTP ${convs.status}，${(convs.json || []).length} 个会话`);
}

// ==================== AC-6 ====================
async function ac6() {
  console.log('\n=== AC-6 评测达标：检索≥90 / 相关度≥85 / 忠实度≥88 / 幻觉≤5 / 引用完整≥90 ===');
  if (!argv.eval) {
    check('AC-6', '评测中心', null, '未执行（需真实 LLM 花费）。加 --eval 运行全量 golden set');
    return;
  }
  const start = await api('/eval/run', { role: 'km', method: 'POST', body: {} });
  const runId = start.json?.runId;
  if (!runId) { check('AC-6', '评测触发', false, `HTTP ${start.status} ${clip(start.text, 120)}`); return; }
  console.log(`  runId=${runId}，轮询中…`);
  let run = null;
  for (let i = 0; i < 240; i++) {
    await sleep(5000);
    const runs = await api('/eval/runs', { role: 'km' });
    run = (runs.json || []).find((r) => r.id === runId);
    process.stdout.write(`\r  progress ${run?.metrics?.progress ?? 0}%   `);
    // failed 也要跳出：D-28 之后"没有可用真向量"会让整轮直接失败并且不出分，
    // 继续轮询到 240 次只是白等
    if (run?.status === 'done' || run?.status === 'failed') break;
  }
  console.log();
  const detail = await api('/eval/runs/' + runId, { role: 'km' });
  const d = detail.json || {};
  const env = d.env || run?.env || {};
  // 分数只有在"知道当时是什么检索环境"时才可解释（D-28）：没有这份快照，这一轮没法和任何一轮比
  check('AC-6', '本轮带检索环境快照（env）', !!(env.vectorStore && env.embedMode),
    `发起人 ${env.initiatorRole || '?'}/${env.initiator || '?'}，vectorStore=${env.vectorStore || '无快照'}，`
    + `embedMode=${env.embedMode || '无快照'}，topK=${env.ragTopK ?? '?'}，阈值=${env.ragScoreThreshold ?? '?'}，degraded=${env.degraded ?? '?'}`);
  if (run?.status === 'failed') {
    check('AC-6', '评测运行完成', false, `status=failed：${run.failReason || d.failReason || '失败原因未记录'}`);
    return;
  }
  check('AC-6', '评测运行完成', run?.status === 'done', `status=${run?.status ?? '未知（轮询超时）'}`);
  const m = d.metrics || run?.metrics || {};
  // 检索失败的题仍留在分母里（这是刻意的：不靠剔除失败样本把分数做高），但它意味着这一轮不可比
  const rf = m.retrievalFailedCount;
  if (typeof rf === 'number') {
    check('AC-6', '检索失败题数为 0（分数可解释的前提）', rf === 0,
      rf === 0 ? '无失败题' : `${rf} 题检索失败，仍计入分母 → 本轮分数偏低是真的偏低，要重新跑才算数`);
  }
  // -1 是后端的"未判定"哨兵（judge 不可用或该维度无样本），字段缺席则是"未产出"。
  // 两者一律判 SKIP（pass=null）而不是 PASS：原来 hallucinationRate=-1 会因 -1<=5 被判合格，是假通过（D-22）
  for (const [k, [dir, target]] of Object.entries(LIMITS)) {
    const v = m[k];
    const measured = typeof v === 'number' && v >= 0;
    const pass = measured ? (dir === 'gte' ? v >= target : v <= target) : null;
    check('AC-6', k, pass, measured
      ? `${v}（目标 ${dir === 'gte' ? '≥' : '≤'}${target}）`
      : `${v === undefined ? '字段缺席' : v} → 未判定（judge 未运行或无样本），不计入达标`);
  }
  const samples = detail.json?.samples || [];
  const failed = samples.filter((s) => s.passed === false);
  check('AC-6', '逐样本明细可查（JSONL 导出）', samples.length > 0,
    `样本 ${samples.length} 条，未通过 ${failed.length} 条`);
  const dist = {};
  for (const s of samples) {
    const v = s.scores?.citationCompleteness;
    dist[v] = (dist[v] || 0) + 1;
  }
  console.log(`        引用完整率分布 ${J(dist)}；markers/min(citations,2) 口径下单标记样本必得 50 分`);
  for (const s of failed.slice(0, 5)) {
    console.log(`        - ${clip(s.question, 34)}｜${J(s.scores)}`);
  }
  const ex = await fetch(BASE + '/eval/export/' + runId, { headers: { authorization: 'Bearer ' + tok.km } });
  const body = await ex.text();
  check('AC-6', '评测结果 JSONL 导出', ex.status === 200 && body.trim().split('\n').length > 1,
    `HTTP ${ex.status}，${body.trim().split('\n').length} 行`);
}

// ==================== 收尾 ====================
async function cleanup() {
  if (KEEP) {
    console.log(`\n--keep：保留 ${created.kbs.length} 个测试库 / ${created.docs.length} 篇文档 / ${created.conversations.size} 个会话`);
    return;
  }
  for (const id of created.docs) await api('/documents/' + id, { role: 'km', method: 'DELETE' }).catch(() => { });
  for (const id of created.kbs) await api('/knowledge-bases/' + id, { role: 'km', method: 'DELETE' }).catch(() => { });
  for (const id of created.conversations) await api('/chat/conversations/' + id, { role: 'admin', method: 'DELETE' }).catch(() => { });
  console.log(`\n已清理：${created.kbs.length} 个测试知识库、${created.docs.length} 篇文档、${created.conversations.size} 个测试会话`);
}

async function main() {
  console.log(`智汇洞察验收测试 · ${new Date().toISOString()} · nonce=${NONCE}`);
  await loginAll();
  await preflight();
  let ac1Result = { answer: '', cites: [] };
  try {
    if (want('AC-1')) ac1Result = await ac1();
    if (want('AC-2')) await ac2(ac1Result);
    if (want('AC-3')) await ac3();
    if (want('AC-4')) await ac4();
    if (want('AC-5')) await ac5();
    await ac6();
  } finally {
    // 降级可能发生在跑测途中（pg 连接瞬断即降级，30s 后由 PgStoreRecovery 自愈），收尾再查一次，避免把环境问题报成产品缺陷
    try {
      const docs = await api('/documents?page=1&size=1', { role: 'admin' });
      const first = docs.json?.items?.[0];
      const probe = first ? await api('/documents/' + first.id, { role: 'admin' }) : null;
      if (probe && probe.json?.vectorStore !== 'pgvector' && process.env.PG_ENABLED !== 'false') {
        check('ENV', '测试期间向量库未发生降级', false,
          `收尾复查 vectorStore=${probe.json?.vectorStore}（跑测中 pg 连接失败触发过降级，本轮 AC-2/AC-3 结果不可信）`);
      }
    } catch { /* 复查失败不掩盖已有结论 */ }
    await cleanup();
  }
  const byAc = {};
  for (const r of results) (byAc[r.ac] ??= []).push(r);
  console.log('\n================ 验收结论 ================');
  let failed = 0;
  for (const [ac, rs] of Object.entries(byAc)) {
    const bad = rs.filter((x) => x.pass === false);
    const skipped = rs.filter((x) => x.pass === null).length;
    failed += bad.length;
    console.log(`${ac.padEnd(5)} ${rs.length - bad.length - skipped}/${rs.length - skipped} 通过`
      + (bad.length ? `  ← ${bad.map((x) => x.name).join('；')}` : ''));
  }
  console.log(`\n${failed ? `\x1b[31m未通过 ${failed} 项\x1b[0m` : '\x1b[32m全部通过\x1b[0m'}`);
  process.exitCode = failed ? 1 : 0;
}

main().catch((e) => {
  console.error('\n验收执行异常：', e?.response ? `${e.response.status} ${e.response.statusText}` : '', e?.message || e);
  process.exitCode = 2;
});
