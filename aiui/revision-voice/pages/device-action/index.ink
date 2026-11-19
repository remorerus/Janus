<script def>
{
  "navigationBarTitleText": "RE:Vision",
  "description": "Голосовое управление устройствами RE:Vision и открытие системных настроек Wi-Fi на очках Rokid. Показывает сохранённые устройства, проверяет доступность, включает и выключает настроенные устройства, а по команде открыть Wi-Fi вызывает системную панель выбора сети.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "action": {
          "type": "string",
          "enum": ["list", "status", "wake", "shutdown", "wifi"],
          "description": "Операция: list — показать устройства; status — проверить доступность; wake — включить; shutdown — выключить; wifi — открыть системные настройки Wi-Fi для ручного выбора сети."
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
      reject(new Error('wx.request недоступен в этом AIUI runtime'));
      return;
    }
    const header = { 'content-type': 'application/json' };
    if (token) header['X-Revision-Token'] = token;
    wx.request({
      url: BRIDGE + path,
      method: method || 'GET',
      header,
      data,
      timeout: 6000,
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data);
        } else {
          reject(new Error('HTTP ' + res.statusCode));
        }
      },
      fail(err) {
        reject(new Error(err && err.errMsg ? err.errMsg : 'Сетевая ошибка'));
      }
    });
  });
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
    const requestedName = String(query.deviceName || query.device || '');

    this.setData({
      statusText: 'Проверяю связь с RE:Vision…',
      detailText: requestedName,
      busy: true
    });

    try {
      const health = await request('/health', 'GET', '');
      if (!health || health.ok !== true) throw new Error('Неожиданный ответ /health');
      if (action === 'wifi' && health.wifiRoute !== true) {
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

      if (action === 'wifi') {
        const result = await request('/wifi', 'POST', token, {});
        if (!result || result.ok !== true || result.opened !== true) {
          throw new Error(result && result.error ? result.error : 'Не удалось открыть системные настройки Wi-Fi');
        }
        const message = 'Открыл настройки Wi-Fi. Выбери нужную сеть на очках.';
        this.setData({ statusText: message, detailText: result.mode === 'panel' ? 'Системная панель Wi-Fi.' : 'Открыта полная страница настроек Wi-Fi.', resultText: message, busy: false });
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
      const raw = error && error.message ? error.message : String(error);
      const message = raw === 'HTTP 404'
        ? 'Установленная версия RE:Vision не знает команду Wi-Fi. Нужно обновить Android-приложение RE:Vision; AIUI уже обращается к правильному адресу.'
        : raw.includes('Failed to fetch') || raw.includes('Network') || raw.includes('refused') || raw.includes('timeout')
          ? 'AIUI не смог связаться с локальным мостом RE:Vision. Нужно проверить доступ к localhost на самих очках.'
          : 'Ошибка RE:Vision: ' + raw;
      this.setData({ statusText: message, detailText: 'Никакая команда не считается выполненной без подтверждения.', busy: false });
      speak(message);
      console.error('RE:Vision action failed', error);
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
