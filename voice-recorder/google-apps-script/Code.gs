/**
 * 소리 감지 녹음기 → 구글 드라이브 업로드 받는 스크립트
 *
 * 설치 (PC에서 5분)
 * 1. https://script.google.com → [새 프로젝트]
 * 2. 기존 코드를 모두 지우고 이 파일 내용을 붙여넣기
 * 3. 아래 SECRET 값을 나만 아는 비밀번호로 바꾸고 저장(Ctrl+S)
 * 4. [배포] → [새 배포] → 유형 선택(톱니바퀴) → [웹 앱]
 *      - 다음 사용자 인증 정보로 실행: 나
 *      - 액세스 권한이 있는 사용자: 모든 사용자
 *    → [배포] → 권한 승인 (경고가 나오면 '고급' → '(안전하지 않음)으로 이동' → 허용)
 * 5. 나오는 '웹 앱 URL'(…/exec)을 복사해서 폰 앱에 붙여넣고, 같은 비밀번호를 입력
 *
 * 코드를 고친 뒤에는 [배포] → [배포 관리] → 연필 → 버전: 새 버전 → [배포] 해야 반영됩니다.
 */

const SECRET = 'CHANGE_ME';      // ← 여기를 나만 아는 비밀번호로 바꾸세요
const ROOT_FOLDER = '폰녹음';     // 드라이브에 만들어질 폴더 이름

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
      return reply_({ ok: true, pong: true, folderUrl: root.getUrl() });
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
    return reply_({ ok: true, id: file.getId(), size: file.getSize() });
  } catch (err) {
    return reply_({ ok: false, code: 'server', error: String((err && err.message) || err) });
  }
}

/** 브라우저로 주소를 열었을 때 동작 확인용 */
function doGet() {
  return reply_({ ok: true, message: '녹음 업로드 스크립트가 동작 중이에요' });
}

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

function reply_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
