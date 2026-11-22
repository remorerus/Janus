<script def>
{
  "navigationBarTitleText": "RE:Vision",
  "description": "Голосовое управление устройствами RE:Vision и Wi-Fi на очках Rokid. Для Wi-Fi строго различай команды: узнать текущую подключённую сеть или проверить Wi-Fi — wifi_status; перечислить видимые сети — wifi_list; открыть системные настройки Wi-Fi — wifi; подключиться или переключиться на названную сеть — wifi_connect. Никогда не открывай настройки Wi-Fi в ответ на вопрос о текущей сети или её статусе. Для устройств показывай сохранённые устройства, проверяй доступность, включай и выключай настроенные устройства.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "action": {
          "type": "string",
          "enum": ["list", "status", "wake", "shutdown", "wifi", "wifi_connect", "wifi_status", "wifi_list"],
          "description": "Операция: list — показать устройства; status — проверить доступность устройства; wake — включить устройство; shutdown — выключить устройство; wifi_status — узнать, включён ли Wi-Fi и к какой сети подключены (в том числе вопросы «проверь Wi-Fi», «какая сеть подключена», «назови сеть»); wifi_list — перечислить видимые сети («покажи доступные сети»); wifi — только открыть системные настройки Wi-Fi по прямой просьбе «открой настройки Wi-Fi»; wifi_connect — подключиться или переключиться на сеть по имени (Студио = Studio, Локал нетворк = Local_Network_5G). Не используй wifi для проверки статуса. Для wifi_connect Android открывает системный выбор, где пользователь подтверждает сеть. Не утверждай, что подключение завершено, пока wifi_status не подтвердит SSID."
        },
        "networkName": {
          "type": "string",
          "description": "Целевая сеть Wi-Fi: Студио / Studio или Локал нетворк / Local_Network_5G."
        },
        "deviceName": {
          "type": "string",
          "description": "Имя или разговорное название сохранённого устройства. Например STUDIO / студия или Odyssey Ark / Одиссей Арк. Не придумывай имя."
        }
      },
      "required": ["action"]
    }
  }
}
</script>

<script setup>
const BRIDGE = 'http://127.0.0.1:38761';

function normalize(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/ё/g, 'е')
    .replace(/[^a-zа-я0-9]/g, '');
}

function request(path, method, token, data) {
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
      timeout: 12000,
      success(res) {
        const status = Number(res && res.statusCode || 0);
        if (status >= 200 && status < 300) {
          let body = res.data;
          if (typeof body === 'string') {
            try { body = JSON.parse(body); } catch (e) {}
          }
          resolve(body);
        } else {
          const error = new Error('HTTP ' + status);
          error.statusCode = status;
          error.stage = path;
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

async function requestWithRetry(path, method, token, data, attempts) {
  let lastError;
  const total = attempts || 3;
  for (let attempt = 0; attempt < total; attempt += 1) {
    try {
      return await request(path, method, token, data);
    } catch (error) {
      lastError = error;
      if (error && error.statusCode) throw error;
      if (attempt + 1 < total) await delay(700 * (attempt + 1));
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
      speechSynthesis.speak(new SpeechSynthesisUtterance(text));
    }
  } catch (e) {
    console.warn('RE:Vision speech fallback failed', e);
  }
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

export default {
  data: {
    title: 'RE:Vision',
    statusText: 'Подключение к RE:Vision…',
    detailText: '',
    resultText: '',
    showSettingsButton: false,
    busy: true
  },

  openSettings() {
    wx.navigateTo({ url: '/pages/settings/index' });
  },

  async onLoad(query) {
    const action = String(query.action || '').toLowerCase();
    const rawRequestedName = query.deviceName ?? query.device ?? '';
    const requestedText = String(rawRequestedName).trim();
    const requestedName = /^(null|undefined|none)$/i.test(requestedText) ? '' : requestedText;

    this.setData({
      statusText: 'Проверяю связь с RE:Vision…',
      detailText: requestedName,
      busy: true
    });

    try {
      const health = await requestWithRetry('/health', 'GET', '', null, 4);
      if (!health || health.ok !== true) throw new Error('Неожиданный ответ /health');
      if (['wifi', 'wifi_connect', 'wifi_status', 'wifi_list'].includes(action) && health.wifiRoute !== true) {
        const message = 'Установленная версия RE:Vision на очках устарела и не поддерживает открытие Wi-Fi. AIUI уже обновлён, но нужно обновить само Android-приложение RE:Vision.';
        this.setData({ statusText: message, detailText: 'Сохранённые устройства и настройки не трогай и не удаляй.', busy: false });
        speak(message);
        return;
      }

      const token = wx.getStorageSync('revision_bridge_token');
      if (!token) {
        const message = 'Связь с RE:Vision обнаружена, но ключ доступа ещё не настроен. Открой настройки агента и введи ключ из приложения RE:Vision.';
        this.setData({ statusText: message, detailText: 'Команды пока заблокированы до настройки защиты.', showSettingsButton: true, busy: false });
        speak('Связь с RE:Vision есть. Нужно настроить ключ доступа.');
        return;
      }

      if (action === 'wifi' || action === 'wifi_connect') {
        const targetNetwork = String(query.networkName || query.ssid || '');
        const result = action === 'wifi_connect'
          ? await request('/wifi/connect', 'POST', token, { networkName: targetNetwork })
          : await request('/wifi', 'POST', token, {});
        if (!result || result.ok !== true || result.opened !== true) {
          throw new Error(result && result.error ? result.error : 'Не удалось открыть системные настройки Wi-Fi');
        }
        const message = action === 'wifi_connect'
          ? 'Открыл системный выбор Wi-Fi. Выбери сеть ' + result.targetSsid + ' и подтверди подключение, если Android попросит. Затем скажи: Ревижн, проверь Wi-Fi.'
          : 'Открыл список сетей Wi-Fi на очках. Выбери нужную точку доступа.';
        this.setData({ statusText: message, detailText: action === 'wifi_connect' ? 'Целевая сеть: ' + result.targetSsid + '. Подключение ещё не подтверждено.' : (result.mode === 'panel' ? 'Системная панель Wi-Fi.' : 'Открыта полная страница настроек Wi-Fi.'), resultText: message, busy: false });
        speak(message);
        return;
      }

      if (action === 'wifi_status') {
        const state = await request('/wifi/status', 'GET', token);
        const message = state && state.connected
          ? 'Сейчас подключено к сети ' + state.ssid + '.'
          : (state && state.enabled ? 'Wi-Fi включён, но имя текущей сети недоступно.' : 'Wi-Fi выключен или соединение отсутствует.');
        this.setData({ statusText: message, detailText: state && state.permissionRequired ? 'Android ограничил доступ к имени сети.' : '', resultText: message, busy: false });
        speak(message);
        return;
      }

      if (action === 'wifi_list') {
        const scan = await requestWithRetry('/wifi/networks', 'GET', token, null, 2);
        if (!scan || scan.ok !== true) {
          const why = scan && scan.error === 'wifi_scan_permission_required'
            ? 'Android не выдал RE:Vision разрешение на сканирование Wi-Fi. Разреши доступ к местоположению в настройках приложения.'
            : (scan && scan.error ? scan.error : 'Не удалось получить список сетей.');
          this.setData({ statusText: why, busy: false });
          speak(why);
          return;
        }
        const names = (scan.networks || []).map(item => item.ssid);
        const message = names.length ? 'Вижу сети: ' + names.join(', ') : 'Список сетей пока пуст. Повтори запрос через несколько секунд.';
        this.setData({ statusText: message, detailText: 'Сканирование запрошено: ' + (scan.scanStarted ? 'да' : 'нет'), resultText: message, busy: false });
        speak(message);
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
        speak(message);
        return;
      }

      const resolved = resolveDevice(list.devices, requestedName);
      if (resolved.error === 'device_name_required') {
        const message = 'Не расслышал название устройства. Назови его ещё раз.';
        this.setData({ statusText: message, busy: false });
        speak(message);
        return;
      }
      if (resolved.error === 'device_ambiguous') {
        const names = resolved.matches.map(item => item.name).join(', ');
        const message = 'Нашёл несколько устройств: ' + names + '. Уточни, какое именно.';
        this.setData({ statusText: message, detailText: '', busy: false });
        speak(message);
        return;
      }
      if (!resolved.device) {
        const message = 'Устройство «' + requestedName + '» не найдено в RE:Vision.';
        this.setData({ statusText: message, busy: false });
        speak(message);
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
        speak(message);
        return;
      }

      if (action !== 'wake' && action !== 'shutdown') {
        throw new Error('Операция не поддерживается: ' + action);
      }
      if (action === 'wake' && device.canWake !== true) {
        const message = 'Для ' + device.name + ' в RE:Vision не настроено включение Wake-on-LAN.';
        this.setData({ statusText: message, busy: false });
        speak(message);
        return;
      }
      if (action === 'shutdown' && device.canShutdown !== true) {
        const message = 'Для ' + device.name + ' в RE:Vision не настроено удалённое выключение SSH.';
        this.setData({ statusText: message, busy: false });
        speak(message);
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
            speak(message);
            return;
          }
        }
        const message = 'Команда включения отправлена, но ' + device.name + ' пока не ответил. Включение ещё не подтверждено.';
        this.setData({ statusText: message, detailText: 'Проверял доступность до 12 секунд.', resultText: message, busy: false });
        speak(message);
        return;
      }

      await delay(3000);
      const state = await request('/status?deviceId=' + encodeURIComponent(device.id), 'GET', token);
      if (!state || state.ok !== true) throw new Error(state && state.error ? state.error : 'Не удалось проверить состояние после команды');
      const message = state.online
        ? 'Команда выключения отправлена, но ' + device.name + ' всё ещё отвечает в сети. Выключение не подтверждено.'
        : 'После команды ' + device.name + ' перестал отвечать по сети. Это не является абсолютным подтверждением выключения.';
      this.setData({ statusText: message, detailText: 'Состояние питания не подтверждено напрямую.', resultText: message, busy: false });
      speak(message);
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
      } else if (error && error.networkFailure) {
        message = 'Не удалось достучаться до RE:Vision на очках. Сам запрос не получил HTTP-ответ.';
        detail += ' Точная ошибка AIUI: ' + raw;
      }
      this.setData({ statusText: message, detailText: detail, busy: false });
      speak(message);
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
