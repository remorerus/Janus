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
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] voice wakeup received', { keyword });
    // Match Kavi's verified two-entry-point pattern: after the agent is
    // foregrounded, listen locally without cancelling Rokid's host flow.
    this.startListening();
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
    if (/(voice\s*bridge|войс\s*бридж|голосов\w* мост|настройк\w* ревижн)/i.test(compact)) {
      params = { action: 'bridge_settings' };
    } else if (hasWifi && /(выключи|выключить|отключи|отключить|выруби)/i.test(compact)) {
      params = { action: 'wifi_disable' };
    } else if (hasWifi && /(включи|включить|активируй)/i.test(compact)) {
      params = { action: 'wifi_enable' };
    } else if (hasWifi && /(открой|открыть|настройк)/i.test(compact)) {
      params = { action: 'wifi' };
    } else if (hasWifi && /(список|покажи|доступн|видим)/i.test(compact)) {
      params = { action: 'wifi_list' };
    } else if (hasWifi && /(какая сеть|какую сеть|проверь|статус|состояни)/i.test(compact)) {
      params = { action: 'wifi_status' };
    } else if (/(подключись|переключись|соединись)/i.test(compact) &&
               /(сеть|wi\s*fi|вай\s*фай|вайфай|локал\s*нетворк|local\s*network|студио|studio)/i.test(compact)) {
      let networkName = '';
      if (/(локал\s*нетворк|local\s*network)/i.test(compact)) networkName = 'Local_Network_5G';
      else if (/(студио|studio)/i.test(compact)) networkName = 'Studio';
      params = { action: 'wifi_connect', networkName };
    } else if (/(список устройств|покажи устройства|какие устройства|мои устройства)/i.test(compact)) {
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

    if (!params) {
      this.setData({
        statusText: 'Команду не удалось определить.',
        detailText: 'Распознано: «' + transcript + '». Повтори команду или используй штатный голосовой канал Rokid.'
      });
      return;
    }

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
