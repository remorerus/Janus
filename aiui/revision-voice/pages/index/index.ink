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
    if (keyword === 'clickAiAssist') {
      // Keep Rokid's normal voice interaction active after the user taps RE:Vision.
      // Do not cancel the AI Shortcut event: the tap must open the agent and leave
      // the standard speech flow available for the next spoken command.
      this.setData({
        statusText: 'Ревижн вызван касанием.',
        detailText: 'Голосовой канал Rokid активен. Готов принять команду.'
      });
      return;
    }
    this.setData({
      statusText: 'Ревижн активен. Готов принять команду.',
      detailText: keyword ? 'Источник пробуждения: ' + keyword : 'Получено событие голосового пробуждения.'
    });
    // Do not call preventDefault(): Rokid must retain its normal voice interaction flow.
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
