<script def>
{
  "navigationBarTitleText": "RE:Vision — настройки",
  "description": "Страница локальной настройки ключа доступа к установленному приложению RE:Vision. Не вызывай автоматически при обычных командах управления."
}
</script>

<script setup>
export default {
  data: {
    token: '',
    statusText: 'Введи ключ, который будет показан в настройках приложения RE:Vision.'
  },

  onLoad() {
    const saved = wx.getStorageSync('revision_bridge_token');
    if (saved) {
      this.setData({ token: saved, statusText: 'Ключ уже сохранён на этом устройстве.' });
    }
  },

  onTokenInput(event) {
    this.setData({ token: event.detail.value || '' });
  },

  saveToken() {
    const token = String(this.data.token || '').trim();
    if (!token) {
      this.setData({ statusText: 'Ключ пустой. Вставь ключ из RE:Vision.' });
      return;
    }
    wx.setStorageSync('revision_bridge_token', token);
    this.setData({ statusText: 'Ключ сохранён локально в AIUI.' });
    try {
      wx.speech.playTTS('Ключ доступа сохранён.');
    } catch (e) {}
  },

  clearToken() {
    wx.removeStorageSync('revision_bridge_token');
    this.setData({ token: '', statusText: 'Ключ удалён из локального хранилища AIUI.' });
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">RE:Vision</text>
    <text class="label">Ключ локального командного моста</text>
    <input class="token" password="true" value="{{token}}" bindinput="onTokenInput" placeholder="Вставь ключ доступа" />
    <button bindtap="saveToken">Сохранить ключ</button>
    <button bindtap="clearToken">Удалить ключ</button>
    <text class="status">{{statusText}}</text>
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
.heading { font-size: 22px; font-weight: 700; }
.label, .status { font-size: 16px; }
.token {
  min-height: 42px;
  border: 2px solid #40ff5e;
  border-radius: 8px;
  padding: 8px;
  color: #40ff5e;
}
button { border: 2px solid #40ff5e; color: #40ff5e; }
</style>
