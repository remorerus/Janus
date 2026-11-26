<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Стартовая страница голосового агента Ревижн. Использует штатный голосовой канал Rokid AIUI. При событии voice wakeup не блокирует стандартное распознавание, не запускает отдельный SpeechRecognition и не закрывает агента по таймеру. Распознанное AIUI намерение обрабатывается страницей device-action."
}
</script>

<script setup>
export default {
  data: {
    title: 'Ревижн',
    statusText: 'Ревижн готов. Ожидаю голосовую команду.',
    detailText: 'Вызови агента штатным способом Rokid и произнеси команду.'
  },

  onLoad(query) {
    const keys = query && typeof query === 'object' ? Object.keys(query) : [];
    console.log('[RE:Vision] startup page loaded', { queryKeys: keys });
    this.setData({
      statusText: 'Ревижн активен. Готов слушать.',
      detailText: 'Голосовой ввод выполняет штатный сервис Rokid AIUI.'
    });
  },

  onShow() {
    this.setData({
      statusText: 'Ревижн готов. Ожидаю голосовую команду.',
      detailText: 'Распознавание выполняет штатный голосовой канал Rokid AIUI.'
    });
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] native AIUI voice wakeup received', { keyword });
    this.setData({
      statusText: 'Слушаю…',
      detailText: keyword
        ? 'Вызов принят: ' + keyword + '. Говори команду.'
        : 'Голосовой вызов принят. Говори команду.'
    });
    // Deliberately do not call preventDefault(): Rokid must continue its
    // native voice-input and intent-recognition flow for this agent.
  },

  onKeyUp(event) {
    if (event && event.code === 'GlobalHook') {
      console.log('[RE:Vision] temple touch event received');
      this.setData({
        statusText: 'Вызов с сенсора принят.',
        detailText: 'Ожидаю голосовой ввод штатного канала Rokid AIUI.'
      });
      // Do not prevent the host's default voice interaction.
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
