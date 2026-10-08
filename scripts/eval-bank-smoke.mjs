// 评测题库接口冒烟：只验门禁与读写，不触发真实评测（避免往 eval_runs 里塞垃圾点位）。
// 用法（PowerShell）：
//   $env:ZD_ADMIN='admin@corp.com'; $env:ZD_ADMIN_PW='***'; node scripts/eval-bank-smoke.mjs
// 口令一律来自环境变量，脚本不落任何凭据；ZD_BASE 默认本机 8080。
const BASE = process.env.ZD_BASE || 'http://localhost:8080/api';
const ACCOUNT = process.env.ZD_ADMIN;
const PASSWORD = process.env.ZD_ADMIN_PW;
if (!ACCOUNT || !PASSWORD) {
  console.error('需要 ZD_ADMIN / ZD_ADMIN_PW 环境变量（知识管理员及以上）');
  process.exit(2);
}

const results = [];
let token = '';
const createdIds = [];

async function call(method, path, body) {
  const headers = {};
  if (token) headers.authorization = 'Bearer ' + token;
  if (body !== undefined) headers['content-type'] = 'application/json; charset=utf-8';
  const r = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON（如导出）保留原文 */ }
  return { status: r.status, json, text };
}

function check(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ｜ ' + detail : ''}`);
}

const nonce = Date.now();
const Q = (tag) => `冒烟${nonce} ${tag}：这项制度什么时候生效？`;

async function main() {
  const login = await call('POST', '/auth/login', { account: ACCOUNT, password: PASSWORD });
  if (!login.json?.token) {
    console.error('登录失败 HTTP ' + login.status + '，终止（不重试口令，避免触发登录冷却）');
    process.exit(1);
  }
  token = login.json.token;
  console.log(`已登录 ${login.json.user?.account}(${login.json.user?.role}) → ${BASE}\n`);

  const before = await call('GET', '/eval/golden-set');
  check('GS-0 题库实况可读', before.status === 200 && typeof before.json?.usable === 'number',
    JSON.stringify(before.json));

  // 空库分支只有真的空库才可测：usable>0 时发出 POST /eval/run 就会**真跑一次全量评测**
  // （花钱 + 往 eval_runs 写点位），与本脚本自述"未触发任何真实评测运行"矛盾（D-23）。
  // 所以先门控、再发请求； runsBefore 给 GATE-3 做基线。
  const runsBefore = await call('GET', '/eval/runs');
  const runCountBefore = (runsBefore.json || []).length;
  if (before.json.usable === 0) {
    const emptyRun = await call('POST', '/eval/run', {});
    check('RUN-0 无可用题时拒绝出分', emptyRun.status === 400 && emptyRun.json?.code === 'GOLDEN_EMPTY',
      `HTTP ${emptyRun.status} ${emptyRun.json?.code}`);
  } else {
    check('RUN-0 跳过（未发送 POST /eval/run）', true,
      '题库已有 ' + before.json.usable + ' 条可用题，发出去就是一次真实全量评测');
  }

  // 建题：不给期望文档
  const draft = await call('POST', '/eval/questions', { question: Q('缺期望文档'), golden: '要点 A' });
  check('CRUD-1 新建一律 draft', draft.status === 200 && draft.json.status === 'draft'
    && draft.json.source === 'manual' && draft.json.enabled === true,
    `status=${draft.json?.status} source=${draft.json?.source}`);
  if (draft.json?.id) createdIds.push(draft.json.id);

  const badReview = await call('POST', '/eval/questions/review', { ids: [draft.json.id], reviewed: true });
  check('REV-1 缺期望文档不允许复核', badReview.status === 400,
    `HTTP ${badReview.status} ${badReview.json?.message}`);

  const patched = await call('PUT', '/eval/questions/' + draft.json.id,
    { question: Q('补时期望文档'), golden: '要点 A', expectedDoc: '冒烟占位文档.md' });
  check('CRUD-2 编辑回填期望文档', patched.status === 200 && patched.json.status === 'draft'
    && patched.json.expectedDoc === '冒烟占位文档.md', `status=${patched.json?.status}`);

  const reviewed = await call('POST', '/eval/questions/review', { ids: [draft.json.id], reviewed: true });
  check('REV-2 复核成功', reviewed.status === 200 && reviewed.json.count === 1, JSON.stringify(reviewed.json));
  const afterReview = (await call('GET', `/eval/questions?status=reviewed`)).json || [];
  check('REV-3 复核后进入 reviewed 且带复核人',
    afterReview.some((q) => q.id === draft.json.id && q.reviewedBy),
    `reviewed 共 ${afterReview.length} 条`);

  // 改题面 → 应自动退回 draft
  const edited = await call('PUT', '/eval/questions/' + draft.json.id,
    { question: Q('改过一次题干'), golden: '要点 A', expectedDoc: '冒烟占位文档.md' });
  check('GATE-1 改动题面退回 draft 并清空复核人',
    edited.status === 200 && edited.json.status === 'draft' && !edited.json.reviewedBy,
    `status=${edited.json?.status} reviewedBy=${edited.json?.reviewedBy}`);

  // 勾选题库里含未复核 → 400，且不会创建 run
  const pickDraft = await call('POST', '/eval/run', { questionIds: [draft.json.id] });
  check('GATE-2 勾选未复核题 → 拒绝且不落 run',
    pickDraft.status === 400 && /复核/.test(pickDraft.json?.message || ''),
    `HTTP ${pickDraft.status} ${pickDraft.json?.message}`);
  const runsAfter = await call('GET', '/eval/runs');
  const runCountAfter = (runsAfter.json || []).length;
  check('GATE-3 上面被拒的运行都没有写库',
    runsAfter.status === 200 && runCountAfter === runCountBefore
      && (runsAfter.json || []).every((r) => r.status !== 'running'),
    `runs ${runCountBefore}→${runCountAfter}（相等即没有新点位），无 running`);

  // 导入：两行新题 + 一行重复
  const l1 = { question: Q('导入一'), golden: '要点 1', expectedDoc: '导入文档甲.md' };
  const l2 = { question: Q('导入二'), golden: '要点 2', expectedDoc: '导入文档乙.md' };
  const text = [l1, l2, l1, { golden: '缺题干' }].map((o) => JSON.stringify(o)).join('\n') + '\n';
  const imp = await call('POST', '/eval/questions/import', { text });
  check('IMP-1 导入按题干去重、格式不符跳过、全部 draft',
    imp.status === 200 && imp.json.created === 2 && imp.json.skippedDuplicate === 1
    && imp.json.skippedInvalid === 1 && imp.json.allAsDraft === true, JSON.stringify(imp.json));
  const listAfterImport = await call('GET', '/eval/questions');
  const imported = (listAfterImport.json || []).filter((q) => q.source === 'imported' && q.question.includes(String(nonce)));
  imported.forEach((q) => createdIds.push(q.id));
  check('IMP-2 导入题 source=imported 且为 draft',
    imported.length === 2 && imported.every((q) => q.status === 'draft'), `共 ${imported.length} 条`);

  // 停用后不参与可用计数
  await call('POST', '/eval/questions/review', { ids: [draft.json.id], reviewed: true });
  await call('POST', '/eval/questions/enabled', { id: draft.json.id, enabled: false });
  const listNow = (await call('GET', '/eval/questions')).json || [];
  const statsDisabled = (await call('GET', '/eval/golden-set')).json;
  const expectUsable = listNow.filter((q) => q.status === 'reviewed' && q.enabled).length;
  check('EN-1 停用题不计入 usable（实况与逐条推导一致）',
    listNow.find((q) => q.id === draft.json.id)?.enabled === false && statsDisabled.usable === expectUsable,
    `reviewed=${statsDisabled.reviewed} usable=${statsDisabled.usable}（按列表推导 ${expectUsable}）`);
  await call('POST', '/eval/questions/enabled', { id: draft.json.id, enabled: true });

  const exp = await call('GET', '/eval/questions/export');
  const lines = (exp.text || '').trim().split('\n').filter(Boolean);
  const allParsed = lines.every((l) => { try { JSON.parse(l); return true; } catch { return false; } });
  check('EXP-1 导出 JSONL 每行可解析', exp.status === 200 && lines.length > 0 && allParsed,
    `${lines.length} 行`);

  const del = await call('DELETE', '/eval/questions', { ids: createdIds });
  check('DEL-1 批量删除返回条数', del.status === 200 && del.json.deleted === createdIds.length,
    JSON.stringify(del.json));
  const statsAfter = (await call('GET', '/eval/golden-set')).json;
  check('DEL-2 删完回到冒烟前的基线（不留测试题）',
    statsAfter.total === before.json.total && statsAfter.usable === before.json.usable,
    `total ${before.json.total}→${statsAfter.total}，usable ${before.json.usable}→${statsAfter.usable}`);
  const left = ((await call('GET', '/eval/questions')).json || [])
    .filter((q) => q.question.includes(String(nonce)));
  check('DEL-3 列表里查不到本次冒烟题', left.length === 0, `残留 ${left.length} 条`);
}

main().catch((e) => { console.error('冒烟脚本异常：', e); process.exitCode = 1; })
  .finally(() => {
    const failed = results.filter((r) => !r.ok);
    console.log(`\n合计 ${results.length} 项，失败 ${failed.length} 项`);
    console.log(`清理：本次创建的 ${createdIds.length} 条考题已在 DEL-1 删除（未触发任何真实评测运行）`);
    process.exitCode = failed.length ? 1 : process.exitCode || 0;
  });
