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

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] voice wakeup received', { keyword });
    this.setData({
      statusText: 'Запускаю распознавание команды…',
      detailText: keyword ? 'Событие Rokid: ' + keyword : 'Получено голосовое пробуждение Rokid.'
    });
    // Do not cancel the host event: preserve the native AI Assistant flow.
    // The guard below merges clickAiAssist + GlobalHook when firmware emits both.
    this.startAgentRecognition('onVoiceWakeup:' + keyword);
  },

  onKeyUp(event) {
    if (event && event.code === 'GlobalHook') {
      console.log('[RE:Vision] temple touch received through GlobalHook');
      this.startAgentRecognition('GlobalHook');
    }
  },

  startAgentRecognition(source) {
    const now = Date.now();
    const lastStart = Number(this._lastRecognitionStartAt || 0);
    if (now - lastStart < 1800) {
      console.log('[RE:Vision] duplicate recognition trigger ignored', { source });
      return;
    }

    if (typeof wx === 'undefined' || !wx.speech ||
        typeof wx.speech.startRecognition !== 'function') {
      const message = 'Голосовое распознавание Rokid недоступно в этой версии AIUI.';
      console.error('[RE:Vision] wx.speech.startRecognition unavailable');
      this.setData({ statusText: message, detailText: 'Проверь версию AIUI runtime и разрешение микрофона.' });
      return;
    }

    if (typeof wx.canIUse === 'function' && !wx.canIUse('speech.startRecognition')) {
      const message = 'AIUI не поддерживает запуск голосового распознавания.';
      console.error('[RE:Vision] speech.startRecognition is not registered');
      this.setData({ statusText: message, detailText: 'Распознавание не запущено; штатная обработка Rokid не блокировалась.' });
      return;
    }

    this._lastRecognitionStartAt = now;
    try {
      const sessionId = wx.speech.startRecognition();
      this._recognitionSessionId = sessionId || '';
      console.log('[RE:Vision] native speech recognition started', { source, sessionId: sessionId || null });
      this.setData({
        statusText: 'Слушаю команду…',
        detailText: 'Говори команду. После распознавания Ревижн передаст её на выполнение.'
      });
    } catch (error) {
      this._lastRecognitionStartAt = 0;
      console.error('[RE:Vision] native speech recognition failed to start', error);
      this.setData({
        statusText: 'Не удалось запустить голосовой ввод.',
        detailText: String(error && error.message || error)
      });
    }
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
