<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Голосовое управление устройствами RE:Vision и Wi-Fi на очках Rokid. Для обычного выключения компьютера фразами «выключи Студио локал», «выключи компьютер Студио локал» или «заверши работу Студио локал» всегда выбирай action=shutdown и передавай deviceName «Студио локал». Слово «устройство» не требуется. Не выбирай windows_shutdown для обычного голосового выключения: оно должно идти через штатный SSH-механизм RE:Vision. Для команд Windows разбирай фразу как «действие + имя компьютера в конце»: например, «пауза AIMP, студио локал» означает выполнить команду «пауза AIMP» на компьютере, найденном по имени в сканировании/списке RE:Vision. Передавай произнесённое имя устройства отдельно в deviceName, а в commandPhrase оставляй только действие; не ограничивайся заранее заданным названием «Studio Local». Не выбирай компьютер по IP-адресу или имени Wi-Fi. Для Wi-Fi строго различай команды: узнать текущую подключённую сеть или проверить Wi-Fi — wifi_status; перечислить видимые сети — wifi_list; открыть системные настройки Wi-Fi — wifi; подключиться или переключиться на названную сеть — wifi_connect. Никогда не открывай настройки Wi-Fi в ответ на вопрос о текущей сети или её статусе. Для устройств показывай сохранённые устройства, проверяй доступность, включай и выключай настроенные устройства. Команды Windows: windows_status — проверить Windows Agent; windows_ssh_launch — выполнить команду из каталога Win SSH Agent через SSH-ключ; windows_lock — заблокировать выбранный компьютер; windows_launch — выполнить сохранённую команду Windows Agent на выбранном компьютере. Разбирай фразу как «действие + имя устройства в конце»: «пауза AIMP, студио локал» означает commandPhrase «пауза AIMP» и deviceName с точным именем «Студио локал» из списка/сканирования RE:Vision. Не подставляй STUDIO PC по умолчанию и не используй IP или имя Wi-Fi как имя устройства. «Открой/запусти AIMP (аимп/аип)» всегда означает запуск приложения на компьютере, а не воспроизведение музыки на телефоне; windows_shutdown — устаревший совместимый action, не использовать для обычного голосового выключения компьютера (используй shutdown через SSH RE:Vision); windows_restart — перезагрузить Windows; windows_cancel_shutdown — отменить запланированное выключение. Для команд Windows Agent требуется предварительное сопряжение Windows Agent в настройках RE:Vision. Для windows_ssh_launch требуется сохранённый SSH-ключ RE:Vision, включённый OpenSSH Server на STUDIO PC и установленный RE:Vision SSH Agent (отдельное приложение; не Windows Agent и не фоновая служба).",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "action": {
          "type": "string",
          "enum": ["list", "status", "wake", "shutdown", "wifi_enable", "wifi_disable", "wifi", "wifi_connect", "wifi_status", "wifi_list", "bridge_settings", "windows_status", "windows_lock", "windows_launch", "windows_ssh_launch", "windows_shutdown", "windows_restart", "windows_cancel_shutdown"],
          "description": "Операция: list — показать устройства; status — проверить доступность устройства; shutdown — выключить компьютер через сохранённый SSH-профиль RE:Vision; для любой фразы «выключи + имя компьютера» выбирай shutdown и передавай точное имя в deviceName, даже если пользователь не произнёс слово «устройство»; не выбирай windows_shutdown для обычного выключения; wake — включить устройство; windows_launch — выполнить команду на выбранном Windows Agent. Для «пауза AIMP, студио локал» выбери action=windows_launch, передай commandPhrase «пауза AIMP», а deviceName — имя «Студио локал», найденное в списке устройств RE:Vision. Имя компьютера обычно находится в конце фразы. Не подставляй другой компьютер, если имя не удалось сопоставить; wifi_status — узнать, включён ли Wi-Fi и к какой сети подключены (в том числе вопросы «проверь Wi-Fi», «какая сеть подключена», «назови сеть»); wifi_list — перечислить видимые сети («покажи доступные сети»); wifi — только открыть системные настройки Wi-Fi по прямой просьбе «открой настройки Wi-Fi»; wifi_connect — подключиться или переключиться на сеть по имени (Студио = Studio, Локал нетворк = Local_Network_5G). wifi_enable — включить Wi-Fi; wifi_disable — выключить Wi-Fi; bridge_settings — открыть меню восстановления Voice Bridge и системные настройки специальных возможностей RE:Vision. Фразы «открой настройки Ревижн», «открой меню Voice Bridge», «зайди в Voice Bridge», «перейди напрямую в Voice Bridge», «открой страницу Voice Bridge», «войс бридж», «проверь службу Ревижн», «дай доступ к Voice Bridge», «подключись к Voice Bridge», «восстанови Voice Bridge», «почему не работает Voice Bridge», «открой настройки голосового моста», «включи службу Ревижн» должны выбирать bridge_settings. При любом явном запросе пользователя перейти непосредственно к Voice Bridge выбирай bridge_settings, а не обычные настройки приложения и не действие Wi-Fi. Для включения/выключения обязательно выбирай отдельное действие wifi_enable или wifi_disable по смыслу фразы. Не используй wifi_toggle, если AIUI не передал enabled. Команды включения/выключения Wi-Fi никогда не направляй в wifi и не открывай настройки. Если пользователь явно говорит «выключи Wi-Fi», «отключи Wi-Fi», «выруби вай-фай» или «отключи вайфай», всегда выбирай wifi_disable и не задавай уточняющий вопрос. Если пользователь говорит «включи Wi-Fi» или «подключи Wi-Fi», всегда выбирай wifi_enable. Не выбирай wifi_toggle: это внутренний режим совместимости, он не является голосовым действием. Не используй wifi для проверки статуса. Для wifi_connect Android открывает системный выбор, где пользователь подтверждает сеть. Не утверждай, что подключение завершено, пока wifi_status не подтвердит SSID."
        },
        "networkName": {
          "type": "string",
          "description": "Целевая сеть Wi-Fi: Студио / Studio или Локал нетворк / Local_Network_5G."
        },
        "enabled": {
          "type": "boolean",
          "description": "Требуется только для wifi_toggle: true — включить Wi-Fi, false — выключить Wi-Fi."
        },
        "deviceName": {
          "type": "string",
          "description": "Точное имя компьютера из списка/сканирования устройств RE:Vision, которое пользователь произнёс в конце команды. Если устройство называется «Студио локал», передай deviceName «Студио локал». Не заменяй имя на STUDIO PC, не используй IP-адрес или SSID и не выбирай устройство по умолчанию, если пользователь назвал другое."
        },
        "commandPhrase": {
          "type": "string",
          "description": "Только действие без имени компьютера в конце. Например, из «пауза AIMP, студио локал» передай «пауза AIMP». Команды «пауза AIMP», «следующий трек AIMP», «предыдущий трек AIMP», «громкость AIMP» — управление AIMP на выбранном компьютере, а не медиаплеером телефона. Не перефразируй название действия; Windows Agent сопоставляет его с каталогом команд выбранного компьютера."
        }
      },
      "required": ["action"]
    }
  }
}
</script>

<script setup>
// Wi-Fi radio on/off uses the Android bridge POST /wifi/toggle; opening Settings is a separate explicit intent.
const BRIDGE = 'http://127.0.0.1:38761';

function normalize(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/ё/g, 'е')
    .replace(/[^a-zа-я0-9]/g, '');
}

function decodeQueryValue(value) {
  let decoded = String(value == null ? '' : value);
  // AIUI runtimes differ: some decode navigateTo query values once, others
  // leave percent-encoded UTF-8 intact. Decode at most twice for double-encoded
  // values, and never let malformed escapes abort command handling.
  for (let pass = 0; pass < 2 && /%[0-9a-f]{2}/i.test(decoded); pass += 1) {
    try {
      const next = decodeURIComponent(decoded);
      if (next === decoded) break;
      decoded = next;
    } catch (e) {
      break;
    }
  }
  return decoded;
}

function parseCommandAlternatives(value) {
  try {
    const parsed = JSON.parse(decodeQueryValue(value));
    if (!Array.isArray(parsed)) return [];
    return parsed.map(item => String(item || '').trim())
      .filter(item => item.length > 0 && item.length <= 160).slice(0, 5);
  } catch (e) { return []; }
}

function request(path, method, token, data, timeoutMs) {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || typeof wx.request !== 'function') {
      const error = new Error('wx.request недоступен в AIUI runtime');
      error.stage = path;
      reject(error);
      return;
    }
    const header = { 'content-type': 'application/json' };
    if (token) header['X-Revision-Token'] = token;
    wx.request({
      url: BRIDGE + path,
      method: method || 'GET',
      header,
      data,
      dataType: 'json',
      timeout: timeoutMs || 12000,
      success(res) {
        const status = Number(res && res.statusCode || 0);
        let body = res && res.data;
        if (typeof body === 'string') {
          try { body = JSON.parse(body); } catch (e) {}
        }
        if (status >= 200 && status < 300) {
          resolve(body);
        } else {
          const serverDetail = body && (body.detail || body.message || body.error);
          const error = new Error(serverDetail
            ? 'HTTP ' + status + ': ' + String(serverDetail)
            : 'HTTP ' + status);
          error.statusCode = status;
          error.stage = path;
          error.detail = serverDetail ? String(serverDetail) : '';
          error.response = body;
          reject(error);
        }
      },
      fail(err) {
        const detail = err && err.errMsg ? String(err.errMsg) : 'Неизвестная сетевая ошибка';
        const error = new Error(detail);
        error.stage = path;
        error.networkFailure = true;
        reject(error);
      }
    });
  });
}

async function requestWithRetry(path, method, token, data, attempts, timeoutMs, onRetry) {
  let lastError;
  const total = attempts || 3;
  const retryDelays = [800, 1500, 3000, 5000, 8000, 8000];
  for (let attempt = 0; attempt < total; attempt += 1) {
    try {
      return await request(path, method, token, data, timeoutMs);
    } catch (error) {
      lastError = error;
      const status = error && Number(error.statusCode || 0);
      // Authentication and missing-route errors are configuration problems,
      // not transient connection failures. Retry network errors and temporary
      // server/rate-limit responses only.
      const retryable = !status || status === 408 || status === 429 || status >= 500;
      if (!retryable || attempt + 1 >= total) throw error;
      const waitMs = retryDelays[Math.min(attempt, retryDelays.length - 1)];
      if (typeof onRetry === 'function') onRetry(attempt + 1, total, error);
      await delay(waitMs);
    }
  }
  throw lastError || new Error('Сетевая ошибка');
}

function speak(text) {
  try {
    if (typeof wx !== 'undefined' && wx.speech && typeof wx.speech.playTTS === 'function') {
      wx.speech.playTTS(text);
      return;
    }
  } catch (e) {
    console.warn('RE:Vision TTS failed', e);
  }
  try {
    if (typeof speechSynthesis !== 'undefined' && typeof SpeechSynthesisUtterance !== 'undefined') {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'ru-RU';
      speechSynthesis.speak(utterance);
    }
  } catch (e) {
    console.warn('RE:Vision speech fallback failed', e);
  }
}


function returnToMainVoicePage(page) {
  if (!page || page.keepActionPageOpen === true || page.returningHome === true) return;
  if (page.returnHomeTimer) clearTimeout(page.returnHomeTimer);
  page.returnHomeTimer = null;
  page.returningHome = true;

  const returnOneStep = (firstError) => {
    try {
      wx.navigateBack({
        delta: 1,
        success() {
          console.log('[RE:Vision] fallback return reached the agent voice page');
        },
        fail(secondError) {
          page.returningHome = false;
          console.warn('[RE:Vision] both two-step and one-step returns failed', {
            firstError, secondError
          });
        }
      });
    } catch (secondError) {
      page.returningHome = false;
      console.warn('[RE:Vision] fallback return failed', { firstError, secondError });
    }
  };

  try {
    wx.navigateBack({
      delta: 2,
      success() {
        console.log('[RE:Vision] returned two steps to main voice input page');
      },
      fail(error) {
        console.warn('[RE:Vision] two-step return failed; trying one-step fallback', error);
        returnOneStep(error);
      }
    });
  } catch (error) {
    console.warn('[RE:Vision] two-step return threw; trying one-step fallback', error);
    returnOneStep(error);
  }
}

function scheduleFallbackReturn(page, spokenText) {
  if (!page || page.keepActionPageOpen === true || page.returnHomeCancelled === true) return;
  // Older AIUI runtimes expose playTTS() without an end event. Use a deliberately
  // generous fallback delay there; current runtimes use the actual audio onEnded event.
  const delayMs = Math.max(4000, Math.min(20000, 2200 + spokenText.length * 130));
  if (page.returnHomeTimer) clearTimeout(page.returnHomeTimer);
  page.returnHomeTimer = setTimeout(() => {
    page.returnHomeTimer = null;
    returnToMainVoicePage(page);
  }, delayMs);
}

function speakAndReturn(page, text) {
  const spokenText = String(text || '');
  if (!page || page.keepActionPageOpen === true) {
    speak(spokenText);
    return;
  }

  if (page.returnHomeTimer) clearTimeout(page.returnHomeTimer);
  page.returnHomeTimer = null;
  page.returnHomeCancelled = false;
  page.returningHome = false;

  // Prefer a task-backed player when the Rokid runtime exposes it. Unlike
  // wx.speech.playTTS(), this gives us a real playback-ended event, so navigation
  // cannot cut off a long spoken answer just because a guessed timer expired.
  try {
    if (typeof speechSynthesis !== 'undefined'
        && typeof speechSynthesis.synthesize === 'function'
        && typeof SpeechSynthesisUtterance !== 'undefined'
        && typeof SpeechAudioPlayer === 'function') {
      const utterance = new SpeechSynthesisUtterance(spokenText);
      utterance.lang = 'ru-RU';
      const taskPromise = speechSynthesis.synthesize(utterance);
      Promise.resolve(taskPromise).then(task => {
        if (page.returnHomeCancelled === true || page.keepActionPageOpen === true) {
          try { task.abort(); } catch (ignored) { }
          return;
        }

        let player;
        try {
          player = new SpeechAudioPlayer(task, { trackMode: 'hidden' });
          if (!player.audioPlayer || typeof player.audioPlayer.onEnded !== 'function'
              || typeof player.audioPlayer.onError !== 'function') {
            throw new Error('SpeechAudioPlayer completion events are unavailable');
          }
        } catch (error) {
          try { task.abort(); } catch (ignored) { }
          console.warn('[RE:Vision] precise TTS playback unavailable; using compatibility playback', error);
          speak(spokenText);
          scheduleFallbackReturn(page, spokenText);
          return;
        }

        page.returnHomeTask = task;
        page.returnHomePlayer = player;
        let settled = false;
        const releasePlayer = () => {
          if (page.returnHomePlayer === player) page.returnHomePlayer = null;
          if (page.returnHomeTask === task) page.returnHomeTask = null;
          try { player.destroy(); } catch (ignored) { }
        };
        player.audioPlayer.onEnded(() => {
          if (settled) return;
          settled = true;
          if (page.returnHomeTimer) clearTimeout(page.returnHomeTimer);
          page.returnHomeTimer = null;
          releasePlayer();
          returnToMainVoicePage(page);
        });
        player.audioPlayer.onError(error => {
          if (settled) return;
          settled = true;
          releasePlayer();
          if (page.returnHomeCancelled === true) return;
          console.warn('[RE:Vision] TTS playback failed; using compatibility playback', error);
          speak(spokenText);
          scheduleFallbackReturn(page, spokenText);
        });
        // Arm a watchdog immediately. Some Rokid builds never settle task.finished
        // even though playback starts, which previously left the action page open.
        const fallbackWatchdogMs = Math.max(7000, Math.min(30000, 3500 + spokenText.length * 150));
        page.returnHomeTimer = setTimeout(() => {
          page.returnHomeTimer = null;
          if (settled || page.returnHomeCancelled === true) return;
          settled = true;
          releasePlayer();
          console.warn('[RE:Vision] TTS playback watchdog expired; returning to voice page');
          returnToMainVoicePage(page);
        }, fallbackWatchdogMs);

        if (task.finished && typeof task.finished.then === 'function') {
          task.finished.then(result => {
            if (settled || page.returnHomeCancelled === true) return;
            const audioDurationMs = Number(result && result.duration) * 1000;
            const watchdogMs = Math.max(5000, Math.min(30000,
              (Number.isFinite(audioDurationMs) && audioDurationMs > 0
                ? audioDurationMs : 2200 + spokenText.length * 130) + 5000));
            if (page.returnHomeTimer) clearTimeout(page.returnHomeTimer);
            page.returnHomeTimer = setTimeout(() => {
              page.returnHomeTimer = null;
              if (settled || page.returnHomeCancelled === true) return;
              settled = true;
              releasePlayer();
              console.warn('[RE:Vision] TTS end event timed out; using playback-duration watchdog');
              returnToMainVoicePage(page);
            }, watchdogMs);
          }).catch(error => {
            if (settled) return;
            settled = true;
            if (page.returnHomeTimer) clearTimeout(page.returnHomeTimer);
            page.returnHomeTimer = null;
            releasePlayer();
            if (page.returnHomeCancelled === true) return;
            console.warn('[RE:Vision] TTS generation failed; using compatibility playback', error);
            speak(spokenText);
            scheduleFallbackReturn(page, spokenText);
          });
        }
        try {
          player.play();
        } catch (error) {
          if (!settled) {
            settled = true;
            releasePlayer();
            console.warn('[RE:Vision] TTS player could not start; using compatibility playback', error);
            speak(spokenText);
            scheduleFallbackReturn(page, spokenText);
          }
        }
      }).catch(error => {
        if (page.returnHomeCancelled === true) return;
        console.warn('[RE:Vision] TTS task creation failed; using compatibility playback', error);
        speak(spokenText);
        scheduleFallbackReturn(page, spokenText);
      });
      return;
    }
  } catch (error) {
    console.warn('[RE:Vision] precise TTS setup failed; using compatibility playback', error);
  }

  // Compatibility path for older glasses firmware / AIUI runtimes.
  speak(spokenText);
  scheduleFallbackReturn(page, spokenText);
}

function resolveDevice(devices, requested) {
  const wanted = normalize(requested);
  if (!wanted) return { error: 'device_name_required' };
  const aliases = [];
  aliases.push(wanted);
  if (wanted.includes('студи') || wanted.includes('studio')) aliases.push('studio');
  if (wanted.includes('одиссей') || wanted.includes('odyssey') || wanted.includes('арк') || wanted.includes('ark')) {
    aliases.push('odysseyark', 'odyssey');
  }
  const matches = (devices || []).filter(item => {
    const name = normalize(item.name);
    return aliases.some(alias => name === alias || name.includes(alias) || alias.includes(name));
  });
  if (matches.length === 1) return { device: matches[0] };
  if (matches.length > 1) return { error: 'device_ambiguous', matches };
  return { error: 'device_not_found' };
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// The PIN is duplicated in AIUI's wx storage and Web Storage. If one
// backend loses its copy during a resource update, recover it from the other.
const TOKEN_KEY = 'revision_bridge_token';

function validBridgeToken(value) {
  const token = String(value == null ? '' : value).trim();
  return /^\d{4}$/.test(token) ? token : '';
}

function readBridgeToken() {
  let token = '';
  try {
    if (typeof localStorage !== 'undefined') {
      token = validBridgeToken(localStorage.getItem(TOKEN_KEY));
    }
  } catch (e) {
    console.warn('RE:Vision localStorage read failed', e);
  }
  if (!token) {
    try {
      if (typeof wx !== 'undefined' && typeof wx.getStorageSync === 'function') {
        token = validBridgeToken(wx.getStorageSync(TOKEN_KEY));
      }
    } catch (e) {
      console.warn('RE:Vision wx storage read failed', e);
    }
  }
  if (token) {
    try {
      if (typeof wx !== 'undefined' && typeof wx.setStorageSync === 'function') {
        wx.setStorageSync(TOKEN_KEY, token);
      }
    } catch (e) {
      console.warn('RE:Vision wx storage recovery failed', e);
    }
    try {
      if (typeof localStorage !== 'undefined') localStorage.setItem(TOKEN_KEY, token);
    } catch (e) {
      console.warn('RE:Vision localStorage recovery failed', e);
    }
  }
  return token;
}

export default {
  data: {
    title: 'Ревижн',
    statusText: 'Подключение к RE:Vision…',
    detailText: '',
    resultText: '',
    showSettingsButton: false,
    busy: true
  },

  onUnload() {
    this.returnHomeCancelled = true;
    if (this.returnHomeTimer) clearTimeout(this.returnHomeTimer);
    this.returnHomeTimer = null;
    if (this.returnHomeTask && typeof this.returnHomeTask.abort === 'function') {
      try { this.returnHomeTask.abort(); } catch (ignored) { }
    }
    this.returnHomeTask = null;
    if (this.returnHomePlayer && typeof this.returnHomePlayer.destroy === 'function') {
      try { this.returnHomePlayer.destroy(); } catch (ignored) { }
    }
    this.returnHomePlayer = null;
  },

  openSettings() {
    wx.navigateTo({ url: '/pages/settings/index' });
  },

  async onLoad(query) {
    const requestedAction = decodeQueryValue(query && query.action).toLowerCase().trim();
    if (requestedAction === 'bridge_settings') {
      wx.navigateTo({ url: '/pages/bridge-settings/index' });
      return;
    }
    // Use direction-specific intents so AIUI cannot drop the boolean enabled field.
    const toggleEnabled = requestedAction === 'wifi_enable' ? true
      : requestedAction === 'wifi_disable' ? false
      : (query && typeof query.enabled === 'boolean' ? query.enabled : undefined);
    const action = (requestedAction === 'wifi_enable' || requestedAction === 'wifi_disable')
      ? 'wifi_toggle' : requestedAction;
    const allowedActions = ['list', 'status', 'wake', 'shutdown', 'wifi_toggle', 'wifi', 'wifi_connect', 'wifi_status', 'wifi_list', 'windows_status', 'windows_lock', 'windows_launch', 'windows_ssh_launch', 'windows_shutdown', 'windows_restart', 'windows_cancel_shutdown'];
    if (!allowedActions.includes(action)) {
      const message = 'Команда не распознана как действие RE:Vision. Уточни команду: список устройств, статус устройства или команда Wi-Fi.';
      this.setData({ statusText: message, detailText: 'Не получено корректное значение action. Команды микрофона выполняются средствами Rokid и не относятся к RE:Vision.', busy: false });
      speakAndReturn(this, message);
      return;
    }
    const rawRequestedName = decodeQueryValue(query && (query.deviceName ?? query.device) || '');
    const requestedText = String(rawRequestedName).trim();
    const requestedName = /^(null|undefined|none)$/i.test(requestedText) ? '' : requestedText
      .replace(/^(?:компьютер|устройство)\s+/i, '')
      .replace(/\s+(?:устройство|компьютер)$/i, '').trim();

    // Windows commands must target the name spoken by the user. Never fall back
    // to a default PC when the voice intent omitted or failed to recognize it.
    if (action.startsWith('windows_') && !requestedName) {
      const message = 'Не расслышал имя компьютера. Повтори команду, назвав устройство в конце.';
      this.setData({ statusText: message, detailText: 'Например: «Пауза AIMP, студио локал».', busy: false });
      speakAndReturn(this, message);
      return;
    }

    this.setData({
      statusText: 'Проверяю связь с RE:Vision…',
      detailText: requestedName,
      busy: true
    });

    try {
      const health = await requestWithRetry(
        '/health', 'GET', '', null, 5, 5000,
        (retryNumber, retryTotal) => {
          this.setData({
            statusText: 'Связь с мостом пока отсутствует. Повторная проверка ' + retryNumber + ' из ' + (retryTotal - 1) + '…',
            detailText: 'Ожидаю восстановления локального Voice Bridge на очках.',
            busy: true
          });
        }
      );
      if (!health || health.ok !== true) throw new Error('Неожиданный ответ /health');
      if (['wifi_toggle', 'wifi', 'wifi_connect', 'wifi_status', 'wifi_list'].includes(action) && health.wifiRoute !== true) {
        const message = 'Установленная версия RE:Vision на очках устарела и не поддерживает открытие Wi-Fi. AIUI уже обновлён, но нужно обновить само Android-приложение RE:Vision.';
        this.setData({ statusText: message, detailText: 'Сохранённые устройства и настройки не трогай и не удаляй.', busy: false });
        speakAndReturn(this, message);
        return;
      }

      const token = readBridgeToken();
      if (!token) {
        const message = 'Связь с RE:Vision обнаружена, но ключ доступа ещё не настроен. Открой настройки агента и введи ключ из приложения RE:Vision.';
        this.setData({ statusText: message, detailText: 'Команды пока заблокированы до настройки защиты.', showSettingsButton: true, busy: false });
        speakAndReturn(this, 'Связь с RE:Vision есть. Нужно настроить ключ доступа.');
        return;
      }

      if (action.startsWith('windows_')) {
        const windowsAction = action.substring('windows_'.length);
        try {
          const result = windowsAction === 'status'
            ? await request('/windows/status', 'GET', token, null)
            : windowsAction === 'shutdown'
              ? await request('/command', 'POST', token, { action: 'shutdown', device: requestedName })
              : windowsAction === 'launch'
              ? await request('/windows/execute', 'POST', token, {
                  phrase: decodeQueryValue(query && query.commandPhrase || '').trim(),
                  deviceName: requestedName,
                  alternatives: parseCommandAlternatives(query && query.commandAlternatives)
                })
              : windowsAction === 'ssh_launch'
                ? await request('/windows/ssh/execute', 'POST', token, { phrase: decodeQueryValue(query && query.commandPhrase || '').trim(), deviceName: requestedName || 'STUDIO' })
                : await request('/windows/command/' + windowsAction.replace(/_/g, '-'), 'POST', token, { deviceName: requestedName });
          if (!result || result.ok !== true) throw new Error(result && result.error ? result.error : 'Windows Agent вернул неожиданный ответ');
          let message;
          if (windowsAction === 'status') {
            const reachable = result.configured && result.agent && result.agent.paired === true;
            message = reachable ? 'Windows Agent компьютера ' + (requestedName || 'по умолчанию') + ' доступен и сопряжён.' : 'Windows Agent не настроен или недоступен. Проверь сопряжение и сеть.';
          } else {
            message = result.accepted === true ? (windowsAction === 'launch' ? 'Команда запуска передана Windows Agent.' : windowsAction === 'ssh_launch' ? 'Команда передана Win SSH Agent по защищённому SSH-соединению.' : windowsAction === 'shutdown' ? 'Команда выключения передана через SSH RE:Vision.' : 'Команда Windows передана компьютеру.') : 'Windows Agent не подтвердил выполнение команды.';
          }
          this.setData({ statusText: message, detailText: (windowsAction === 'ssh_launch' || windowsAction === 'shutdown') ? (requestedName || 'Компьютер') + ' · SSH RE:Vision' : (requestedName || 'Компьютер по умолчанию') + ' · Windows Agent', resultText: message, busy: false });
          speakAndReturn(this, message);
        } catch (error) {
          const message = windowsAction === 'ssh_launch' ? 'Не удалось выполнить команду через Win SSH Agent.' : windowsAction === 'shutdown' ? 'Не удалось выключить компьютер через SSH RE:Vision.' : 'Не удалось связаться с Windows Agent компьютера ' + (requestedName || 'по умолчанию') + '.';
          this.setData({ statusText: message, detailText: String(error && error.message || error), busy: false });
          speakAndReturn(this, message);
        }
        return;
      }

      if (action === 'wifi_toggle') {
        if (typeof toggleEnabled !== 'boolean') {
          const message = 'Уточни действие: включить или выключить Wi-Fi?';
          this.setData({ statusText: message, detailText: 'Не получил параметр enabled=true или enabled=false. Настройки не открывал.', busy: false });
          speakAndReturn(this, message);
          return;
        }
        const requestedEnabled = toggleEnabled;
        let result;
        try {
          result = await request('/wifi/toggle', 'POST', token, { enabled: requestedEnabled });
        } catch (toggleError) {
          // Older installed APKs may not yet expose /wifi/toggle. Fall back to
          // the existing Wi-Fi settings route instead of silently failing.
          if (toggleError && toggleError.statusCode === 404) {
            const fallback = await request('/wifi', 'POST', token, {});
            if (fallback && fallback.ok === true && fallback.opened === true) {
              const fallbackMessage = requestedEnabled
                ? 'В этой версии приложения прямое включение Wi-Fi недоступно. Открыл настройки Wi-Fi: включи переключатель вручную.'
                : 'В этой версии приложения прямое выключение Wi-Fi недоступно. Открыл настройки Wi-Fi: выключи переключатель вручную.';
              this.setData({ statusText: fallbackMessage, detailText: 'Обнови Android-приложение RE:Vision для обработки команды через Voice Bridge.', resultText: fallbackMessage, busy: false });
              speakAndReturn(this, fallbackMessage);
              return;
            }
          }
          throw toggleError;
        }
        if (!result || result.ok !== true) {
          throw new Error(result && result.error ? result.error : 'Android не выполнил переключение Wi-Fi');
        }
        // Modern Android may reject WifiManager.setWifiEnabled(). In that case
        // the bridge opens the system panel and uses its enabled Accessibility
        // service to operate the exact Wi-Fi switch. Verify the radio state here
        // instead of claiming success from merely opening the panel.
        if (result.selectionPending === true) {
          for (let attempt = 0; attempt < 12; attempt += 1) {
            await delay(700);
            const state = await request('/wifi/status', 'GET', token, null, 5000);
            if (state && state.ok === true && state.enabled === requestedEnabled) {
              result = Object.assign({}, state, {
                verified: true,
                requestedEnabled,
                changed: true
              });
              break;
            }
          }
        }
        if (result.verified === true && result.enabled === requestedEnabled) {
          const message = requestedEnabled ? 'Wi-Fi включён. Состояние проверено.' : 'Wi-Fi выключен. Состояние проверено.';
          this.setData({ statusText: message, detailText: result.ssid ? 'Сеть: ' + result.ssid : '', resultText: message, busy: false });
          speakAndReturn(this, message);
          return;
        }
        if (result.requiresUserAction === true || result.selectionPending === true) {
          const message = requestedEnabled
            ? 'Не удалось автоматически подтвердить включение Wi-Fi. Системная панель открыта — включи переключатель вручную.'
            : 'Не удалось автоматически подтвердить выключение Wi-Fi. Системная панель открыта — выключи переключатель вручную.';
          this.setData({ statusText: message, detailText: 'Переключение не подтверждено. Требуется действие в системных настройках.', resultText: message, busy: false });
          speakAndReturn(this, message);
          return;
        }
        const message = 'Запрос на изменение Wi-Fi отправлен, но новое состояние не подтверждено.';
        this.setData({ statusText: message, detailText: JSON.stringify(result), resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      if (action === 'wifi' || action === 'wifi_connect') {
        const targetNetwork = String(query.networkName || query.ssid || '').trim();
        let result;
        let openedPickerForChoice = false;

        if (action === 'wifi_connect' && !targetNetwork) {
          // AIUI sometimes recognizes "connect to another Wi-Fi" but omits the SSID.
          // In that case, show Android's network picker instead of reporting an error.
          result = await request('/wifi', 'POST', token, {});
          openedPickerForChoice = true;
        } else if (action === 'wifi_connect') {
          result = await request('/wifi/connect', 'POST', token, { networkName: targetNetwork });
          if (result && result.ok !== true && result.error === 'wifi_network_required') {
            // Unknown or lost SSID: fall back to the visible Android Wi-Fi list.
            result = await request('/wifi', 'POST', token, {});
            openedPickerForChoice = true;
          }
        } else {
          result = await request('/wifi', 'POST', token, {});
        }

        if (!result || result.ok !== true || (openedPickerForChoice || action === 'wifi') && result.opened !== true) {
          throw new Error(result && result.error ? result.error : 'Не удалось открыть системный список Wi-Fi');
        }

        if (action === 'wifi_connect' && !openedPickerForChoice) {
          if (result.connected === true) {
            const message = result.alreadyConnected
              ? 'Ты уже подключён к сети ' + (result.targetSsid || targetNetwork) + '.'
              : 'Подключение к сети ' + (result.targetSsid || targetNetwork) + ' подтверждено.';
            this.setData({ statusText: message, detailText: 'Текущая сеть проверена через Android.', resultText: message, busy: false });
            speakAndReturn(this, message);
            return;
          }
          const message = result.selectionClicked === true
            ? 'Сеть ' + (result.targetSsid || targetNetwork) + ' выбрана. Если Android запросит подтверждение, подтверди подключение. Затем скажи: Ревижн, проверь Wi-Fi.'
            : 'Открыл список Wi-Fi. Найди сеть ' + (result.targetSsid || targetNetwork) + ' и выбери её. Затем скажи: Ревижн, проверь Wi-Fi.';
          this.setData({ statusText: message, detailText: 'Подключение ещё не подтверждено Android.', resultText: message, busy: false });
          speakAndReturn(this, message);
          return;
        }

        const message = openedPickerForChoice
          ? 'Открыл список доступных сетей Wi-Fi. Выбери нужную точку на экране очков и подтверди подключение. Затем скажи: Ревижн, проверь Wi-Fi.'
          : 'Открыл список сетей Wi-Fi на очках. Выбери нужную точку доступа.';
        this.setData({ statusText: message, detailText: result.mode === 'panel' ? 'Системная панель Wi-Fi.' : 'Открыта системная страница выбора сети.', resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      if (action === 'wifi_status') {
        const state = await request('/wifi/status', 'GET', token);
        if (!state || state.ok !== true) {
          throw new Error(state && state.error ? state.error : 'Android не вернул состояние Wi-Fi');
        }
        const ssid = typeof state.ssid === 'string' ? state.ssid.trim() : '';
        let message;
        if (state.connected === true && ssid) {
          message = 'Сейчас подключено к сети ' + ssid + '.';
        } else if (state.connected === true) {
          message = 'Wi-Fi подключён, но Android не передал имя сети.';
        } else if (state.enabled === true) {
          message = 'Wi-Fi включён, но подключение к сети не подтверждено.';
        } else if (state.enabled === false) {
          message = 'Wi-Fi выключен.';
        } else {
          message = 'Android не передал достоверное состояние Wi-Fi. Повтори запрос.';
        }
        const detail = state.permissionRequired
          ? 'Android ограничил доступ к имени сети.'
          : (ssid ? 'Текущая сеть: ' + ssid : 'SSID не получен от Android.');
        this.setData({ statusText: message, detailText: detail, resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      if (action === 'wifi_list') {
        const scan = await requestWithRetry('/wifi/networks', 'GET', token, null, 2);
        if (!scan || scan.ok !== true) {
          const why = scan && scan.error === 'wifi_scan_permission_required'
            ? 'Android не выдал RE:Vision разрешение на сканирование Wi-Fi. Разреши доступ к местоположению в настройках приложения.'
            : (scan && scan.error ? scan.error : 'Не удалось получить список сетей.');
          this.setData({ statusText: why, busy: false });
          speakAndReturn(this, why);
          return;
        }
        const names = (scan.networks || []).map(item => item.ssid);
        const message = names.length ? 'Вижу сети: ' + names.join(', ') : 'Список сетей пока пуст. Повтори запрос через несколько секунд.';
        this.setData({ statusText: message, detailText: 'Сканирование запрошено: ' + (scan.scanStarted ? 'да' : 'нет'), resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      const list = await request('/devices', 'GET', token);
      if (!list || list.ok !== true || !Array.isArray(list.devices)) {
        throw new Error(list && list.error ? list.error : 'Не удалось получить список устройств');
      }

      if (action === 'list') {
        const names = list.devices.map(item => item.name).filter(Boolean);
        const message = names.length ? 'Сохранённые устройства: ' + names.join(', ') : 'В RE:Vision пока нет сохранённых устройств.';
        this.setData({ statusText: message, detailText: '', resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      const resolved = resolveDevice(list.devices, requestedName);
      if (resolved.error === 'device_name_required') {
        const message = 'Не расслышал название устройства. Назови его ещё раз.';
        this.setData({ statusText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }
      if (resolved.error === 'device_ambiguous') {
        const names = resolved.matches.map(item => item.name).join(', ');
        const message = 'Нашёл несколько устройств: ' + names + '. Уточни, какое именно.';
        this.setData({ statusText: message, detailText: '', busy: false });
        speakAndReturn(this, message);
        return;
      }
      if (!resolved.device) {
        const message = 'Устройство «' + requestedName + '» не найдено в RE:Vision.';
        this.setData({ statusText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      const device = resolved.device;
      this.setData({ statusText: 'Устройство: ' + device.name, detailText: 'Выполняю: ' + action, busy: true });

      if (action === 'status') {
        const state = await request('/status?deviceId=' + encodeURIComponent(device.id), 'GET', token);
        if (!state || state.ok !== true) throw new Error(state && state.error ? state.error : 'Не удалось проверить состояние устройства');
        const message = state.online
          ? device.name + ' доступен в сети.'
          : device.name + ' сейчас не отвечает по сети.';
        this.setData({ statusText: message, detailText: state.checkedHost || '', resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      if (action !== 'wake' && action !== 'shutdown') {
        throw new Error('Операция не поддерживается: ' + action);
      }
      if (action === 'wake' && device.canWake !== true) {
        const message = 'Для ' + device.name + ' в RE:Vision не настроено включение Wake-on-LAN.';
        this.setData({ statusText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }
      if (action === 'shutdown' && device.canShutdown !== true) {
        const message = 'Для ' + device.name + ' в RE:Vision не настроено удалённое выключение SSH.';
        this.setData({ statusText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      const command = await request('/command', 'POST', token, {
        action,
        deviceId: device.id
      });
      if (!command || command.ok !== true || command.accepted !== true) {
        throw new Error(command && command.error ? command.error : 'Команда не принята RE:Vision');
      }

      if (action === 'wake') {
        this.setData({ statusText: 'Команда включения отправлена. Проверяю доступность…', busy: true });
        for (let attempt = 0; attempt < 6; attempt += 1) {
          await delay(2000);
          const state = await request('/status?deviceId=' + encodeURIComponent(device.id), 'GET', token);
          if (state && state.ok === true && state.online === true) {
            const message = device.name + ' отвечает в сети. Компьютер доступен.';
            this.setData({ statusText: message, detailText: 'Доступность подтверждена проверкой сети.', resultText: message, busy: false });
            speakAndReturn(this, message);
            return;
          }
        }
        const message = 'Команда включения отправлена, но ' + device.name + ' пока не ответил. Включение ещё не подтверждено.';
        this.setData({ statusText: message, detailText: 'Проверял доступность до 12 секунд.', resultText: message, busy: false });
        speakAndReturn(this, message);
        return;
      }

      await delay(3000);
      const state = await request('/status?deviceId=' + encodeURIComponent(device.id), 'GET', token);
      if (!state || state.ok !== true) throw new Error(state && state.error ? state.error : 'Не удалось проверить состояние после команды');
      const message = state.online
        ? 'Команда выключения отправлена, но ' + device.name + ' всё ещё отвечает в сети. Выключение не подтверждено.'
        : 'После команды ' + device.name + ' перестал отвечать по сети. Это не является абсолютным подтверждением выключения.';
      this.setData({ statusText: message, detailText: 'Состояние питания не подтверждено напрямую.', resultText: message, busy: false });
      speakAndReturn(this, message);
    } catch (error) {
      const raw = error && error.message ? String(error.message) : String(error);
      const lower = raw.toLowerCase();
      let message = 'Ошибка RE:Vision: ' + raw;
      let detail = 'Этап: ' + (error && error.stage ? error.stage : 'не определён') + '.';
      if (error && error.statusCode === 401) {
        message = 'RE:Vision отклонил PIN доступа. Проверь PIN в настройках агента и введи тот же PIN, который показан в приложении RE:Vision.';
        detail += ' Ответ моста: HTTP 401 — сервер доступен, но PIN не совпадает.';
      } else if (error && error.statusCode === 404) {
        message = 'RE:Vision получил запрос, но такой адрес не найден. Обнови приложение RE:Vision.';
        detail += ' Ответ моста: HTTP 404.';
      } else if (error && error.statusCode === 502) {
        message = 'Voice Bridge не смог выполнить команду на целевом компьютере.';
        detail += ' Ответ моста: HTTP 502. Причина от Windows Agent / SSH: ' + (error.detail || raw);
      } else if (error && error.networkFailure) {
        message = 'Не удалось достучаться до RE:Vision на очках. Сам запрос не получил HTTP-ответ.';
        detail += ' Точная ошибка AIUI: ' + raw;
      }
      this.setData({ statusText: message, detailText: detail, busy: false });
      speakAndReturn(this, message);
      console.error('RE:Vision action failed', { message: raw, stage: error && error.stage, statusCode: error && error.statusCode });
    }
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">{{title}}</text>
    <text class="status">{{statusText}}</text>
    <text class="detail">{{detailText}}</text>
    <button ink:if="{{showSettingsButton}}" bindtap="openSettings">Настроить ключ доступа</button>
    <text class="footer" ink:if="{{busy}}">Выполняется проверка…</text>
  </view>
</page>

<style>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border: 2px solid #40ff5e;
  border-radius: 12px;
  color: #40ff5e;
}
.heading {
  font-size: 22px;
  font-weight: 700;
}
.status {
  font-size: 18px;
  line-height: 1.35;
}
.detail, .footer {
  font-size: 14px;
  opacity: 0.9;
}
</style>
