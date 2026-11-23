<script def>
{
  "navigationBarTitleText": "Ревижн — Voice Bridge",
  "description": "Панель восстановления Voice Bridge. Проверяет основной и аварийный локальные каналы и открывает системные настройки специальных возможностей Android, где пользователь может включить RE:Vision."
}
</script>

<script setup>
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

function bridgeRequest(port, path, method, token) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: 'http://127.0.0.1:' + port + path,
      method: method || 'GET',
      header: token ? { 'content-type': 'application/json', 'X-Revision-Token': token } : { 'content-type': 'application/json' },
      data: method === 'POST' ? {} : undefined,
      timeout: 5000,
      dataType: 'json',
      success: res => {
        const body = res && res.data;
        if (Number(res.statusCode) >= 200 && Number(res.statusCode) < 300 && body && body.ok === true) {
          resolve(body);
        } else {
          const error = new Error(body && body.error ? body.error : 'HTTP ' + String(res && res.statusCode || 0));
          error.statusCode = Number(res && res.statusCode || 0);
          reject(error);
        }
      },
      fail: err => reject(new Error(err && err.errMsg ? err.errMsg : 'Локальный канал недоступен'))
    });
  });
}

export default {
  data: {
    title: 'Voice Bridge',
    statusText: 'Проверяю основной и аварийный каналы…',
    detailText: 'Аварийный канал не зависит от службы специальных возможностей.',
    busy: false
  },

  onLoad() {
    this.checkAndOpen();
  },

  async checkAndOpen() {
    if (this.data.busy) return;
    this.setData({ busy: true, statusText: 'Проверяю каналы RE:Vision…' });
    const token = readToken();
    if (!token) {
      const message = 'PIN агента не настроен.';
      this.setData({
        busy: false,
        statusText: message,
        detailText: 'Открой «Настройки ключа» агента и введи четырёхзначный PIN из приложения RE:Vision.'
      });
      speak(message);
      return;
    }

    let lastError = null;
    for (const port of [38761, 38762]) {
      try {
        const health = await bridgeRequest(port, '/health', 'GET', '');
        const opened = await bridgeRequest(port, '/accessibility/settings', 'POST', token);
        const wasEnabled = health.accessibilityEnabled !== false && opened.accessibilityEnabled !== false;
        const message = wasEnabled
          ? 'Системное меню специальных возможностей открыто. Проверь службу RE:Vision.'
          : 'Служба RE:Vision выключена. Я открыл системное меню для её включения.';
        this.setData({
          busy: false,
          statusText: message,
          detailText: 'Использован локальный канал ' + String(port) + '. Найди RE:Vision и включи переключатель, если он выключен.'
        });
        speak(message);
        return;
      } catch (error) {
        lastError = error;
        if (error && error.statusCode === 401) break;
      }
    }

    const unauthorized = lastError && lastError.statusCode === 401;
    const message = unauthorized
      ? 'PIN не совпадает с ключом приложения RE:Vision.'
      : 'Оба локальных канала пока недоступны.';
    this.setData({
      busy: false,
      statusText: message,
      detailText: unauthorized
        ? 'Сверь PIN в настройках агента с PIN в приложении RE:Vision.'
        : 'Если это первое включение RE:Vision, открой приложение вручную → Настройки → Voice Bridge. После первого включения аварийный канал будет запускаться при загрузке даже если прошивка сбросит системный переключатель.'
    });
    speak(unauthorized
      ? 'Проверь PIN доступа.'
      : 'Локальные каналы недоступны. При первом запуске открой Ревижн вручную и включи службу Voice Bridge.');
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
