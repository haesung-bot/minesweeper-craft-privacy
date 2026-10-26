// Code.gs 시험: 가짜 Apps Script + 가짜 Gemini 환경에서 실제 코드를 돌려 본다.
// 실행: node voice-recorder/google-apps-script/code.test.js
//       node voice-recorder/google-apps-script/code.test.js --serve <포트파일>   (앱 업로더 연동 시험용 HTTP 서버)
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const http = require('http');
const assert = require('assert');
const crypto = require('crypto');

const CODE = fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8');
const UPLOAD_URL = 'https://generativelanguage.googleapis.com/upload/v1beta/files';
const BYTES_PER_SEC = 8000;

function makeEnv(opt = {}) {
  const env = { slept: 0, logs: [], requests: [] };
  const clock = { now: Date.UTC(2026, 9, 26, 1, 0, 0) }; // 2026-10-26 10:00 (한국 시간)
  env.clock = clock;
  let seq = 0;
  const filesById = new Map();
  const toSigned = (buf) => Array.from(buf, (b) => (b > 127 ? b - 256 : b));
  const toBuf = (arr) => Buffer.from(arr.map((b) => b & 255));
  const iter = (arr) => { let i = 0; return { hasNext: () => i < arr.length, next: () => arr[i++] }; };

  class Blob {
    constructor(bytes, mime, name) { this.buf = Buffer.isBuffer(bytes) ? bytes : toBuf(bytes); this.mime = mime; this.name = name; }
    getBytes() { return toSigned(this.buf); }
    getContentType() { return this.mime; }
    getName() { return this.name; }
  }
  class File {
    constructor(name, buf, mime, parent) {
      this.id = 'f' + (++seq); this.name = name; this.buf = buf; this.mime = mime;
      this.parent = parent; this.trashed = false; this.created = clock.now;
      filesById.set(this.id, this);
    }
    getId() { return this.id; }
    getName() { return this.name; }
    getSize() { return this.buf.length; }
    getBlob() { return new Blob(this.buf, this.mime, this.name); }
    getParents() { return iter([this.parent]); }
    isTrashed() { return this.trashed; }
    getDateCreated() { return new Date(this.created); }
    setContent(s) { this.buf = Buffer.from(s, 'utf8'); return this; }
  }
  class Folder {
    constructor(name) { this.id = 'd' + (++seq); this.name = name; this.files = []; this.folders = []; }
    getId() { return this.id; }
    getName() { return this.name; }
    getUrl() { return 'https://drive.google.com/drive/folders/' + this.id; }
    getFoldersByName(n) { return iter(this.folders.filter((f) => f.name === n)); }
    getFolders() { return iter(this.folders.slice()); }
    createFolder(n) { const f = new Folder(n); this.folders.push(f); return f; }
    getFilesByName(n) { return iter(this.files.filter((f) => f.name === n)); }
    getFiles() { return iter(this.files.slice()); }
    createFile(a, content, mime) {
      const f = typeof a === 'string'
        ? new File(a, Buffer.from(content, 'utf8'), mime, this)
        : new File(a.name, a.buf, a.mime, this);
      this.files.push(f);
      return f;
    }
  }
  const root = new Folder('root');
  const props = new Map();
  const triggers = [];
  const gemini = { mode: 'ok', uploads: new Map(), calls: 0, failNext: 0, slowMs: 0 };
  env.root = root; env.props = props; env.triggers = triggers; env.gemini = gemini; env.Blob = Blob;

  const response = (code, body, headers = {}) => {
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    return { getResponseCode: () => code, getContentText: () => text, getAllHeaders: () => headers, getHeaders: () => headers };
  };
  const gErr = (code, status, message, details = []) => response(code, { error: { code, status, message, details } });
  const badKey = () => gErr(400, 'INVALID_ARGUMENT', 'API key not valid. Please pass a valid API key.',
    [{ '@type': 'type.googleapis.com/google.rpc.ErrorInfo', reason: 'API_KEY_INVALID' }]);
  const toSec = (mmss) => { const [m, s] = mmss.split(':').map(Number); return m * 60 + s; };

  function fetch(url, o = {}) {
    const method = (o.method || 'get').toLowerCase();
    const headers = o.headers || {};
    env.requests.push({ url, method, headers, payload: o.payload, contentType: o.contentType });
    if (gemini.throwNext > 0) { gemini.throwNext--; throw new Error('Timeout: ' + url); }

    if (url === UPLOAD_URL) {
      assert.strictEqual(method, 'post');
      if (headers['x-goog-api-key'] !== 'KEY') return badKey();
      assert.strictEqual(headers['X-Goog-Upload-Protocol'], 'resumable');
      assert.strictEqual(headers['X-Goog-Upload-Command'], 'start');
      assert.strictEqual(headers['X-Goog-Upload-Header-Content-Type'], 'audio/m4a');
      assert.strictEqual(o.contentType, 'application/json');
      const id = 'up' + (++seq);
      gemini.uploads.set(id, {
        size: Number(headers['X-Goog-Upload-Header-Content-Length']),
        display: JSON.parse(o.payload).file.display_name,
      });
      // 대소문자가 섞인 헤더 이름도 찾는지 확인
      return response(200, '', { 'X-Goog-Upload-URL': 'https://upload.example.com/s/' + id, 'X-Goog-Upload-Status': 'active' });
    }
    if (url.startsWith('https://upload.example.com/s/')) {
      const up = gemini.uploads.get(url.split('/').pop());
      assert.strictEqual(headers['X-Goog-Upload-Command'], 'upload, finalize');
      assert.strictEqual(headers['X-Goog-Upload-Offset'], '0');
      assert(o.payload instanceof Blob, '업로드 본문은 드라이브 파일 Blob 이어야 함');
      up.bytes = o.payload.buf;
      assert.strictEqual(up.bytes.length, up.size, '선언한 크기와 실제 크기가 같아야 함');
      const id = url.split('/').pop();
      up.name = 'files/' + id;
      up.uri = 'https://generativelanguage.googleapis.com/v1beta/files/' + id;
      up.polls = 0;
      return response(200, { file: { name: up.name, uri: up.uri, mimeType: 'audio/m4a', state: 'PROCESSING' } });
    }
    const fm = /^https:\/\/generativelanguage\.googleapis\.com\/v1beta\/(files\/up\d+)$/.exec(url);
    if (fm) {
      if (headers['x-goog-api-key'] !== 'KEY') return badKey();
      const up = [...gemini.uploads.values()].find((u) => u.name === fm[1]);
      if (method === 'get') { up.polls++; return response(200, { name: up.name, uri: up.uri, state: 'ACTIVE' }); }
      if (method === 'delete') { up.deleted = true; return response(200, {}); }
    }
    const gm = /^https:\/\/generativelanguage\.googleapis\.com\/v1beta\/models\/([^:]+):generateContent$/.exec(url);
    if (gm) {
      assert.strictEqual(method, 'post');
      assert.strictEqual(o.contentType, 'application/json');
      if (headers['x-goog-api-key'] !== 'KEY') return badKey();
      gemini.calls++;
      gemini.model = gm[1];
      clock.now += gemini.slowMs;
      const body = JSON.parse(o.payload);
      gemini.bodies = (gemini.bodies || []).concat([body]);
      const mode = gemini.mode;
      const cfg = body.generationConfig || {};
      if (mode === 'quota') {
        return gErr(429, 'RESOURCE_EXHAUSTED', 'You exceeded your current quota.', [
          { '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaId: 'GenerateRequestsPerMinutePerProjectPerModel-FreeTier' }] },
          { '@type': 'type.googleapis.com/google.rpc.RetryInfo', retryDelay: '38s' },
        ]);
      }
      if (mode === 'quotaDay') {
        return gErr(429, 'RESOURCE_EXHAUSTED', 'You exceeded your current quota.', [
          { '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier' }] },
        ]);
      }
      if (mode === 'e503' || (gemini.failNext > 0 && gemini.failNext--)) return gErr(503, 'UNAVAILABLE', 'The model is overloaded.');
      if (mode === 'notfound') return gErr(404, 'NOT_FOUND', 'models/' + gm[1] + ' is not found for API version v1beta');
      if (mode === 'thinking400' && cfg.thinkingConfig) return gErr(400, 'INVALID_ARGUMENT', 'Thinking level is not supported for this model.');
      if (mode === 'safety400' && body.safetySettings) return gErr(400, 'INVALID_ARGUMENT', 'Invalid value at \'safety_settings[0].category\'');
      if (mode === 'blocked') return response(200, { promptFeedback: { blockReason: 'OTHER' } });

      const parts = body.contents[0].parts;
      const audio = parts.find((p) => p.inline_data || p.file_data);
      const prompt = (parts.find((p) => typeof p.text === 'string') || {}).text || '';
      if (!audio) return response(200, { candidates: [{ content: { parts: [{ text: '확인' }] }, finishReason: 'STOP' }] });
      let bytes;
      let how;
      if (audio.inline_data) {
        assert.strictEqual(audio.inline_data.mime_type, 'audio/m4a');
        bytes = Buffer.from(audio.inline_data.data, 'base64');
        how = 'inline';
      } else {
        assert.strictEqual(audio.file_data.mime_type, 'audio/m4a');
        const up = [...gemini.uploads.values()].find((u) => u.uri === audio.file_data.file_uri);
        assert(up && !up.deleted, '참조한 업로드 파일이 살아 있어야 함');
        bytes = up.bytes;
        how = 'file';
      }
      const sha = crypto.createHash('sha256').update(bytes).digest('hex').slice(0, 8);
      const r = /(\d\d:\d\d)부터 (끝까지|(\d\d:\d\d)까지)/.exec(prompt);
      const span = r ? r[1] + '-' + (r[3] || '끝') : 'all';
      const spanSec = r ? (r[3] ? toSec(r[3]) - toSec(r[1]) : 0) : Infinity;
      if (mode === 'maxtokens600' && spanSec >= 600) {
        return response(200, { candidates: [{ content: { parts: [{ text: '[00:00] 아아아' }] }, finishReason: 'MAX_TOKENS' }] });
      }
      const text = '[' + (r ? r[1] : '00:00') + '] 화자1: ' + how + ' ' + sha + ' ' + span;
      return response(200, { candidates: [{ content: { role: 'model', parts: [{ text: '생각 중', thought: true }, { text }] }, finishReason: 'STOP' }] });
    }
    return response(404, 'unknown ' + url);
  }

  const sandbox = {
    __clock: clock,
    console: { log: (...a) => env.logs.push(a.join(' ')), warn: (...a) => env.logs.push('WARN ' + a.join(' ')) },
    Logger: { log: (s) => env.logs.push(String(s)) },
    MimeType: { PLAIN_TEXT: 'text/plain' },
    DriveApp: {
      getRootFolder: () => root,
      getFileById: (id) => { const f = filesById.get(id); if (!f || f.gone) throw new Error('No item with the given ID could be found'); return f; },
    },
    Utilities: {
      base64Decode: (s) => toSigned(Buffer.from(s, 'base64')),
      base64Encode: (arr) => toBuf(arr).toString('base64'),
      newBlob: (bytes, mime, name) => new Blob(bytes, mime, name),
      sleep: (ms) => { clock.now += ms; env.slept += ms; },
      formatDate: (d, tz, fmt) => {
        assert.strictEqual(tz, 'Asia/Seoul');
        const k = new Date(d.getTime() + 9 * 3600 * 1000);
        const p = (n) => String(n).padStart(2, '0');
        return fmt.replace('yyyy', k.getUTCFullYear()).replace('MM', p(k.getUTCMonth() + 1)).replace('dd', p(k.getUTCDate()))
          .replace('HH', p(k.getUTCHours())).replace('mm', p(k.getUTCMinutes()));
      },
    },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: (s) => ({ content: s, setMimeType() { return this; } }) },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (k) => (props.has(k) ? props.get(k) : null),
        setProperty: (k, v) => { props.set(k, String(v)); },
        deleteProperty: (k) => { props.delete(k); },
        getProperties: () => Object.fromEntries(props),
        getKeys: () => [...props.keys()],
      }),
    },
    ScriptApp: {
      getProjectTriggers: () => triggers.slice(),
      deleteTrigger: (t) => { const i = triggers.indexOf(t); if (i >= 0) triggers.splice(i, 1); },
      newTrigger: (fn) => ({ timeBased: () => ({ everyMinutes: (n) => ({ create: () => {
        const t = { fn, n, getHandlerFunction: () => fn }; triggers.push(t); return t;
      } }) }) }),
    },
    UrlFetchApp: { fetch },
  };
  vm.createContext(sandbox);
  vm.runInContext('Date.now = function () { return __clock.now; };', sandbox);
  const code = CODE
    .replace(/const SECRET = '[^']*';/, "const SECRET = '" + (opt.secret ?? 'my pw') + "';")
    .replace(/const GEMINI_API_KEY = '[^']*';/, "const GEMINI_API_KEY = '" + (opt.key ?? 'KEY') + "';");
  assert.notStrictEqual(code, CODE);
  vm.runInContext(code, sandbox, { filename: 'Code.gs' });
  env.gs = sandbox;
  env.run = (expr) => vm.runInContext(expr, sandbox);

  env.upload = (name, buf, folder) => JSON.parse(sandbox.doPost({ postData: { contents: JSON.stringify({
    secret: opt.secret ?? 'my pw', name, folder: folder || name.slice(0, 10), mime: 'audio/mp4', size: buf.length, data: buf.toString('base64'),
  }) } }).content);
  env.find = (name) => {
    const walk = (f) => f.files.find((x) => x.name === name) || f.folders.map(walk).find(Boolean);
    return walk(root);
  };
  env.text = (name) => { const f = env.find(name); return f ? f.buf.toString('utf8') : null; };
  env.queue = () => [...props.keys()].filter((k) => k.startsWith('q:')).map((k) => Object.assign({ id: k.slice(2) }, JSON.parse(props.get(k))));
  env.generateCalls = () => env.requests.filter((r) => r.url.endsWith(':generateContent')).length;
  return env;
}

function audio(seconds, seed = seconds) {
  const buf = Buffer.alloc(seconds * BYTES_PER_SEC);
  let x = seed * 2654435761 >>> 0;
  for (let i = 0; i < buf.length; i++) { x = (x * 1103515245 + 12345) >>> 0; buf[i] = x >>> 24; }
  return buf;
}
const sha8 = (buf) => crypto.createHash('sha256').update(buf).digest('hex').slice(0, 8);
const MIN = 60 * 1000;

// ───────────── 시험들 ─────────────
const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('짧은 녹음: 업로드 → 대기열 → 바로 보내서 받아쓰기 → 옆에 .txt', () => {
  const env = makeEnv();
  const buf = audio(65);
  const r = env.upload('2026-10-26_09-58-00_1m05s.m4a', buf);
  assert.strictEqual(r.ok, true);
  assert.strictEqual(env.queue().length, 1);
  env.gs.transcribePending();
  const txt = env.text('2026-10-26_09-58-00_1m05s.txt');
  assert.strictEqual(txt, '녹음 2026-10-26 09:58:00 · 1분 05초\n\n[00:00] 화자1: inline ' + sha8(buf) + ' all\n');
  const txtFile = env.find('2026-10-26_09-58-00_1m05s.txt');
  assert.strictEqual(txtFile.parent.name, '2026-10-26');
  assert.strictEqual(txtFile.mime, 'text/plain');
  assert.strictEqual(env.queue().length, 0);
  assert(env.props.get('stt_last_ok').endsWith('2026-10-26_09-58-00_1m05s.m4a'));
  assert(!env.props.has('stt_busy'));
  const body = env.gemini.bodies[0];
  assert.strictEqual(env.gemini.model, 'gemini-3.5-flash-lite');
  assert.strictEqual(body.contents[0].role, 'user');
  assert.deepStrictEqual(body.generationConfig.thinkingConfig, { thinkingLevel: 'minimal' });
  assert.strictEqual(body.generationConfig.maxOutputTokens, 8192);
  assert.strictEqual(body.generationConfig.temperature, undefined);
  assert.strictEqual(body.safetySettings.length, 4);
  const prompt = body.contents[0].parts[1].text;
  assert(prompt.includes('받아써') && !prompt.includes('구간'));
});

test('긴 녹음(25분): Files API 업로드 → 10분씩 3번 → 합쳐 저장 → 업로드 삭제', () => {
  const env = makeEnv();
  const buf = audio(1500);
  env.upload('2026-10-26_09-30-00_25m00s.m4a', buf);
  env.gs.transcribePending();
  const s = sha8(buf);
  assert.strictEqual(env.text('2026-10-26_09-30-00_25m00s.txt'), '녹음 2026-10-26 09:30:00 · 25분 00초\n\n' +
    '[00:00] 화자1: file ' + s + ' 00:00-10:00\n' +
    '[10:00] 화자1: file ' + s + ' 10:00-20:00\n' +
    '[20:00] 화자1: file ' + s + ' 20:00-끝\n');
  const up = [...env.gemini.uploads.values()][0];
  assert.strictEqual(up.size, buf.length);
  assert.strictEqual(up.display, '2026-10-26_09-30-00_25m00s.m4a');
  assert(up.polls >= 1 && up.deleted, '처리 대기 확인 후 삭제');
  assert.strictEqual(env.generateCalls(), 3);
  assert(env.slept >= 2 * 6500, '요청 사이 간격 유지');
});

test('분당 한도 초과: 시도 횟수 그대로, 잠시 쉬었다가 이어서', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30, 1));
  env.upload('2026-10-26_09-01-00_30s.m4a', audio(30, 2));
  env.gemini.mode = 'quota';
  env.gs.transcribePending();
  assert.strictEqual(env.generateCalls(), 1);
  assert.deepStrictEqual(env.queue().map((q) => q.n), [0, 0]);
  assert.strictEqual(Number(env.props.get('stt_pause_until')), env.clock.now + 43000);
  env.gs.transcribePending();
  assert.strictEqual(env.generateCalls(), 1, '쉬는 중에는 요청 안 함');
  env.clock.now += MIN;
  env.gemini.mode = 'ok';
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
  assert(env.text('2026-10-26_09-00-00_30s.txt') && env.text('2026-10-26_09-01-00_30s.txt'));
});

test('하루 한도 초과: 1시간 쉼', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.gemini.mode = 'quotaDay';
  env.gs.transcribePending();
  assert.strictEqual(Number(env.props.get('stt_pause_until')), env.clock.now + 60 * MIN);
  assert(env.props.get('stt_last_error').includes('하루 한도'));
});

test('API 키 오류: 설정 문제로 멈추고 시도 횟수는 안 셈, setup은 오류 안내', () => {
  const env = makeEnv({ key: 'WRONG' });
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.gs.transcribePending();
  assert.deepStrictEqual(env.queue().map((q) => q.n), [0]);
  assert(env.props.get('stt_last_error').includes('API key'));
  assert.throws(() => env.gs.setup(), /Gemini 연결 실패.*API key/s);
  assert.strictEqual(env.triggers.length, 0);
});

test('모델 이름 오류(404)도 설정 문제', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.gemini.mode = 'notfound';
  env.gs.transcribePending();
  assert.deepStrictEqual(env.queue().map((q) => q.n), [0]);
  assert(env.props.get('stt_last_error').includes('404'));
});

test('thinking 설정을 모델이 거부하면 빼고 다시, 다음부터 계속 뺌', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30, 1));
  env.upload('2026-10-26_09-01-00_30s.m4a', audio(30, 2));
  env.gemini.mode = 'thinking400';
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
  assert.strictEqual(env.props.get('stt_no_thinking'), '1');
  assert.strictEqual(env.generateCalls(), 3);
  assert.strictEqual(env.gemini.bodies[2].generationConfig.thinkingConfig, undefined);
});

test('safety 설정을 거부하면 빼고 다시', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.gemini.mode = 'safety400';
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
  assert.strictEqual(env.props.get('stt_no_safety'), '1');
});

test('서버 장애(503): 시도 횟수 +1, 5분 뒤 재시도, 이번 실행은 멈춤', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30, 1));
  env.upload('2026-10-26_09-01-00_30s.m4a', audio(30, 2));
  env.gemini.mode = 'e503';
  env.gs.transcribePending();
  let q = env.queue().sort((a, b) => a.t - b.t);
  assert.deepStrictEqual(q.map((x) => x.n), [1, 0]);
  assert.strictEqual(q[0].at, env.clock.now + 5 * MIN);
  assert(q[0].err.includes('503'));
  env.gs.transcribePending(); // 첫 파일은 아직 기다리는 중 → 두 번째 파일 시도
  q = env.queue().sort((a, b) => a.t - b.t);
  assert.deepStrictEqual(q.map((x) => x.n), [1, 1]);
  env.gemini.mode = 'ok';
  env.clock.now += 5 * MIN;
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
});

test('연결 시간 초과(예외)도 재시도 대상', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.gemini.throwNext = 1;
  env.gs.transcribePending();
  assert.deepStrictEqual(env.queue().map((q) => q.n), [1]);
  assert(env.props.get('stt_last_error').includes('Timeout'));
});

test('계속 거부되는 파일: 5번 시도 후 실패 메모를 남기고 다음 파일로', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.gemini.mode = 'blocked';
  for (let i = 0; i < 5; i++) {
    env.gs.transcribePending();
    env.clock.now += 7 * 60 * MIN;
  }
  assert.strictEqual(env.queue().length, 0);
  assert.strictEqual(env.text('2026-10-26_09-00-00_30s.txt'),
    '녹음 2026-10-26 09:00:00 · 30초\n\n(받아쓰기 실패: Gemini가 거부함(OTHER))\n');
  assert.strictEqual(env.generateCalls(), 5);
});

test('결과가 잘리면(MAX_TOKENS) 다음 시도에서 5분씩 나눠서', () => {
  const env = makeEnv();
  const buf = audio(600);
  env.upload('2026-10-26_09-00-00_10m00s.m4a', buf);
  env.gemini.mode = 'maxtokens600';
  env.gs.transcribePending();
  assert.deepStrictEqual(env.queue().map((q) => q.n), [1]);
  env.clock.now += 5 * MIN;
  env.gs.transcribePending();
  const s = sha8(buf);
  assert.strictEqual(env.text('2026-10-26_09-00-00_10m00s.txt'), '녹음 2026-10-26 09:00:00 · 10분 00초\n\n' +
    '[00:00] 화자1: file ' + s + ' 00:00-05:00\n[05:00] 화자1: file ' + s + ' 05:00-끝\n');
});

test('휴지통에 버린 녹음은 대기열에서 빠짐', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.find('2026-10-26_09-00-00_30s.m4a').trashed = true;
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
  assert.strictEqual(env.generateCalls(), 0);
});

test('이전 실행이 처리 중이면 건너뛰고, 10분 넘게 남은 표시는 무시', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.props.set('stt_busy', String(env.clock.now - 2 * MIN));
  env.gs.transcribePending();
  assert.strictEqual(env.generateCalls(), 0);
  env.props.set('stt_busy', String(env.clock.now - 11 * MIN));
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
});

test('한 번 실행에 3분 넘게 걸리면 남은 파일은 다음 실행에서', () => {
  const env = makeEnv();
  for (let i = 0; i < 40; i++) env.upload('2026-10-26_08-' + String(i).padStart(2, '0') + '-00_20s.m4a', audio(20, i + 1));
  env.gs.transcribePending();
  const left = env.queue().length;
  assert(left > 5 && left < 20, 'left=' + left);
  assert(env.queue().every((q) => q.n === 0));
  env.clock.now += 5 * MIN;
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
});

test('파일 중간에 시간이 모자라면 멈추고, 업로드는 지우고, 다음에 다시', () => {
  const env = makeEnv();
  env.upload('2026-10-26_08-00-00_35m00s.m4a', audio(2100));
  env.gemini.slowMs = 100 * 1000;
  env.gs.transcribePending();
  const q = env.queue();
  assert.strictEqual(q.length, 1);
  assert.strictEqual(q[0].n, 1);
  assert.strictEqual(env.generateCalls(), 3);
  assert([...env.gemini.uploads.values()].every((u) => u.deleted));
  assert(!env.props.has('stt_busy'));
});

test('setup: 키 확인, 트리거 1개만, 최근 7일 백필, 시험 1개 처리', () => {
  const env = makeEnv();
  const day = (name) => env.root.folders.find((f) => f.name === '폰녹음').folders.find((f) => f.name === name) ||
    env.root.folders.find((f) => f.name === '폰녹음').createFolder(name);
  env.root.createFolder('폰녹음');
  const mk = (folder, name, created) => {
    const f = day(folder).createFile(new env.Blob(audio(30, name.length), 'audio/mp4', name));
    f.created = created;
    return f;
  };
  mk('2026-10-25', '2026-10-25_10-00-00_30s.m4a', env.clock.now - 20 * 60 * MIN);
  day('2026-10-25').createFile('2026-10-25_10-00-00_30s.txt', '이미 있음', 'text/plain');
  mk('2026-10-25', '2026-10-25_11-00-00_30s.m4a', env.clock.now - 19 * 60 * MIN);
  mk('2026-10-24', '2026-10-24_11-00-00_30s.m4a', env.clock.now - 43 * 60 * MIN);
  mk('2026-10-10', '2026-10-10_11-00-00_30s.m4a', env.clock.now - 16 * 24 * 60 * MIN);
  mk('기타', 'x_30s.m4a', env.clock.now - 60 * MIN);
  env.gs.setup();
  assert.strictEqual(env.triggers.length, 1);
  assert.strictEqual(env.triggers[0].fn, 'transcribePending');
  assert.strictEqual(env.triggers[0].n, 5);
  assert(env.logs.some((l) => l.includes('✅ Gemini 연결 성공')));
  assert(env.logs.some((l) => l.includes('3개를 받아쓰기 대기열')), env.logs.join('\n'));
  assert(env.logs.some((l) => l.includes('✅ 시험 받아쓰기 완료: ') && l.includes('2026-10-24_11-00-00_30s.m4a')), env.logs.join('\n'));
  assert(env.text('2026-10-24_11-00-00_30s.txt'));
  assert.strictEqual(env.text('2026-10-25_10-00-00_30s.txt'), '이미 있음');
  assert.strictEqual(env.queue().length, 2);
  env.gs.setup();
  assert.strictEqual(env.triggers.length, 1, '다시 실행해도 트리거는 1개');
  env.gs.transcribePending();
  assert.strictEqual(env.queue().length, 0);
  assert(env.text('x_30s.txt').startsWith('녹음 x_30s.m4a · 30초'));
});

test('setup: 키가 비어 있으면 안내', () => {
  const env = makeEnv({ key: '' });
  assert.throws(() => env.gs.setup(), /GEMINI_API_KEY/);
});

test('키가 비어 있으면 대기열에 안 넣고 아무것도 안 함, ping에 stt:off', () => {
  const env = makeEnv({ key: '' });
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  assert.strictEqual(env.queue().length, 0);
  env.gs.transcribePending();
  assert.strictEqual(env.requests.length, 0);
  const ping = JSON.parse(env.gs.doPost({ postData: { contents: JSON.stringify({ secret: 'my pw', ping: true }) } }).content);
  assert.strictEqual(ping.ok, true);
  assert.strictEqual(ping.stt, 'off');
});

test('6시간마다 최근 2일 폴더를 훑어서 빠진 녹음을 채움', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  env.props.clear(); // 예전 버전 배포가 받아서 대기열에 없던 상황
  env.gs.transcribePending();
  assert(env.text('2026-10-26_09-00-00_30s.txt'));
  env.upload('2026-10-26_09-05-00_30s.m4a', audio(30, 9));
  [...env.props.keys()].filter((k) => k.startsWith('q:')).forEach((k) => env.props.delete(k));
  env.gs.transcribePending();
  assert.strictEqual(env.text('2026-10-26_09-05-00_30s.txt'), null, '6시간 전에는 다시 안 훑음');
  env.clock.now += 6 * 60 * MIN + 1;
  env.gs.transcribePending();
  assert(env.text('2026-10-26_09-05-00_30s.txt'));
});

test('doGet: 비밀번호 없으면 기본 정보만, 있으면 대기 개수', () => {
  const env = makeEnv();
  env.upload('2026-10-26_09-00-00_30s.m4a', audio(30));
  const open = JSON.parse(env.gs.doGet({ parameter: {} }).content);
  assert.strictEqual(open.stt, 'on');
  assert.strictEqual(open.queued, undefined);
  const mine = JSON.parse(env.gs.doGet({ parameter: { secret: 'my pw' } }).content);
  assert.strictEqual(mine.queued, 1);
});

test('재전송(같은 이름·크기)은 새 파일·대기열을 만들지 않음', () => {
  const env = makeEnv();
  const buf = audio(30);
  env.upload('2026-10-26_09-00-00_30s.m4a', buf);
  const again = env.upload('2026-10-26_09-00-00_30s.m4a', buf);
  assert.strictEqual(again.dup, true);
  assert.strictEqual(env.queue().length, 1);
});

test('파일 이름에서 길이 읽기', () => {
  const env = makeEnv();
  assert.strictEqual(env.run("durationSec_('2026-10-24_14-35-12_1m05s.m4a', 0)"), 65);
  assert.strictEqual(env.run("durationSec_('2026-10-24_14-35-12_45s.m4a', 0)"), 45);
  assert.strictEqual(env.run("durationSec_('2026-10-24_14-35-12_10m00s_2.m4a', 0)"), 600);
  assert.strictEqual(env.run("durationSec_('2026-10-24_14-35-12.m4a', 80000)"), 10);
  assert.strictEqual(env.run("titleLine_('2026-10-24_14-35-12.m4a', 80000)"), '녹음 2026-10-24 14:35:12 · 약 10초');
});

// ───────────── 실행 ─────────────

function serve(portFile) {
  const env = makeEnv({ secret: 'my pw', key: 'KEY' });
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      if (req.method === 'POST' && req.url.endsWith('/exec')) {
        const out = env.gs.doPost({ postData: { contents: Buffer.concat(chunks).toString('utf8') } });
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(out.content);
      } else if (req.url === '/run') {
        env.gs.transcribePending();
        const list = [];
        const walk = (f, p) => { f.files.forEach((x) => list.push(p + x.name + ' ' + x.buf.length + (x.name.endsWith('.txt') ? ' | ' + x.buf.toString('utf8').replace(/\n/g, ' ⏎ ') : ''))); f.folders.forEach((d) => walk(d, p + d.name + '/')); };
        walk(env.root, '');
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(list.join('\n') + '\nqueue=' + env.queue().length + '\n');
      } else {
        res.writeHead(404);
        res.end();
      }
    });
  });
  server.listen(0, '127.0.0.1', () => fs.writeFileSync(portFile, String(server.address().port)));
}

if (process.argv[2] === '--serve') {
  serve(process.argv[3]);
} else {
  let failed = 0;
  for (const t of tests) {
    try {
      t.fn();
      console.log('PASS ' + t.name);
    } catch (e) {
      failed++;
      console.log('FAIL ' + t.name + '\n  ' + (e.stack || e).toString().split('\n').slice(0, 4).join('\n  '));
    }
  }
  console.log('passed=' + (tests.length - failed) + ' failed=' + failed);
  process.exit(failed ? 1 : 0);
}
