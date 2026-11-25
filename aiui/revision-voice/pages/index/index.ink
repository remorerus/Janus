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
    statusText: 'Ревижн готов. Удерживай сенсор двумя пальцами.',
    detailText: 'После удержания начнётся одноразовое прослушивание.',
    transcript: ''
  },

  onLoad(query) {
    this.pageLoadedAt = Date.now();
    this.initialShowPending = true;
    this.voiceListening = false;
    this.voiceHandled = false;
    this.voiceSpeechStarted = false;
    this.commandNavigating = false;
    const keys = query && typeof query === 'object' ? Object.keys(query) : [];
    console.log('[RE:Vision] startup page loaded', { queryKeys: keys });
    this.setData({
      statusText: 'Ревижн запускается. Готовлю микрофон…',
      detailText: 'При вызове агента прослушивание начинается автоматически.',
      transcript: ''
    });
    // The two-finger system gesture has already opened this agent. Try to
    // consume that launch activation immediately; GlobalHook remains the
    // fallback for later long holds while the page is already open.
    this.startListening();
  },

  onShow() {
    if (this.initialShowPending) {
      this.initialShowPending = false;
      return;
    }
    if (this.closeAfterReturn) {
      this.closeAfterReturn = false;
      this.setData({
        statusText: 'Команда завершена. Возвращаюсь к Rokid.',
        detailText: 'Ревижн закроется; для следующей команды снова удерживай сенсор двумя пальцами.'
      });
      this.scheduleAgentClose(1200);
      return;
    }
    if (!this.voiceListening) {
      this.setData({
        statusText: 'Ревижн готов. Удерживай сенсор двумя пальцами.',
        detailText: 'После одной команды Ревижн вернётся к Rokid.',
        transcript: ''
      });
    }
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] wakeup ignored outside two-finger hold', { keyword });
    // A normal tap or a normal Rokid wake word must not start RE:Vision commands.
    if (event && typeof event.preventDefault === 'function') event.preventDefault();
    this.setData({
      statusText: keyword === 'clickAiAssist'
        ? 'Обычное касание не запускает Ревижн.'
        : 'Ожидаю специальный вызов Ревижна.',
      detailText: 'Для команд удерживай сенсор дужки двумя пальцами.'
    });
  },

  onKeyDown(event) {
    if (event && event.code === 'GlobalHook') {
      this.globalHookPressedAt = Date.now();
      console.log('[RE:Vision] temple GlobalHook down');
    }
  },

  onKeyUp(event) {
    if (!event || event.code !== 'GlobalHook') return;
    const startedAt = Number(this.globalHookPressedAt || 0);
    this.globalHookPressedAt = 0;
    const heldMs = startedAt ? Math.max(0, Date.now() - startedAt) : 0;
    if (event && typeof event.preventDefault === 'function') event.preventDefault();

    // A tap without a measured long press is never allowed to start commands.
    // The initial two-finger system gesture is handled by the onLoad startup.
    if (!startedAt || heldMs < 700) {
      console.log('[RE:Vision] short or unpaired temple tap ignored', { heldMs });
      this.setData({
        statusText: 'Короткое касание проигнорировано.',
        detailText: 'Удерживай сенсор двумя пальцами для вызова Ревижна.'
      });
      return;
    }
    if (this.voiceListening) return;
    console.log('[RE:Vision] hold accepted; starting one-shot Russian recognition', { heldMs });
    this.startListening();
  },

  onUnload() {
    this.clearVoiceTimers();
    const recognition = this.recognition;
    this.recognition = null;
    this.voiceListening = false;
    if (recognition && typeof recognition.abort === 'function') {
      recognition.onresult = null;
      recognition.onerror = null;
      recognition.onend = null;
      try { recognition.abort(); } catch (e) {}
    }
  },

  clearVoiceTimers() {
    ['noSpeechTimer', 'speechEndTimer', 'hardStopTimer', 'resetTimer'].forEach(key => {
      if (this[key]) clearTimeout(this[key]);
      this[key] = null;
    });
  },

  scheduleAgentClose(delay) {
    if (this.resetTimer) clearTimeout(this.resetTimer);
    this.resetTimer = setTimeout(() => {
      this.resetTimer = null;
      this.requestAgentClose();
    }, delay);
  },

  requestAgentClose() {
    try {
      if (typeof wx !== 'undefined' && typeof wx.exitMiniProgram === 'function') {
        wx.exitMiniProgram({
          success: () => console.log('[RE:Vision] agent exit accepted'),
          fail: (error) => {
            console.warn('[RE:Vision] agent exit was not accepted', error);
            this.setData({
              statusText: 'Прослушивание закрыто.',
              detailText: 'Для новой команды снова удерживай сенсор двумя пальцами.'
            });
          }
        });
        return;
      }
    } catch (error) {
      console.warn('[RE:Vision] wx.exitMiniProgram failed', error);
    }
    try {
      if (typeof window !== 'undefined' && typeof window.close === 'function') {
        window.close();
        return;
      }
    } catch (error) {
      console.warn('[RE:Vision] window.close failed', error);
    }
    this.setData({
      statusText: 'Прослушивание закрыто.',
      detailText: 'Автоматическое закрытие недоступно. Для новой команды удерживай сенсор двумя пальцами.'
    });
  },



  finishListening(message, detail, recognition, closeDelay) {
    if (this.voiceHandled) return;
    this.voiceHandled = true;
    this.clearVoiceTimers();
    this.voiceListening = false;
    if (this.recognition === recognition) this.recognition = null;
    if (recognition && typeof recognition.abort === 'function') {
      recognition.onresult = null;
      recognition.onerror = null;
      recognition.onend = null;
      try { recognition.abort(); } catch (e) {}
    }
    this.setData({ statusText: message, detailText: detail });
    this.scheduleAgentClose(Number(closeDelay) || 1800);
  },

  startListening() {
    if (this.voiceListening) return;
    if (typeof SpeechRecognition === 'undefined') {
      this.setData({
        statusText: 'Распознавание речи недоступно.',
        detailText: 'В этой версии AIUI нет SpeechRecognition. Закрою Ревижн.'
      });
      this.scheduleAgentClose(3500);
      return;
    }

    this.clearVoiceTimers();
    this.voiceHandled = false;
    this.voiceSpeechStarted = false;
    this.voiceListening = true;
    this.setData({
      statusText: 'Слушаю команду…',
      detailText: 'Скажи одну команду по-русски.',
      transcript: ''
    });

    let recognition;
    try {
      recognition = new SpeechRecognition();
      this.recognition = recognition;
      recognition.lang = 'ru-RU';
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.maxAlternatives = 1;

      recognition.onstart = () => {
        this.setData({ statusText: 'Микрофон включён. Слушаю…' });
      };

      recognition.onaudiostart = () => {
        this.setData({ statusText: 'Микрофон активен. Говори…' });
      };

      recognition.onspeechstart = () => {
        this.voiceSpeechStarted = true;
        if (this.noSpeechTimer) clearTimeout(this.noSpeechTimer);
        this.noSpeechTimer = null;
        this.setData({ statusText: 'Распознаю речь…' });
      };

      recognition.onspeechend = () => {
        if (this.speechEndTimer) clearTimeout(this.speechEndTimer);
        this.speechEndTimer = setTimeout(() => {
          if (!this.voiceHandled && this.recognition === recognition) {
            try { recognition.stop(); } catch (e) {}
          }
        }, 1800);
      };

      recognition.onresult = (resultEvent) => {
        if (this.voiceHandled) return;
        let interim = '';
        let finalText = '';
        const results = resultEvent && resultEvent.results;
        if (!results) return;
        const startIndex = Number(resultEvent.resultIndex || 0);
        for (let i = startIndex; i < results.length; i++) {
          const result = results[i];
          const text = String(result && result[0] && result[0].transcript || '').trim();
          if (!text) continue;
          if (result.isFinal) finalText = text;
          else interim = text;
        }
        if (finalText) {
          this.voiceHandled = true;
          this.clearVoiceTimers();
          this.voiceListening = false;
          this.recognition = null;
          try { recognition.stop(); } catch (e) {}
          console.log('[RE:Vision] final Russian transcript', finalText);
          this.setData({
            statusText: 'Команда распознана. Выполняю…',
            detailText: 'Распознано:',
            transcript: finalText
          });
          this.commandNavigating = true;
          this.routeVoiceCommand(finalText);
          return;
        }
        if (interim) {
          this.setData({
            statusText: 'Распознаю…',
            detailText: 'Пока услышано:',
            transcript: interim
          });
        }
      };

      recognition.onerror = (error) => {
        const code = String(error && error.error || 'unknown');
        console.warn('[RE:Vision] SpeechRecognition error', { code, message: error && error.message });
        const message = code === 'no-speech' ? 'Не услышал речь.' : 'Не удалось распознать команду.';
        const detail = code === 'not-allowed' || code === 'service-not-allowed'
          ? 'Нет разрешения на микрофон. Проверь RECORD_AUDIO и разрешение AIUI.'
          : 'Сеанс прослушивания закрыт. Для повтора снова удерживай сенсор двумя пальцами.';
        this.finishListening(message, detail, recognition, code === 'not-allowed' || code === 'service-not-allowed' ? 5000 : 1800);
      };

      recognition.onend = () => {
        if (this.voiceHandled) return;
        this.clearVoiceTimers();
        this.voiceListening = false;
        if (this.recognition === recognition) this.recognition = null;
        this.setData({
          statusText: this.voiceSpeechStarted ? 'Не удалось получить фразу.' : 'Время ожидания истекло.',
          detailText: 'Прослушивание закрыто. Возвращаюсь к Rokid.'
        });
        this.scheduleAgentClose(1500);
      };

      this.noSpeechTimer = setTimeout(() => {
        if (!this.voiceSpeechStarted && !this.voiceHandled) {
          this.finishListening('Время ожидания истекло.', 'Ничего не услышал. Прослушивание закрыто.', recognition);
        }
      }, 8000);
      this.hardStopTimer = setTimeout(() => {
        if (!this.voiceHandled) {
          this.finishListening('Время прослушивания завершено.', 'Сеанс закрыт. Для новой команды снова удерживай сенсор двумя пальцами.', recognition);
        }
      }, 25000);

      recognition.start();
    } catch (error) {
      this.finishListening(
        'Не удалось запустить прослушивание.',
        String(error && error.message || error),
        recognition
      );
    }
  },

  routeVoiceCommand(transcript) {
    const text = String(transcript || '').toLowerCase().replace(/ё/g, 'е').trim();
    const compact = text.replace(/[^a-zа-я0-9]+/g, ' ').trim();
    let params = null;

    // Device-list intent must win before generic Windows/SSH command fallbacks.
    // A list request never carries a deviceName.
    const listIntent = /(?:список|перечень) устройств|покажи(?:\s+мне)?\s+(?:все\s+)?устройства|какие\s+устройства|мои\s+устройства|какие\s+устройства\s+сохранены/i.test(compact);
    if (listIntent) {
      params = { action: 'list' };
    }

    const hasWifi = /(wi\s*fi|вай\s*фай|вайфай|вайфая)/i.test(compact);
    const isStudioPc = /(studio\s*pc|студио\s*писи|студи\s*писи|студия|студийный\s*пк|компьютер|комп|пк)/i.test(compact);
    const bridgePhrase = /(voice\s*bridge|войс\s*бридж|голосов\w* мост|настройк\w* ревижн|проверь службу ревижн|служб\w* ревижн|дай доступ к voice bridge|подключись к voice bridge|восстанови voice bridge|почему не работает voice bridge|настройк\w* голосов\w* мост)/i.test(compact);

    // Voice Bridge maintenance commands take priority over generic device routing.
    if (!params && bridgePhrase) {
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
          .replace(/^[\s,;:—-]*(команду|команда)(?=\s|$)[\s,;:—-]*/i, '')
          .replace(/^[\s,;:—-]+/, '')
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


    // Explicit Windows Agent transport marker: remove it before matching the
    // saved command phrase so "... AIMP через виндовс агент" uses the PC catalog.
    if (!params && /(?:через|по)\s+(?:windows|виндовс)\s*агент|(?:windows|виндовс)\s*агент/i.test(compact)) {
      const marker = /(?:через|по)\s+(?:windows|виндовс)\s*агент|(?:windows|виндовс)\s*агент/i;
      const commandPhrase = transcript
        .replace(marker, ' ')
        .replace(/^\s*ревижн[\s,.:;-]*/i, '')
        .replace(/^\s*выполни[\s,.:;-]*(?:команду[\s,.:;-]*)?/i, '')
        .replace(/[.!?]+$/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      if (commandPhrase) {
        params = { action: 'windows_launch', deviceName: 'STUDIO PC', commandPhrase };
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

    if (!params && /(запусти|запустить|открой|открыть|закрой|закрыть|перезапусти|перезапустить|переключись|переключить|сверни|свернуть|разверни|развернуть|восстанови|восстановить)/i.test(compact)) {
      // User-defined commands live in the Windows Agent catalog. Forward the
      // recognized phrase unchanged so the Windows Agent can match it safely.
      params = { action: 'windows_launch', deviceName: 'STUDIO PC', commandPhrase: transcript };
    }

    if (!params && /(chrome|хром|браузер|aimp|аимп|аип|плеер|музык|окно|приложен|скриншот|ctrl|control|alt|shift|win|клавиш)/i.test(compact)) {
      params = { action: 'windows_launch', deviceName: 'STUDIO PC', commandPhrase: transcript };
    }

    if (!params) {
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
      } else {
        // Any other phrase is a user-defined Windows Agent command candidate.
        params = { action: 'windows_launch', deviceName: 'STUDIO PC', commandPhrase: transcript };
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
    this.closeAfterReturn = true;
    wx.navigateTo({
      url: '/pages/device-action/index?' + query,
      fail: (error) => {
        this.closeAfterReturn = false;
        this.setData({
          statusText: 'Не удалось открыть выполнение команды.',
          detailText: String(error && error.errMsg || error || 'navigation failed')
        });
        this.scheduleAgentClose(2000);
      }
    });
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">{{title}}</text>
    <text class="status">{{statusText}}</text>
    <text class="detail">{{detailText}}</text>
    <text class="transcript">{{transcript}}</text>
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
.transcript {
  font-size: 16px;
  line-height: 1.35;
  font-weight: 700;
  word-break: break-word;
}
.detail {
  font-size: 14px;
  line-height: 1.35;
  opacity: 0.9;
}
</style>
