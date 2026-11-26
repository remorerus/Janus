<script def>
{
  "navigationBarTitleText": "Ревижн",
  "description": "Стартовая страница Ревижн. Распознавание речи и выбор намерения выполняются штатным голосовым каналом Rokid AIUI. Не запускать SpeechRecognition из JavaScript."
}
</script>

<script setup>
export default {
  data: {
    title: 'Ревижн',
    statusText: 'Ревижн готов.',
    detailText: 'Произнеси действие, затем имя устройства из списка Ревижн.'
  },

  onLoad() {
    console.log('[RE:Vision] стартовая страница загружена; используется штатное распознавание Rokid AIUI');
    this.setData({
      statusText: 'Ревижн готов.',
      detailText: 'Например: «Пауза AIMP, студио локал».'
    });
  },

  onShow() {
    this.setData({
      statusText: 'Ревижн готов.',
      detailText: 'Ожидаю команду через голосовой канал Rokid AIUI.'
    });
  },

  // Не перехватываем событие пробуждения. AIUI должен сам завершить
  // штатное распознавание и вызвать страницу device-action с intent-параметрами.
  onVoiceWakeup(event) {
    console.log('[RE:Vision] штатное голосовое пробуждение AIUI', {
      keyword: String(event && event.keyword || '')
    });
  },

  onUnload() {
    console.log('[RE:Vision] стартовая страница выгружена');
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
