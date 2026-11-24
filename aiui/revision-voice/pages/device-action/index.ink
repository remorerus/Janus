<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Голосовое управление устройствами RE:Vision и Wi-Fi на очках Rokid. Для Wi-Fi строго различай команды: узнать текущую подключённую сеть или проверить Wi-Fi — wifi_status; перечислить видимые сети — wifi_list; открыть системные настройки Wi-Fi — wifi; подключиться или переключиться на названную сеть — wifi_connect. Никогда не открывай настройки Wi-Fi в ответ на вопрос о текущей сети или её статусе. Для устройств показывай сохранённые устройства, проверяй доступность, включай и выключай настроенные устройства. Команды STUDIO PC: windows_status — проверить Windows Agent; windows_lock — заблокировать STUDIO PC штатной блокировкой Windows; windows_launch — выполнить сохранённую команду: запустить, закрыть или перезапустить EXE, либо запустить BAT; распознавай фразы «запусти Mixxx», «закрой Mixxx», «перезапусти Mixxx» и передавай исходную фразу в commandPhrase; windows_shutdown — выключить Windows; windows_restart — перезагрузить Windows; windows_cancel_shutdown — отменить запланированное выключение. Для этих команд требуется предварительное сопряжение Windows Agent в настройках Android-приложения RE:Vision.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "action": {
          "type": "string",
          "enum": ["list", "status", "wake", "shutdown", "wifi_enable", "wifi_disable", "wifi", "wifi_connect", "wifi_status", "wifi_list", "bridge_settings", "windows_status", "windows_lock", "windows_launch", "windows_shutdown", "windows_restart", "windows_cancel_shutdown"],
          "description": "Операция: list — показать устройства; status — проверить доступность устройства; wake — включить устройство; shutdown — выключить устройство; wifi_status — узнать, включён ли Wi-Fi и к какой сети подключены (в том числе вопросы «проверь Wi-Fi», «какая сеть подключена», «назови сеть»); wifi_list — перечислить видимые сети («покажи доступные сети»); wifi — только открыть системные настройки Wi-Fi по прямой просьбе «открой настройки Wi-Fi»; wifi_connect — подключиться или переключиться на сеть по имени (Студио = Studio, Локал нетворк = Local_Network_5G). wifi_enable — включить Wi-Fi; wifi_disable — выключить Wi-Fi; bridge_settings — открыть меню восстановления Voice Bridge и системные настройки специальных возможностей RE:Vision. Фразы «открой настройки Ревижн», «открой меню Voice Bridge», «зайди в Voice Bridge», «перейди напрямую в Voice Bridge», «открой страницу Voice Bridge», «войс бридж», «проверь службу Ревижн», «дай доступ к Voice Bridge», «подключись к Voice Bridge», «восстанови Voice Bridge», «почему не работает Voice Bridge», «открой настройки голосового моста», «включи службу Ревижн» должны выбирать bridge_settings. При любом явном запросе пользователя перейти непосредственно к Voice Bridge выбирай bridge_settings, а не обычные настройки приложения и не действие Wi-Fi. Для включения/выключения обязательно выбирай отдельное действие wifi_enable или wifi_disable по смыслу фразы. Не используй wifi_toggle, если AIUI не передал enabled. Команды включения/выключения Wi-Fi никогда не направляй в wifi и не открывай настройки. Если пользователь явно говорит «выключи Wi-Fi», «отключи Wi-Fi», «выруби вай-фай» или «отключи вайфай», всегда выбирай wifi_disable и не задавай уточняющий вопрос. Если пользователь говорит «включи Wi-Fi» или «подключи Wi-Fi», всегда выбирай wifi_enable. Не выбирай wifi_toggle: это внутренний режим совместимости, он не является голосовым действием. Не используй wifi для проверки статуса. Для wifi_connect Android открывает системный выбор, где пользователь подтверждает сеть. Не утверждай, что подключение завершено, пока wifi_status не подтвердит SSID."
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
          "description": "Имя или разговорное название сохранённого устройства. Например STUDIO / студия или Odyssey Ark / Одиссей Арк. Не придумывай имя."
        },
        "commandPhrase": {
          "type": "string",
          "description": "Точная голосовая фраза, сохранённая в каталоге Windows Agent, например «Запусти Mixxx». Передавай исходную команду пользователя без перефразирования."
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

  openSettings() {
    wx.navigateTo({ url: '/pages/settings/index' });
  },

  async onLoad(query) {
    const requestedAction = String((query && query.action) || '').toLowerCase().trim();
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
    const allowedActions = ['list', 'status', 'wake', 'shutdown', 'wifi_toggle', 'wifi', 'wifi_connect', 'wifi_status', 'wifi_list', 'windows_status', 'windows_lock', 'windows_launch', 'windows_shutdown', 'windows_restart', 'windows_cancel_shutdown'];
    if (!allowedActions.includes(action)) {
      const message = 'Команда не распознана как действие RE:Vision. Уточни команду: список устройств, статус устройства или команда Wi-Fi.';
      this.setData({ statusText: message, detailText: 'Не получено корректное значение action. Команды микрофона выполняются средствами Rokid и не относятся к RE:Vision.', busy: false });
      speak(message);
      return;
    }
    const rawRequestedName = query && (query.deviceName ?? query.device) || '';
    const requestedText = String(rawRequestedName).trim();
    const requestedName = /^(null|undefined|none)$/i.test(requestedText) ? '' : requestedText;

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
        speak(message);
        return;
      }

      const token = readBridgeToken();
      if (!token) {
        const message = 'Связь с RE:Vision обнаружена, но ключ доступа ещё не настроен. Открой настройки агента и введи ключ из приложения RE:Vision.';
        this.setData({ statusText: message, detailText: 'Команды пока заблокированы до настройки защиты.', showSettingsButton: true, busy: false });
        speak('Связь с RE:Vision есть. Нужно настроить ключ доступа.');
        return;
      }

      if (action.startsWith('windows_')) {
        const windowsAction = action.substring('windows_'.length);
        try {
          const result = windowsAction === 'status'
            ? await request('/windows/status', 'GET', token, null)
            : windowsAction === 'launch'
              ? await request('/windows/execute', 'POST', token, { phrase: String(query && query.commandPhrase || '').trim() })
              : await request('/windows/command/' + windowsAction.replace(/_/g, '-'), 'POST', token, {});
          if (!result || result.ok !== true) throw new Error(result && result.error ? result.error : 'Windows Agent вернул неожиданный ответ');
          let message;
          if (windowsAction === 'status') {
            const reachable = result.configured && result.agent && result.agent.paired === true;
            message = reachable ? 'Windows Agent STUDIO PC доступен и сопряжён.' : 'Windows Agent не настроен или недоступен. Проверь сопряжение и сеть.';
          } else {
            message = result.accepted === true ? (windowsAction === 'launch' ? 'Команда запуска передана STUDIO PC.' : 'Команда Windows передана компьютеру.') : 'Windows Agent не подтвердил выполнение команды.';
          }
          this.setData({ statusText: message, detailText: 'STUDIO PC · Windows Agent', resultText: message, busy: false });
          speak(message);
        } catch (error) {
          const message = 'Не удалось связаться с Windows Agent STUDIO PC.';
          this.setData({ statusText: message, detailText: String(error && error.message || error), busy: false });
          speak(message);
        }
        return;
      }

      if (action === 'wifi_toggle') {
        if (typeof toggleEnabled !== 'boolean') {
          const message = 'Уточни действие: включить или выключить Wi-Fi?';
          this.setData({ statusText: message, detailText: 'Не получил параметр enabled=true или enabled=false. Настройки не открывал.', busy: false });
          speak(message);
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
              speak(fallbackMessage);
              return;
            }
          }
          throw toggleError;
        }
        if (!result || result.ok !== true) {
          throw new Error(result && result.error ? result.error : 'Android не выполнил переключение Wi-Fi');
        }
        if (result.verified === true && result.enabled === requestedEnabled) {
          const message = requestedEnabled ? 'Wi-Fi включён. Состояние проверено.' : 'Wi-Fi выключен. Состояние проверено.';
          this.setData({ statusText: message, detailText: result.ssid ? 'Сеть: ' + result.ssid : '', resultText: message, busy: false });
          speak(message);
          return;
        }
        if (result.requiresUserAction === true) {
          const message = requestedEnabled
            ? 'Android не разрешил включить Wi-Fi напрямую. Открыл системные настройки Wi-Fi — включи переключатель вручную.'
            : 'Android не разрешил выключить Wi-Fi напрямую. Открыл системные настройки Wi-Fi — выключи переключатель вручную.';
          this.setData({ statusText: message, detailText: 'Переключение не подтверждено. Требуется действие в системных настройках.', resultText: message, busy: false });
          speak(message);
          return;
        }
        const message = 'Запрос на изменение Wi-Fi отправлен, но новое состояние не подтверждено.';
        this.setData({ statusText: message, detailText: JSON.stringify(result), resultText: message, busy: false });
        speak(message);
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
            speak(message);
            return;
          }
          const message = result.selectionClicked === true
            ? 'Сеть ' + (result.targetSsid || targetNetwork) + ' выбрана. Если Android запросит подтверждение, подтверди подключение. Затем скажи: Ревижн, проверь Wi-Fi.'
            : 'Открыл список Wi-Fi. Найди сеть ' + (result.targetSsid || targetNetwork) + ' и выбери её. Затем скажи: Ревижн, проверь Wi-Fi.';
          this.setData({ statusText: message, detailText: 'Подключение ещё не подтверждено Android.', resultText: message, busy: false });
          speak(message);
          return;
        }

        const message = openedPickerForChoice
          ? 'Открыл список доступных сетей Wi-Fi. Выбери нужную точку на экране очков и подтверди подключение. Затем скажи: Ревижн, проверь Wi-Fi.'
          : 'Открыл список сетей Wi-Fi на очках. Выбери нужную точку доступа.';
        this.setData({ statusText: message, detailText: result.mode === 'panel' ? 'Системная панель Wi-Fi.' : 'Открыта системная страница выбора сети.', resultText: message, busy: false });
        speak(message);
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
