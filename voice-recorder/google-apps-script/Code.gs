/**
 * 소리 감지 녹음기 → 구글 드라이브 저장 + 자동 받아쓰기(Gemini)
 *
 * ■ 처음 설치 (PC에서 5분)
 * 1. https://script.google.com → [새 프로젝트] → 이 코드를 통째로 붙여넣기
 * 2. 아래 SECRET을 나만 아는 비밀번호로 바꾸고 저장(Ctrl+S)
 * 3. [배포] → [새 배포] → 유형: 웹 앱 (실행: 나 / 액세스: 모든 사용자) → [배포] → 권한 허용
 *    (경고가 나오면 '고급' → '(안전하지 않음)으로 이동' → 허용)
 * 4. 나온 웹 앱 URL(…/exec)을 폰 앱에 붙여넣고 같은 비밀번호 입력
 *
 * ■ 받아쓰기 켜기
 * 1. https://aistudio.google.com/apikey → [API 키 만들기] → 복사해서 아래 GEMINI_API_KEY에 붙여넣고 저장
 * 2. 위쪽 함수 목록에서 setup 선택 → [실행] → 권한 허용 → 실행 로그에 ✅ 가 나오면 성공
 * 3. [배포] → [배포 관리] → 연필 → 버전: [새 버전] → [배포]  (주소는 그대로)
 *
 * 받아쓰기는 녹음 파일 옆에 같은 이름의 .txt 로 저장돼요 (5분마다 처리).
 * 코드를 고친 뒤에는 항상 '새 버전 배포'를 해야 폰 업로드에 반영돼요.
 */

const SECRET = 'CHANGE_ME';        // ← 폰 앱에 넣은 비밀번호와 같게
const GEMINI_API_KEY = '';         // ← 받아쓰기용 Gemini API 키 (비워 두면 받아쓰기 안 함)
const ROOT_FOLDER = '폰녹음';       // 드라이브에 만들어질 폴더 이름
const GEMINI_MODEL = 'gemini-3.5-flash-lite'; // 더 정확하게: 'gemini-3.8-flash' (대신 무료 한도가 훨씬 적음)

// ───────────── 폰에서 업로드 받기 ─────────────

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return reply_({ ok: false, code: 'bad_request', error: '요청 내용이 비어 있어요' });
    }
    if (SECRET === 'CHANGE_ME' || !SECRET) {
      return reply_({ ok: false, code: 'no_secret', error: '스크립트의 SECRET 값을 먼저 바꾸고 다시 배포해 주세요' });
    }
    const req = JSON.parse(e.postData.contents);
    if (req.secret !== SECRET) {
      return reply_({ ok: false, code: 'bad_secret', error: '비밀번호가 스크립트의 SECRET과 달라요' });
    }

    const root = folder_(DriveApp.getRootFolder(), ROOT_FOLDER);
    if (req.ping) {
      return reply_({ ok: true, pong: true, folderUrl: root.getUrl(), stt: GEMINI_API_KEY ? 'on' : 'off' });
    }
    if (!req.data || !req.name) {
      return reply_({ ok: false, code: 'bad_request', error: '파일 정보가 없어요' });
    }

    const name = clean_(req.name, 'recording.m4a');
    const target = folder_(root, clean_(req.folder, '기타'));
    const bytes = Utilities.base64Decode(req.data);

    // 같은 이름·크기 파일이 이미 있으면(재전송) 새로 만들지 않는다
    const same = target.getFilesByName(name);
    while (same.hasNext()) {
      const f = same.next();
      if (f.getSize() === bytes.length) {
        return reply_({ ok: true, dup: true, id: f.getId(), size: f.getSize() });
      }
    }

    const file = target.createFile(Utilities.newBlob(bytes, req.mime || 'audio/mp4', name));
    if (GEMINI_API_KEY && /\.m4a$/i.test(name)) {
      queue_(PropertiesService.getScriptProperties(), file.getId(), Date.now());
    }
    return reply_({ ok: true, id: file.getId(), size: file.getSize() });
  } catch (err) {
    return reply_({ ok: false, code: 'server', error: String((err && err.message) || err) });
  }
}

/** 브라우저로 주소를 열면 동작 확인. 주소 끝에 ?secret=비밀번호 를 붙이면 받아쓰기 상태도 보여요. */
function doGet(e) {
  const out = { ok: true, message: '녹음 업로드 스크립트가 동작 중이에요', stt: GEMINI_API_KEY ? 'on' : 'off' };
  if (e && e.parameter && e.parameter.secret === SECRET && SECRET !== 'CHANGE_ME') {
    const props = PropertiesService.getScriptProperties();
    out.queued = queueItems_(props).length;
    out.lastOk = props.getProperty(P_LAST_OK) || '';
    out.lastError = props.getProperty(P_LAST_ERROR) || '';
  }
  return reply_(out);
}

// ───────────── 받아쓰기 켜기 (편집기에서 한 번 실행) ─────────────

function setup() {
  if (!GEMINI_API_KEY) {
    throw new Error('GEMINI_API_KEY에 API 키를 붙여넣고 저장(Ctrl+S)한 뒤 다시 실행해 주세요');
  }
  const props = PropertiesService.getScriptProperties();
  const test = callGemini_('models/' + GEMINI_MODEL + ':generateContent', {
    contents: [{ role: 'user', parts: [{ text: '확인' }] }],
    generationConfig: { maxOutputTokens: 64 },
  });
  if (test.code !== 200) {
    throw new Error('Gemini 연결 실패 — ' + failFromResponse_(test).message +
      '\nAPI 키와 GEMINI_MODEL 이름을 확인해 주세요.');
  }

  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'transcribePending') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('transcribePending').timeBased().everyMinutes(5).create();
  [P_PAUSE, P_NO_THINKING, P_NO_SAFETY, P_LAST_ERROR].forEach(function (k) { props.deleteProperty(k); });
  Logger.log('✅ Gemini 연결 성공! 5분마다 자동 받아쓰기를 하도록 켰어요.');

  const added = backfill_(props, 7);
  props.setProperty(P_LAST_SCAN, String(Date.now()));
  if (added) Logger.log('최근 7일 녹음 ' + added + '개를 받아쓰기 대기열에 넣었어요.');

  if (queueItems_(props).length && lockRun_(props)) {
    try {
      const done = runQueue_(props, 1);
      Logger.log(done
        ? '✅ 시험 받아쓰기 완료: ' + props.getProperty(P_LAST_OK) + ' (드라이브에서 .txt 확인)'
        : '시험 받아쓰기는 실패했어요: ' + (props.getProperty(P_LAST_ERROR) || '') + ' (5분마다 다시 시도해요)');
    } finally {
      props.deleteProperty(P_BUSY);
    }
  }
  Logger.log('마지막으로 [배포] → [배포 관리] → 연필 → 새 버전 → [배포] 를 해야 새 녹음도 받아쓰기 돼요.');
}

/** 5분마다 자동 실행: 대기열의 녹음을 받아쓴다. (편집기에서 직접 실행해도 돼요) */
function transcribePending() {
  if (!GEMINI_API_KEY) return;
  const props = PropertiesService.getScriptProperties();
  if (Number(props.getProperty(P_PAUSE) || 0) > Date.now()) return; // 무료 한도 초과로 쉬는 중
  if (!lockRun_(props)) return; // 이전 실행이 아직 처리 중
  try {
    // 혹시 대기열에 못 들어간 파일이 있으면 6시간마다 최근 2일 폴더를 훑어서 채운다
    if (Date.now() - Number(props.getProperty(P_LAST_SCAN) || 0) > STT.scanEveryMs) {
      backfill_(props, 2);
      props.setProperty(P_LAST_SCAN, String(Date.now()));
    }
    runQueue_(props, 0);
  } finally {
    props.deleteProperty(P_BUSY);
  }
}

// ───────────── 받아쓰기 처리 ─────────────

const STT = {
  inlineMaxBytes: 5 * 1024 * 1024, // 이보다 작고 한 번에 할 수 있으면 바로 보내고, 크면 업로드 후 처리
  windowSec: [600, 300],           // 첫 시도는 10분씩, 다시 할 때는 5분씩 나눠서 (요청당 60초 제한 대비)
  maxAttempts: 5,
  retryMin: [5, 30, 120, 360],     // 실패 후 다시 시도까지 기다리는 시간(분)
  startBudgetMs: 3 * 60 * 1000,    // 이 시간이 지나면 새 파일은 다음 실행에서
  hardBudgetMs: 4.5 * 60 * 1000,   // 파일 중간이라도 이 시간이 지나면 멈춤 (Apps Script 6분 제한)
  minGapMs: 6500,                  // 요청 사이 최소 간격 (분당 한도 보호)
  maxOutputTokens: 8192,
  scanEveryMs: 6 * 60 * 60 * 1000,
  bytesPerSec: 8000,               // 앱 녹음 64kbps 기준 (파일 이름에 길이가 없을 때만 사용)
};

const GEMINI_BASE = 'https://generativelanguage.googleapis.com/v1beta/';
const GEMINI_UPLOAD = 'https://generativelanguage.googleapis.com/upload/v1beta/files';
const AUDIO_MIME = 'audio/m4a';
const Q_PREFIX = 'q:';
const P_BUSY = 'stt_busy';
const P_PAUSE = 'stt_pause_until';
const P_NO_THINKING = 'stt_no_thinking';
const P_NO_SAFETY = 'stt_no_safety';
const P_LAST_SCAN = 'stt_last_scan';
const P_LAST_OK = 'stt_last_ok';
const P_LAST_ERROR = 'stt_last_error';
const DURATION_RE = /_(?:(\d+)m)?(\d+)s(?:_\d+)?\.m4a$/i;
const SAFETY_OFF = ['HARM_CATEGORY_HARASSMENT', 'HARM_CATEGORY_HATE_SPEECH',
  'HARM_CATEGORY_SEXUALLY_EXPLICIT', 'HARM_CATEGORY_DANGEROUS_CONTENT']
  .map(function (c) { return { category: c, threshold: 'BLOCK_NONE' }; });

function runQueue_(props, maxFiles) {
  const ctx = { props: props, started: Date.now(), lastCall: 0 };
  let done = 0;
  const items = queueItems_(props);
  for (let i = 0; i < items.length; i++) {
    if (maxFiles && done >= maxFiles) break;
    if (Date.now() - ctx.started > STT.startBudgetMs) break;
    if (items[i].at > Date.now()) continue; // 다시 시도할 시간이 아직 안 됨
    const r = processItem_(ctx, items[i]);
    if (r === 'stop') break;
    if (r === 'done') done++;
  }
  return done;
}

function processItem_(ctx, item) {
  const props = ctx.props;
  let file;
  try {
    file = DriveApp.getFileById(item.id);
    if (file.isTrashed()) throw new Error('trashed');
  } catch (err) {
    props.deleteProperty(Q_PREFIX + item.id); // 지워진 파일
    return 'skip';
  }
  const attempt = item.n;
  const failed = { t: item.t, n: attempt + 1, at: Date.now() + 60 * 1000, err: item.err };
  // 실행이 강제로 끊겨도 같은 파일에서 끝없이 반복하지 않도록 시도 횟수를 먼저 기록
  saveItem_(props, item.id, failed);
  try {
    const text = transcribeFile_(ctx, file, attempt);
    writeTranscript_(file, text);
    props.deleteProperty(Q_PREFIX + item.id);
    props.setProperty(P_LAST_OK, stamp_() + ' ' + file.getName());
    return 'done';
  } catch (err) {
    const f = asFailure_(err);
    props.setProperty(P_LAST_ERROR, stamp_() + ' ' + f.message);
    console.warn(file.getName() + ': ' + f.message);
    if (f.kind === 'config' || f.kind === 'quota') {
      saveItem_(props, item.id, item); // 이 파일 탓이 아니니 시도 횟수 되돌리기
      if (f.kind === 'quota') props.setProperty(P_PAUSE, String(Date.now() + f.pauseMs));
      return 'stop';
    }
    if (f.kind === 'time') return 'stop'; // 다음 실행에서 이어서
    if (attempt + 1 >= STT.maxAttempts) {
      writeTranscript_(file, '(받아쓰기 실패: ' + f.message + ')');
      props.deleteProperty(Q_PREFIX + item.id);
      return 'skip';
    }
    failed.at = Date.now() + STT.retryMin[Math.min(attempt, STT.retryMin.length - 1)] * 60 * 1000;
    failed.err = f.message;
    saveItem_(props, item.id, failed);
    return f.kind === 'retry' ? 'stop' : 'skip'; // 서버 장애로 보이면 이번 실행은 여기까지
  }
}

function transcribeFile_(ctx, file, attempt) {
  const size = file.getSize();
  const dur = durationSec_(file.getName(), size);
  const win = STT.windowSec[Math.min(attempt, STT.windowSec.length - 1)];
  if (dur <= win + 30 && size <= STT.inlineMaxBytes) {
    const data = Utilities.base64Encode(file.getBlob().getBytes());
    return generate_(ctx, { inline_data: { mime_type: AUDIO_MIME, data: data } }, null);
  }
  const uploaded = uploadAudio_(file, size);
  try {
    const parts = [];
    for (let start = 0; start < dur; start += win) {
      if (Date.now() - ctx.started > STT.hardBudgetMs) {
        throw fail_('time', '실행 시간이 모자라서 다음 실행에서 이어서 해요');
      }
      const end = start + win;
      const audio = { file_data: { mime_type: AUDIO_MIME, file_uri: uploaded.uri } };
      parts.push(generate_(ctx, audio, { start: start, end: end, last: end >= dur }));
    }
    return parts.join('\n');
  } finally {
    deleteUploaded_(uploaded.name);
  }
}

function generate_(ctx, audioPart, range) {
  const props = ctx.props;
  const config = { maxOutputTokens: STT.maxOutputTokens };
  const body = {
    contents: [{ role: 'user', parts: [audioPart, { text: prompt_(range) }] }],
    generationConfig: config,
  };
  const useThinking = props.getProperty(P_NO_THINKING) !== '1';
  const useSafety = props.getProperty(P_NO_SAFETY) !== '1';
  if (useThinking) config.thinkingConfig = { thinkingLevel: 'minimal' };
  if (useSafety) body.safetySettings = SAFETY_OFF;
  const path = 'models/' + GEMINI_MODEL + ':generateContent';

  let r = paced_(ctx, path, body);
  // 모델이 지원하지 않는 설정이라고 하면 빼고 다시 (다음부터는 계속 뺌)
  if (r.code === 400 && useThinking && /thinking/i.test(r.message)) {
    props.setProperty(P_NO_THINKING, '1');
    delete config.thinkingConfig;
    r = paced_(ctx, path, body);
  }
  if (r.code === 400 && useSafety && /safety|harm/i.test(r.message)) {
    props.setProperty(P_NO_SAFETY, '1');
    delete body.safetySettings;
    r = paced_(ctx, path, body);
  }
  if (r.code !== 200) throw failFromResponse_(r);

  const cand = r.json && r.json.candidates && r.json.candidates[0];
  if (!cand) {
    const block = r.json && r.json.promptFeedback && r.json.promptFeedback.blockReason;
    throw fail_('bad', block ? 'Gemini가 거부함(' + block + ')' : 'Gemini 응답이 비어 있어요');
  }
  const text = ((cand.content && cand.content.parts) || [])
    .filter(function (p) { return typeof p.text === 'string' && !p.thought; })
    .map(function (p) { return p.text; })
    .join('').trim();
  if (cand.finishReason === 'MAX_TOKENS') throw fail_('bad', '결과가 너무 길어서 더 잘게 나눠 다시 해요');
  if (!text) throw fail_('bad', '받아쓴 내용이 비어 있어요(' + (cand.finishReason || '?') + ')');
  return text;
}

function prompt_(range) {
  const lines = ['이 녹음을 들리는 그대로 받아써 주세요.'];
  if (range) {
    lines.push('이번에는 ' + mmss_(range.start) + '부터 ' +
      (range.last ? '끝까지' : mmss_(range.end) + '까지') + ' 구간만 받아쓰세요.');
  }
  lines.push(
    '',
    '규칙:',
    '- 원래 언어 그대로 적고, 요약·번역·고쳐 쓰기를 하지 마세요.',
    '- 말하는 사람이 바뀌거나 새 문장이 시작되면 줄을 바꾸고, 줄 맨 앞에 녹음 맨 처음부터 잰 시각을 [분:초]로 적으세요.',
    '- 말하는 사람이 둘 이상이면 시각 뒤에 화자1:, 화자2:처럼 구분하세요.',
    '- 알아듣기 어려운 부분은 (불분명)이라고 적으세요.',
    '- 말소리가 없으면 들리는 소리만 괄호 안에 한 줄로 적으세요. 예: (말소리 없음 - TV 소리)',
    '- 받아쓴 내용 말고 다른 설명은 쓰지 마세요.',
    '',
    '예시:',
    '[00:03] 화자1: 오늘 몇 시에 와?',
    '[00:05] 화자2: 일곱 시쯤 갈게.'
  );
  return lines.join('\n');
}

/** 긴 파일은 Gemini Files API로 올린 뒤 주소로 참조한다 (48시간 뒤 자동 삭제, 처리 후 바로 삭제). */
function uploadAudio_(file, size) {
  let start;
  try {
    start = UrlFetchApp.fetch(GEMINI_UPLOAD, {
      method: 'post',
      contentType: 'application/json',
      headers: {
        'x-goog-api-key': GEMINI_API_KEY,
        'X-Goog-Upload-Protocol': 'resumable',
        'X-Goog-Upload-Command': 'start',
        'X-Goog-Upload-Header-Content-Length': String(size),
        'X-Goog-Upload-Header-Content-Type': AUDIO_MIME,
      },
      payload: JSON.stringify({ file: { display_name: file.getName() } }),
      muteHttpExceptions: true,
    });
  } catch (err) {
    throw fail_('retry', '업로드 연결 오류: ' + errText_(err));
  }
  const s = parseResponse_(start);
  if (s.code !== 200) throw failFromResponse_(s);
  const url = responseHeader_(start, 'x-goog-upload-url');
  if (!url) throw fail_('retry', 'Gemini 업로드 주소를 받지 못했어요');

  let up;
  try {
    up = UrlFetchApp.fetch(url, {
      method: 'post',
      contentType: AUDIO_MIME,
      headers: { 'X-Goog-Upload-Offset': '0', 'X-Goog-Upload-Command': 'upload, finalize' },
      payload: file.getBlob(),
      muteHttpExceptions: true,
    });
  } catch (err) {
    throw fail_('retry', '업로드 오류: ' + errText_(err));
  }
  const u = parseResponse_(up);
  if (u.code !== 200) throw failFromResponse_(u);
  let info = u.json && u.json.file;
  if (!info || !info.uri || !info.name) throw fail_('retry', 'Gemini 업로드 응답이 이상해요');

  for (let i = 0; i < 20 && info.state === 'PROCESSING'; i++) {
    Utilities.sleep(2000);
    const g = callGemini_(info.name, null, 'get');
    if (g.code === 200 && g.json && g.json.name) info = g.json;
  }
  if (info.state === 'FAILED') {
    deleteUploaded_(info.name);
    throw fail_('bad', 'Gemini가 이 오디오를 읽지 못했어요');
  }
  if (info.state === 'PROCESSING') {
    deleteUploaded_(info.name);
    throw fail_('retry', 'Gemini 오디오 처리가 늦어요');
  }
  return info;
}

function deleteUploaded_(name) {
  try {
    callGemini_(name, null, 'delete');
  } catch (err) {
    // 48시간 뒤 자동 삭제되니 무시
  }
}

function paced_(ctx, path, body) {
  const wait = ctx.lastCall + STT.minGapMs - Date.now();
  if (wait > 0) Utilities.sleep(wait);
  ctx.lastCall = Date.now();
  return callGemini_(path, body);
}

function callGemini_(path, body, method) {
  const options = {
    method: method || 'post',
    headers: { 'x-goog-api-key': GEMINI_API_KEY },
    muteHttpExceptions: true,
  };
  if (body) {
    options.contentType = 'application/json';
    options.payload = JSON.stringify(body);
  }
  let res;
  try {
    res = UrlFetchApp.fetch(GEMINI_BASE + path, options);
  } catch (err) {
    throw fail_('retry', 'Gemini 연결 오류: ' + errText_(err)); // 시간 초과(60초) 포함
  }
  return parseResponse_(res);
}

function parseResponse_(res) {
  const code = res.getResponseCode();
  const text = res.getContentText() || '';
  let json = null;
  try {
    json = JSON.parse(text);
  } catch (err) {
    // JSON이 아닌 응답
  }
  const error = (json && json.error) || {};
  return {
    code: code,
    json: json,
    message: String(error.message || (code === 200 ? '' : text.slice(0, 200))),
    status: String(error.status || ''),
    details: error.details || [],
  };
}

function failFromResponse_(r) {
  const details = JSON.stringify(r.details);
  const msg = 'Gemini ' + r.code + (r.status ? ' ' + r.status : '') + ': ' + shorten_(r.message);
  if (r.code === 429 || r.status === 'RESOURCE_EXHAUSTED') {
    const perDay = /PerDay/i.test(details) || /per day/i.test(r.message);
    return fail_('quota', perDay ? '무료 하루 한도 초과 — 한도가 풀리면 이어서 해요' : '무료 분당 한도 초과 — 잠시 뒤 이어서 해요',
      perDay ? 60 * 60 * 1000 : retryDelayMs_(r.details) || 10 * 60 * 1000);
  }
  if (r.code === 401 || r.code === 403 || r.code === 404 || /API_KEY/.test(details) ||
      /API key/i.test(r.message) || r.status === 'FAILED_PRECONDITION') {
    return fail_('config', msg);
  }
  if (r.code >= 500) return fail_('retry', msg);
  return fail_('bad', msg);
}

function retryDelayMs_(details) {
  for (let i = 0; i < details.length; i++) {
    const m = /^(\d+(?:\.\d+)?)s$/.exec(String((details[i] && details[i].retryDelay) || ''));
    if (m) return Math.ceil(Number(m[1]) * 1000) + 5000;
  }
  return 0;
}

function fail_(kind, message, pauseMs) {
  const e = new Error(message);
  e.kind = kind;
  e.pauseMs = pauseMs || 0;
  return e;
}

function asFailure_(err) {
  if (err && err.kind) return err;
  return fail_('bad', errText_(err));
}

// ───────────── 결과 저장 ─────────────

function writeTranscript_(file, text) {
  const parents = file.getParents();
  const folder = parents.hasNext() ? parents.next() : DriveApp.getRootFolder();
  const name = transcriptName_(file.getName());
  const body = titleLine_(file.getName(), file.getSize()) + '\n\n' + String(text).trim() + '\n';
  const same = folder.getFilesByName(name);
  if (same.hasNext()) {
    same.next().setContent(body);
  } else {
    folder.createFile(name, body, MimeType.PLAIN_TEXT);
  }
}

function transcriptName_(audioName) {
  return audioName.replace(/\.m4a$/i, '') + '.txt';
}

/** 예: "녹음 2026-10-24 14:35:12 · 1분 05초" */
function titleLine_(name, size) {
  const m = /^(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})/.exec(name);
  const when = m ? m[1] + ' ' + m[2] + ':' + m[3] + ':' + m[4] : name;
  const d = durationSec_(name, size);
  const len = d >= 60 ? Math.floor(d / 60) + '분 ' + String(d % 60).padStart(2, '0') + '초' : d + '초';
  return '녹음 ' + when + ' · ' + (DURATION_RE.test(name) ? '' : '약 ') + len;
}

function durationSec_(name, size) {
  const m = DURATION_RE.exec(name);
  if (m) return Number(m[1] || 0) * 60 + Number(m[2]);
  return Math.max(1, Math.round(size / STT.bytesPerSec));
}

// ───────────── 대기열 (스크립트 속성에 파일별로 저장) ─────────────

function queue_(props, fileId, createdMs) {
  saveItem_(props, fileId, { t: createdMs, n: 0, at: 0, err: '' });
}

function saveItem_(props, fileId, item) {
  props.setProperty(Q_PREFIX + fileId, JSON.stringify({ t: item.t, n: item.n, at: item.at, err: item.err || '' }));
}

function queueItems_(props) {
  const all = props.getProperties();
  const items = [];
  Object.keys(all).forEach(function (k) {
    if (k.indexOf(Q_PREFIX) !== 0) return;
    let v = {};
    try {
      v = JSON.parse(all[k]);
    } catch (err) {
      // 깨진 값은 처음부터
    }
    items.push({ id: k.slice(Q_PREFIX.length), t: Number(v.t) || 0, n: Number(v.n) || 0, at: Number(v.at) || 0, err: v.err || '' });
  });
  return items.sort(function (a, b) { return a.t - b.t; });
}

/** 최근 며칠 폴더에서 받아쓰기가 없는 녹음을 대기열에 넣는다. */
function backfill_(props, days) {
  const root = folder_(DriveApp.getRootFolder(), ROOT_FOLDER);
  const since = Utilities.formatDate(new Date(Date.now() - (days + 1) * 86400000), 'Asia/Seoul', 'yyyy-MM-dd');
  const queued = {};
  queueItems_(props).forEach(function (i) { queued[i.id] = true; });
  let added = 0;
  const folders = root.getFolders();
  while (folders.hasNext()) {
    const folder = folders.next();
    const fname = folder.getName();
    if (!((/^\d{4}-\d{2}-\d{2}$/.test(fname) && fname >= since) || fname === '기타')) continue;
    const audio = [];
    const texts = {};
    const files = folder.getFiles();
    while (files.hasNext()) {
      const f = files.next();
      const n = f.getName();
      if (/\.txt$/i.test(n)) texts[n] = true;
      else if (/\.m4a$/i.test(n)) audio.push(f);
    }
    audio.forEach(function (f) {
      if (texts[transcriptName_(f.getName())] || queued[f.getId()] || f.isTrashed()) return;
      queue_(props, f.getId(), f.getDateCreated().getTime());
      added++;
    });
  }
  return added;
}

/** 동시에 두 번 처리하지 않도록 표시 (10분 지나면 끊긴 실행으로 보고 무시) */
function lockRun_(props) {
  const busy = Number(props.getProperty(P_BUSY) || 0);
  if (busy && Date.now() - busy < 10 * 60 * 1000) return false;
  props.setProperty(P_BUSY, String(Date.now()));
  return true;
}

// ───────────── 공통 ─────────────

/** 하위 폴더 찾기/만들기 (동시에 두 번 만들어지지 않도록 잠금) */
function folder_(parent, name) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const it = parent.getFoldersByName(name);
    return it.hasNext() ? it.next() : parent.createFolder(name);
  } finally {
    lock.releaseLock();
  }
}

function clean_(value, fallback) {
  const s = String(value || '').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 120);
  return s || fallback;
}

function responseHeader_(res, name) {
  const all = (res.getAllHeaders && res.getAllHeaders()) || res.getHeaders() || {};
  const keys = Object.keys(all);
  for (let i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === name) {
      const v = all[keys[i]];
      return Array.isArray(v) ? v[0] : v;
    }
  }
  return '';
}

function mmss_(sec) {
  return String(Math.floor(sec / 60)).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0');
}

function stamp_() {
  return Utilities.formatDate(new Date(), 'Asia/Seoul', 'MM-dd HH:mm');
}

function errText_(err) {
  return String((err && err.message) || err);
}

function shorten_(s) {
  s = String(s || '');
  return s.length > 160 ? s.slice(0, 160) + '…' : s;
}

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
