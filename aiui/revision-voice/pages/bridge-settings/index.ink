<script def>
{
  "navigationBarTitleText": "Ревижн — Voice Bridge",
  "description": "Панель восстановления Voice Bridge. Проверяет доступность локального моста и по запросу открывает системные настройки специальных возможностей Android, где пользователь может включить RE:Vision."
}
</script>

<script setup>
const BRIDGE = 'http://127.0.0.1:38761';
const TOKEN_KEY = 'revision_bridge_token';

function readToken() {
  let token = '';
  try {
    if (typeof wx !== 'undefined' && typeof wx.getStorageSync === 'function') {
      token = String(wx.getStorageSync(TOKEN_KEY) || '').trim();
    }
  } catch (e) {}
  if (!/^\d{4}$/.test(token)) {
    try {
      token = typeof localStorage !== 'undefined'
        ? String(localStorage.getItem(TOKEN_KEY) || '').trim() : '';
    } catch (e) {}
  }
  return /^\d{4}$/.test(token) ? token : '';
}

function speak(text) {
  try {
    if (typeof wx !== 'undefined' && wx.speech && typeof wx.speech.playTTS === 'function') {
      wx.speech.playTTS(text);
    }
  } catch (e) {}
}

export default {
  data: {
    title: 'Voice Bridge',
    statusText: 'Проверяю состояние службы…',
    detailText: 'Системный переключатель остаётся под контролем Android.',
    busy: false,
    canOpen: false
  },

  async onLoad() {
    await this.checkAndOpen();
  },

  async checkAndOpen() {
    if (this.data.busy) return;
    this.setData({ busy: true, statusText: 'Проверяю локальный Voice Bridge…' });
    try {
      const health = await new Promise((resolve, reject) => {
        wx.request({
          url: BRIDGE + '/health',
          method: 'GET',
          timeout: 5000,
          dataType: 'json',
          success: res => {
            if (Number(res.statusCode) >= 200 && Number(res.statusCode) < 300 && res.data && res.data.ok) resolve(res.data);
            else reject(new Error('HTTP ' + String(res.statusCode || 0)));
          },
          fail: err => reject(new Error(err && err.errMsg ? err.errMsg : 'Нет ответа от моста'))
        });
      });
      const token = readToken();
      if (!token) {
        this.setData({
          busy: false,
          canOpen: false,
          statusText: 'Мост работает, но PIN агента не настроен.',
          detailText: 'Открой «Настройки ключа» в агенте и введи PIN из приложения RE:Vision.'
        });
        speak('Мост работает. Сначала настрой PIN доступа.');
        return;
      }
      const opened = await new Promise((resolve, reject) => {
        wx.request({
          url: BRIDGE + '/accessibility/settings',
          method: 'POST',
          header: { 'content-type': 'application/json', 'X-Revision-Token': token },
          data: {},
          timeout: 7000,
          dataType: 'json',
          success: res => {
            if (Number(res.statusCode) >= 200 && Number(res.statusCode) < 300 && res.data && res.data.ok) resolve(res.data);
            else reject(new Error(res.data && res.data.error ? res.data.error : 'HTTP ' + String(res.statusCode || 0)));
          },
          fail: err => reject(new Error(err && err.errMsg ? err.errMsg : 'Не удалось открыть настройки'))
        });
      });
      this.setData({
        busy: false,
        canOpen: true,
        statusText: 'Системное меню специальных возможностей открыто.',
        detailText: 'Найди RE:Vision и включи его переключатель, если он выключен.'
      });
      speak('Открыл системное меню специальных возможностей. Найди Ревижн и проверь переключатель.');
    } catch (error) {
      this.setData({
        busy: false,
        canOpen: false,
        statusText: 'Voice Bridge сейчас недоступен.',
        detailText: 'Служба специальных возможностей RE:Vision выключена или ещё не запустилась. Открой приложение RE:Vision → Настройки → Voice Bridge. Затем включи RE:Vision в системном меню специальных возможностей Android. Не удаляй приложение и не очищай его данные.'
      });
      speak('Мост сейчас недоступен. Открой приложение Ревижн, настройки, Voice Bridge, затем включи службу Ревижн в настройках специальных возможностей Android.');
    }
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">{{title}}</text>
    <text class="status">{{statusText}}</text>
    <text class="detail">{{detailText}}</text>
    <button bindtap="checkAndOpen" disabled="{{busy}}">Повторить проверку и открыть меню</button>
    <text class="footer" ink:if="{{busy}}">Проверка выполняется…</text>
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
.status { font-size: 18px; line-height: 1.35; }
.detail, .footer { font-size: 14px; opacity: 0.9; }
button { border: 2px solid #40ff5e; color: #40ff5e; }
</style>
