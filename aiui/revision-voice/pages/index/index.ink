<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Стартовая страница голосового агента Ревижн. При запуске без параметров показывает готовность агента. При голосовом или сенсорном пробуждении принимает событие Rokid AIUI и не блокирует стандартную обработку голосового взаимодействия."
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

  lastListenStartAt: 0,

  startListening(source, event) {
    const now = Date.now();
    // Rokid may report one temple tap as both GlobalHook and clickAiAssist.
    // Start only one recognition session for that physical tap.
    if (now - this.lastListenStartAt < 1200) return;
    this.lastListenStartAt = now;

    try {
      if (event && typeof event.preventDefault === 'function') {
        event.preventDefault();
      }
      if (!wx || !wx.speech || typeof wx.speech.startRecognition !== 'function') {
        throw new Error('wx.speech.startRecognition is unavailable');
      }
      const sessionId = wx.speech.startRecognition();
      console.log('[RE:Vision] native speech recognition started', { source, sessionId });
      this.setData({
        statusText: 'Слушаю…',
        detailText: 'Говори команду. Используется штатное распознавание Rokid.'
      });
    } catch (error) {
      console.error('[RE:Vision] could not start speech recognition', error);
      this.setData({
        statusText: 'Не удалось включить прослушивание.',
        detailText: String(error && error.message || error)
      });
    }
  },

  onKeyUp(event) {
    const code = String(event && event.code || '');
    console.log('[RE:Vision] temple key up', { code });
    if (code === 'GlobalHook') {
      this.startListening('GlobalHook', event);
    }
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] voice/touch wakeup received', { keyword });
    this.startListening(keyword || 'voicewakeup', event);
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
