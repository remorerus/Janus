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

  onKeyUp(event) {
    const code = String(event && event.code || '');
    console.log('[RE:Vision] temple key up', { code });
    // Do not intercept GlobalHook: Rokid must continue its native touch-wakeup flow.
  },

  onVoiceWakeup(event) {
    const keyword = String(event && event.keyword || '');
    console.log('[RE:Vision] voice/touch wakeup received; keeping Rokid default behavior', { keyword });
    // Do not call preventDefault() and do not start a second wx.speech session.
    // The host owns microphone capture, ASR results and intent delivery.
    this.setData({
      statusText: 'Касание принято. Ожидаю голосовую команду…',
      detailText: 'Штатное распознавание Rokid продолжает работу.'
    });
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
