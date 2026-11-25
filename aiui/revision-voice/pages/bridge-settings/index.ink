<script def>
{
  "navigationBarTitleText": "Ревижн — Voice Bridge",
  "description": "Панель диагностики Voice Bridge. Автоматически проверяет состояние основного и аварийного локальных каналов. Системные настройки специальных возможностей открываются только после отдельного нажатия пользователя."
}
</script>

<script setup>
function speak(text) {
  try {
    if (typeof wx !== 'undefined' && wx.speech && typeof wx.speech.playTTS === 'function') {
      wx.speech.playTTS(text);
    }
  } catch (e) {}
}

function bridgeRequest(port, path, method) {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || typeof wx.request !== 'function') {
      reject(new Error('wx.request недоступен в AIUI runtime'));
      return;
    }
    wx.request({
      url: 'http://127.0.0.1:' + port + path,
      method: method || 'GET',
      header: { 'content-type': 'application/json' },
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
    statusText: 'Проверяю состояние Voice Bridge…',
    detailText: 'Проверка не открывает системные настройки.',
    busy: false,
    mainStatus: 'Проверка основного канала…',
    recoveryStatus: 'Проверка аварийного канала…'
  },

  onLoad() {
    // Important: opening this page must never launch Android Settings.
    this.checkStatus();
  },

  async checkStatus() {
    if (this.data.busy) return;
    this.setData({
      busy: true,
      statusText: 'Проверяю локальные каналы…',
      mainStatus: 'Проверяю 127.0.0.1:38761…',
      recoveryStatus: 'Проверяю 127.0.0.1:38762…'
    });

    let main = null;
    let recovery = null;
    try { main = await bridgeRequest(38761, '/health', 'GET'); } catch (e) {}
    try { recovery = await bridgeRequest(38762, '/health', 'GET'); } catch (e) {}

    const health = main || recovery;
    if (!health) {
      this.setData({
        busy: false,
        statusText: 'Voice Bridge недоступен.',
        mainStatus: 'Основной канал 38761: НЕТ ОТВЕТА',
        recoveryStatus: 'Аварийный канал 38762: НЕТ ОТВЕТА',
        detailText: 'Открой приложение RE:Vision на телефоне. Проверь, включена ли служба RE:Vision в системных специальных возможностях Android. Настройки автоматически не открываются.'
      });
      return;
    }

    const enabled = health.accessibilityEnabled === true;
    const connected = health.accessibilityConnected === true;
    const mainRunning = main ? main.mainServerRunning === true : health.mainServerRunning === true;
    const recoveryRunning = recovery ? recovery.recoveryServerRunning === true : false;

    const mainText = mainRunning
      ? 'Основной канал 38761: РАБОТАЕТ'
      : 'Основной канал 38761: НЕ ЗАПУЩЕН';
    const recoveryText = recoveryRunning
      ? 'Аварийный канал 38762: РАБОТАЕТ'
      : 'Аварийный канал 38762: НЕТ ОТВЕТА';

    let message;
    if (mainRunning && enabled && connected) {
      message = 'Voice Bridge работает. Основной канал активен.';
    } else if (!enabled) {
      message = 'Служба специальных возможностей RE:Vision выключена.';
    } else if (!connected || !mainRunning) {
      message = 'Служба включена, но основной Voice Bridge не подключён.';
    } else {
      message = 'Состояние Voice Bridge требует проверки.';
    }

    this.setData({
      busy: false,
      statusText: message,
      mainStatus: mainText,
      recoveryStatus: recoveryText,
      detailText: 'Accessibility: ' + (enabled ? 'включена' : 'выключена')
        + '. Подключение службы: ' + (connected ? 'есть' : 'нет')
        + '. Аварийный канал нужен только для диагностики и восстановления.'
    });
  },

  async openAccessibilitySettings() {
    if (this.data.busy) return;
    this.setData({ busy: true, statusText: 'Открываю системные настройки по запросу…' });
    let lastError = null;
    for (const port of [38762, 38761]) {
      try {
        // The explicit confirmation flag is also enforced by the Android endpoints.
        await bridgeRequest(port, '/accessibility/settings?userInitiated=1', 'POST');
        this.setData({
          busy: false,
          statusText: 'Системные настройки открыты.',
          detailText: 'Найди RE:Vision в списке служб специальных возможностей и включи переключатель вручную.'
        });
        speak('Открыл настройки специальных возможностей. Включи службу Ревижн.');
        return;
      } catch (error) {
        lastError = error;
      }
    }
    this.setData({
      busy: false,
      statusText: 'Не удалось открыть системные настройки.',
      detailText: String(lastError && lastError.message || 'Оба локальных канала недоступны.')
    });
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">{{title}}</text>
    <text class="status">{{statusText}}</text>
    <text class="detail">{{mainStatus}}</text>
    <text class="detail">{{recoveryStatus}}</text>
    <text class="detail">{{detailText}}</text>
    <button bindtap="checkStatus" disabled="{{busy}}">Проверить Voice Bridge</button>
    <button bindtap="openAccessibilitySettings" disabled="{{busy}}">Открыть специальные возможности</button>
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
.detail { font-size: 14px; line-height: 1.35; opacity: 0.9; }
button { border: 2px solid #40ff5e; color: #40ff5e; }
</style>
