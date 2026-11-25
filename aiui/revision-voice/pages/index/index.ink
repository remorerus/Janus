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
      statusText: keyword === 'clickAiAssist'
        ? 'Касание получено. Ожидаю голосовой ввод Rokid.'
        : 'Голосовое пробуждение получено. Слушаю.',
      detailText: keyword ? 'Источник: ' + keyword + '. Штатный голосовой канал Rokid не перехватывается.' : 'Штатный голосовой канал Rokid не перехватывается.'
    });
    // Intentionally do not call preventDefault(): Rokid must open its native
    // voice-input flow after either a wake word or a temple tap.
  },

  onKeyUp(event) {
    // Some Rokid firmware versions expose the temple tap as GlobalHook instead
    // of clickAiAssist. Observe it as a compatibility fallback without blocking
    // the host's normal voice interaction.
    if (event && event.code === 'GlobalHook') {
      console.log('[RE:Vision] temple tap received through GlobalHook');
      this.setData({
        statusText: 'Касание получено. Ожидаю голосовой ввод Rokid.',
        detailText: 'Событие GlobalHook принято. Штатный голосовой канал не перехватывается.'
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
