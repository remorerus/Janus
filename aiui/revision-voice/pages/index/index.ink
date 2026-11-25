<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Стартовая страница голосового агента Ревижн. При голосовом пробуждении запускает собственный короткий цикл распознавания русской речи и направляет распознанные команды в существующую страницу управления устройствами и Wi-Fi. Также остаётся точкой входа для обычного запуска агента."
}
</script>

<script setup>
export default {
  data: {
    title: 'Ревижн',
    statusText: 'Ревижн готов. Скажи команду.',
    detailText: 'Голосовое управление устройствами и Wi-Fi.'
  },

  onLoad(query) {
    const keys = query && typeof query === 'object' ? Object.keys(query) : [];
    console.log('[RE:Vision] startup page loaded', { queryKeys: keys });
    this.setData({
      statusText: 'Ревижн активен. Готов принять команду.',
      detailText: 'Ожидаю команду через штатный голосовой канал Rokid.'
    });
  },

  onShow() {
    console.log('[RE:Vision] startup page shown');
    this.setData({
      statusText: 'Ревижн готов. Одно касание дужки — слушаю команду.',
      detailText: 'После выполнения команды вернулся на начальный экран.'
    });
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] voice/touch wakeup received', { keyword });
    // A single temple tap is the agent's push-to-talk trigger. Intercept the
    // host shortcut so Rokid does not require a press-and-hold gesture.
    if (keyword === 'clickAiAssist') {
      if (event && typeof event.preventDefault === 'function') event.preventDefault();
      console.log('[RE:Vision] single-tap AI Assistant shortcut intercepted; starting Russian recognition');
      this.startListening();
      return;
    }
    // Explicit non-touch wake words use the same local Russian recognition route.
    if (event && typeof event.preventDefault === 'function') event.preventDefault();
    this.startListening();
  },

  onKeyUp(event) {
    const code = String(event && event.code || '');
    if (code === 'GlobalHook') {
      // Some firmware versions report the same single tap as GlobalHook.
      // Prevent the native long-press assistant and start recognition once.
      if (event && typeof event.preventDefault === 'function') event.preventDefault();
      console.log('[RE:Vision] single temple tap received through GlobalHook');
      this.startListening();
    }
  },

  onUnload() {
    if (this.recognition && typeof this.recognition.abort === 'function') {
      try { this.recognition.abort(); } catch (e) {}
    }
    this.recognition = null;
    this.voiceListening = false;
  },

  startListening() {
    if (this.voiceListening) return;
    if (typeof SpeechRecognition === 'undefined') {
      this.setData({
        statusText: 'Распознавание речи недоступно.',
        detailText: 'Штатный голосовой канал Rokid остаётся доступен.'
      });
      return;
    }

    this.voiceListening = true;
    this.setData({
      statusText: 'Слушаю команду…',
      detailText: 'Скажи команду по-русски.'
    });

    let recognition;
    try {
      recognition = new SpeechRecognition();
      this.recognition = recognition;
      recognition.lang = 'ru-RU';
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;

      recognition.onresult = (resultEvent) => {
        const result = resultEvent && resultEvent.results &&
          resultEvent.results[resultEvent.resultIndex || 0];
        const alternative = result && result[0];
        const transcript = String(alternative && alternative.transcript || '').trim();
        this.voiceListening = false;
        this.recognition = null;
        if (typeof recognition.abort === 'function') {
          try { recognition.abort(); } catch (e) {}
        }
        if (!transcript) {
          this.setData({ statusText: 'Не расслышал команду.', detailText: 'Повтори после пробуждения Ревижн.' });
          return;
        }
        console.log('[RE:Vision] local speech recognized', transcript);
        this.routeVoiceCommand(transcript);
      };

      recognition.onerror = (error) => {
        console.warn('[RE:Vision] speech recognition error', error);
        this.voiceListening = false;
        this.recognition = null;
        this.setData({
          statusText: 'Не удалось распознать речь.',
          detailText: 'Ошибка микрофона/распознавания. Штатный канал Rokid не изменён.'
        });
      };

      recognition.onend = () => {
        this.voiceListening = false;
        if (this.recognition === recognition) this.recognition = null;
      };

      recognition.start();
    } catch (error) {
      this.voiceListening = false;
      this.recognition = null;
      console.warn('[RE:Vision] could not start SpeechRecognition', error);
      this.setData({
        statusText: 'Не удалось запустить распознавание.',
        detailText: String(error && error.message || error)
      });
    }
  },

  routeVoiceCommand(transcript) {
    const text = String(transcript || '').toLowerCase().replace(/ё/g, 'е').trim();
    const compact = text.replace(/[^a-zа-я0-9]+/g, ' ').trim();
    let params = null;

    const hasWifi = /(wi\s*fi|вай\s*фай|вайфай|вайфая)/i.test(compact);
    const isStudioPc = /(studio\s*pc|студио\s*писи|студи\s*писи|студия|студийный\s*пк|компьютер|комп|пк)/i.test(compact);
    const bridgePhrase = /(voice\s*bridge|войс\s*бридж|голосов\w* мост|настройк\w* ревижн|проверь службу ревижн|служб\w* ревижн|дай доступ к voice bridge|подключись к voice bridge|восстанови voice bridge|почему не работает voice bridge|настройк\w* голосов\w* мост)/i.test(compact);

    // Voice Bridge maintenance commands take priority over generic device routing.
    if (bridgePhrase) {
      params = { action: 'bridge_settings' };
    }

    // Wi-Fi radio controls are explicit independent actions, never Settings or a toggle intent.
    if (!params && hasWifi && /(выключи|выключить|отключи|отключить|выруби)/i.test(compact)) {
      params = { action: 'wifi_disable' };
    } else if (!params && hasWifi && /(включи|включить|активируй|подними)/i.test(compact)) {
      params = { action: 'wifi_enable' };
    } else if (!params && hasWifi && /(какая сеть|какую сеть|проверь|статус|состояни|назови)/i.test(compact)) {
      params = { action: 'wifi_status' };
    } else if (!params && hasWifi && /(список|покажи|доступн|видим)/i.test(compact)) {
      params = { action: 'wifi_list' };
    } else if (!params && hasWifi && /(открой|открыть|настройк)/i.test(compact)) {
      params = { action: 'wifi' };
    }

    // Named network shortcuts: the user commonly says only "подключись к Студио".
    if (!params && /(подключись|переключись|соединись|подключи|перейди)/i.test(compact) &&
        /(локал\s*нетворк|local\s*network|local\s*network\s*5g|студио|studio)/i.test(compact)) {
      const networkName = /(локал\s*нетворк|local\s*network)/i.test(compact)
        ? 'Local_Network_5G' : 'Studio';
      params = { action: 'wifi_connect', networkName };
    }

    // SSH catalog commands are separate from Windows Agent application launches.
    // The manager stores only the command phrase (for example "открой Mixxx"),
    // not the wake/transport words ("Ревижн, выполни через SSH").
    if (!params && /(через ssh|по ssh|ssh агент|эс эс эйч|эсэсэйч)/i.test(compact) &&
        /(запусти|выполни|открой|запуск|команду)/i.test(compact)) {
      const sshMarker = /(через\s+ssh|по\s+ssh|ssh\s+агент|эс\s+эс\s+эйч|эсэсэйч)/i.exec(transcript);
      let commandPhrase = '';
      if (sshMarker) {
        const afterMarker = transcript.slice(sshMarker.index + sshMarker[0].length)
          .replace(/^([\s,;:—-]*)(команду|команда)\b/i, '')
          .trim();
        if (afterMarker) {
          commandPhrase = afterMarker;
        } else {
          commandPhrase = transcript.slice(0, sshMarker.index)
            .replace(/^\s*(ревижн[\s,.:;-]*)?(пожалуйста[\s,.:;-]*)?(выполни[\s,.:;-]*команду[\s,.:;-]*)?/i, '')
            .trim();
        }
      }
      commandPhrase = commandPhrase.replace(/[.!?]+$/g, '').trim();
      if (commandPhrase) {
        params = { action: 'windows_ssh_launch', deviceName: 'STUDIO PC', commandPhrase };
      }
    }

    // Windows STUDIO PC commands. Bare "компьютер" refers to the paired STUDIO PC.
    if (!params && /(отмени|отменить|сними отмену).*(выключени|перезагрузк)|отмени выключение|отмени перезагрузку/i.test(compact)) {
      params = { action: 'windows_cancel_shutdown', deviceName: 'STUDIO PC' };
    } else if (!params && isStudioPc && /(заблокируй|заблокировать|блокируй|заблокируй экран)/i.test(compact)) {
      params = { action: 'windows_lock', deviceName: 'STUDIO PC' };
    } else if (!params && isStudioPc && /(перезагрузи|перезагрузить|рестарт|перезапусти компьютер)/i.test(compact)) {
      params = { action: 'windows_restart', deviceName: 'STUDIO PC' };
    } else if (!params && isStudioPc && /(выключи|выключить|отключи|заверши|завершить работу)/i.test(compact)) {
      params = { action: 'windows_shutdown', deviceName: 'STUDIO PC' };
    } else if (!params && /(проверь|статус|состояние).*(windows\s*agent|виндовс|студио\s*писи|студия|компьютер|комп)/i.test(compact)) {
      params = { action: 'windows_status', deviceName: 'STUDIO PC' };
    } else if (!params && /(запусти|открой|закрой|закрыть|перезапусти|перезапуск).*(mixxx|\.exe|\.bat)/i.test(compact)) {
      params = { action: 'windows_launch', deviceName: 'STUDIO PC', commandPhrase: transcript };
    }

    if (!params) {
      if (/(список устройств|покажи устройства|какие устройства|мои устройства)/i.test(compact)) {
        params = { action: 'list' };
      } else {
        const verb = /(включи|включить|запусти|разбуди)/i.test(compact) ? 'wake'
          : (/(выключи|выключить|отключи|отключить|заверши)/i.test(compact) ? 'shutdown'
          : (/(проверь|проверить|статус|состояние)/i.test(compact) ? 'status' : ''));
        if (verb) {
          let deviceName = compact
            .replace(/^(пожалуйста\s+)?(ревижн\s+)?(включи|включить|запусти|разбуди|выключи|выключить|отключи|отключить|заверши|проверь|проверить|статус|состояние)\s*/i, '')
            .replace(/^(компьютер|устройство|пк)\s+/i, '')
            .replace(/\s+(пожалуйста|сейчас)$/i, '')
            .trim();
          params = { action: verb, deviceName };
        }
      }
    }

    if (!params) {
      this.setData({
        statusText: 'Команду не удалось определить.',
        detailText: 'Распознано: «' + transcript + '». Повтори команду или используй штатный голосовой канал Rokid.'
      });
      return;
    }

    console.log('[RE:Vision] voice command routed', { transcript, action: params.action, deviceName: params.deviceName || '', networkName: params.networkName || '' });
    const query = Object.keys(params)
      .filter((key) => params[key] !== undefined && params[key] !== '')
      .map((key) => encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key])))
      .join('&');
    this.setData({
      statusText: 'Команда распознана.',
      detailText: transcript
    });
    wx.navigateTo({ url: '/pages/device-action/index?' + query });
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">{{title}}</text>
    <text class="status">{{statusText}}</text>
    <text class="detail">{{detailText}}</text>
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
.detail {
  font-size: 14px;
  line-height: 1.35;
  opacity: 0.9;
}
</style>
