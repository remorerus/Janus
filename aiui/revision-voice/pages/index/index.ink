<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Ревижн запускает распознавание русской речи непосредственно по событию голосового пробуждения Rokid или нажатию сенсора. Передаёт распознанную фразу в существующую маршрутизацию команд RE:Vision и остаётся активным для следующих команд."
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
    this.voiceListening = false;
    this.voiceHandled = false;
    this.voiceSpeechStarted = false;
    this.voiceRetryCount = 0;
    this.commandNavigating = false;
    const keys = query && typeof query === 'object' ? Object.keys(query) : [];
    console.log('[RE:Vision] startup page loaded', { queryKeys: keys });
    this.setData({
      statusText: 'Ревижн готов. Произнеси команду после вызова.',
      detailText: 'Распознавание запускается только по реальному событию голосового пробуждения или сенсорному вызову.',
      transcript: ''
    });
  },

  onShow() {
    this.closeAfterReturn = false;
    if (!this.voiceListening) {
      this.setData({
        statusText: 'Ревижн готов. Ожидаю следующую голосовую команду.',
        detailText: 'Скажи команду после вызова Ревижн.',
        transcript: ''
      });
    }
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    if (event && typeof event.preventDefault === 'function') event.preventDefault();
    console.log('[RE:Vision] voice/touch wakeup intercepted', { keyword });
    if (this.voiceListening) return;
    this.startListening();
  },

  onKeyDown(event) {
    if (event && event.code === 'GlobalHook') {
      this.globalHookPressedAt = Date.now();
      console.log('[RE:Vision] temple GlobalHook down');
    }
  },

  onKeyUp(event) {
    if (!event || event.code !== 'GlobalHook') return;
    if (event && typeof event.preventDefault === 'function') event.preventDefault();
    if (this.voiceListening) return;
    console.log('[RE:Vision] temple input accepted; starting Russian recognition');
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
    // Keep the agent alive after silence, errors and completed commands.
    // The next Rokid wakeup starts a fresh recognition session.
    if (this.resetTimer) clearTimeout(this.resetTimer);
    this.resetTimer = null;
    this.closeAfterReturn = false;
  },

  requestAgentClose() {
    // Intentionally disabled: RE:Vision must remain available for repeated commands.
    this.setData({
      statusText: 'Ревижн готов. Ожидаю следующую команду.',
      detailText: 'Для новой команды снова вызови Ревижн.'
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

  startListening(isRetry) {
    if (this.voiceListening) return;
    if (!isRetry) this.voiceRetryCount = 0;
    if (isRetry) this.voiceRetryCount = Number(this.voiceRetryCount || 0) + 1;
    if (typeof SpeechRecognition === 'undefined') {
      this.setData({
        statusText: 'Распознавание речи недоступно.',
        detailText: 'AIUI runtime не предоставил SpeechRecognition. Проверь версию прошивки и разрешение RECORD_AUDIO.'
      });
      this.scheduleAgentClose(3500);
      return;
    }

    this.clearVoiceTimers();
    this.voiceHandled = false;
    this.voiceSpeechStarted = false;
    this.voiceAttemptStartedAt = Date.now();
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
      // Ask the ASR service for several hypotheses when supported. Some Rokid
      // firmware versions return only one; the primary result remains a fallback.
      recognition.maxAlternatives = 5;

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
        }, 2200);
      };

      recognition.onresult = (resultEvent) => {
        if (this.voiceHandled) return;
        let interim = '';
        let finalText = '';
        let finalAlternatives = [];
        const results = resultEvent && resultEvent.results;
        if (!results) return;
        const startIndex = Number(resultEvent.resultIndex || 0);
        for (let i = startIndex; i < results.length; i++) {
          const result = results[i];
          const text = String(result && result[0] && result[0].transcript || '').trim();
          if (!text) continue;
          if (result.isFinal) {
            finalText = text;
            finalAlternatives = [];
            const alternativeCount = Math.min(5, Math.max(1, Number(result.length) || 1));
            for (let alternativeIndex = 0; alternativeIndex < alternativeCount; alternativeIndex++) {
              const candidate = String(result[alternativeIndex] && result[alternativeIndex].transcript || '').trim();
              if (candidate && !finalAlternatives.includes(candidate)) finalAlternatives.push(candidate);
            }
          } else {
            interim = text;
          }
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
          this.routeVoiceCommand(finalText, finalAlternatives);
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
        // Rokid ASR may emit no-speech while its microphone pipeline is still
        // becoming ready. Let onend perform a bounded retry instead of closing
        // the agent immediately. Permission errors remain terminal.
        if (code === 'no-speech' || code === 'aborted' || code === 'network') {
          this.lastRecognitionError = code;
          return;
        }
        const message = 'Не удалось запустить распознавание.';
        const detail = code === 'not-allowed' || code === 'service-not-allowed'
          ? 'Нет разрешения на микрофон. Проверь RECORD_AUDIO и разрешение AIUI.'
          : 'Ошибка службы распознавания: ' + code + '.';
        this.finishListening(message, detail, recognition, code === 'not-allowed' || code === 'service-not-allowed' ? 5000 : 3500);
      };

      recognition.onend = () => {
        if (this.voiceHandled) return;
        this.clearVoiceTimers();
        this.voiceListening = false;
        if (this.recognition === recognition) this.recognition = null;
        const elapsed = Date.now() - Number(this.voiceAttemptStartedAt || Date.now());
        const retryCount = Number(this.voiceRetryCount || 0);
        if (!this.voiceSpeechStarted && retryCount < 2 && elapsed < 18000) {
          console.warn('[RE:Vision] ASR ended before speech; retrying microphone startup', {
            retry: retryCount + 1, elapsedMs: elapsed, error: this.lastRecognitionError || ''
          });
          this.setData({
            statusText: 'Микрофон запускается…',
            detailText: 'Повторно подключаю распознавание. Начинай говорить после сигнала «Слушаю».'
          });
          this.resetTimer = setTimeout(() => {
            this.resetTimer = null;
            this.startListening(true);
          }, 800);
          return;
        }
        this.setData({
          statusText: this.voiceSpeechStarted ? 'Не удалось получить фразу.' : 'Распознавание не запустилось.',
          detailText: 'Не закрываю Ревижн мгновенно. Для повтора удерживай сенсор двумя пальцами.'
        });
        this.scheduleAgentClose(4500);
      };

      this.noSpeechTimer = setTimeout(() => {
        if (!this.voiceSpeechStarted && !this.voiceHandled) {
          this.finishListening('Время ожидания истекло.', 'Ничего не услышал. Прослушивание закрыто.', recognition);
        }
      }, 16000);
      this.hardStopTimer = setTimeout(() => {
        if (!this.voiceHandled) {
          this.finishListening('Время прослушивания завершено.', 'Сеанс закрыт. Для новой команды снова удерживай сенсор двумя пальцами.', recognition);
        }
      }, 40000);

      recognition.start();
    } catch (error) {
      this.finishListening(
        'Не удалось запустить прослушивание.',
        String(error && error.message || error),
        recognition
      );
    }
  },

  routeVoiceCommand(transcript, alternatives) {
    const text = String(transcript || '').toLowerCase().replace(/ё/g, 'е').trim();
    const compact = text.replace(/[^a-zа-я0-9]+/g, ' ').trim();
    let params = null;

    // Convert each ASR hypothesis to the same catalog-phrase shape that the
    // primary Windows Agent route uses. The Windows Agent performs the final
    // dictionary/fuzzy match against the commands actually configured on that PC.
    const commandCandidates = [];
    [transcript].concat(Array.isArray(alternatives) ? alternatives : []).forEach(candidate => {
      let value = String(candidate || '')
        .replace(/(?:через|по)\s+(?:windows|виндовс)\s*агент|(?:windows|виндовс)\s*агент/ig, ' ')
        .replace(/^\s*(?:ревижн|ревижен|ревижин|ревиж)\s*[,:.\-;]?\s*/i, '')
        .replace(/^\s*(?:пожалуйста\s*)?(?:выполни\s*)?(?:команду\s*)?/i, '')
        .replace(/[.!?]+$/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      if (value && value.length <= 160 && !commandCandidates.includes(value)) commandCandidates.push(value);
    });
    const attachCommandCandidates = () => {
      if (params && params.action === 'windows_launch' && commandCandidates.length && !params.commandAlternatives) {
        params.commandAlternatives = commandCandidates.slice(0, 5);
      }
    };

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

    // AIMP playback requests addressed to STUDIO are Windows Agent catalog
    // commands, not media playback on the Android phone. Remove the spoken
    // computer name from the catalog phrase and route to the paired STUDIO PC.
    const aimpPlaybackIntent = /(?:воспроизведи|воспроизвести|проиграй|проиграть|поставь|включи)\s+(?:трек\s+)?(?:aimp|аимп|аип)(?:\s|$)/i.test(compact);
    const explicitStudioTarget = /(?:studio\s*pc|studio|студио|студия|студийный\s*пк|на\s+студио)(?:\s|$)/i.test(compact);
    if (!params && aimpPlaybackIntent && explicitStudioTarget) {
      const commandPhrase = String(transcript || '')
        .replace(/\s+(?:на\s+)?(?:studio\s*pc|studio|студио|студия|студийный\s+пк)\s*$/i, '')
        .replace(/[.!?]+$/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      params = {
        action: 'windows_launch',
        deviceName: 'STUDIO PC',
        commandPhrase,
        commandAlternatives: [commandPhrase]
      };
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

    attachCommandCandidates();
    console.log('[RE:Vision] voice command routed', {
      transcript, commandCandidates: params.commandAlternatives || [],
      action: params.action, deviceName: params.deviceName || '', networkName: params.networkName || ''
    });
    const query = Object.keys(params)
      .filter((key) => params[key] !== undefined && params[key] !== '')
      .map((key) => encodeURIComponent(key) + '=' + encodeURIComponent(Array.isArray(params[key]) ? JSON.stringify(params[key]) : String(params[key])))
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
