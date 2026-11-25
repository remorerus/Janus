<script def>
{
  "navigationBarTitleText": "RE:Vision — настройки",
  "description": "Открывает локальные настройки PIN доступа к установленному приложению RE:Vision. Используй только когда пользователь явно просит настроить или заменить PIN.",
  "schema": {
    "data": {
      "type": "object",
      "properties": {}
    }
  }
}
</script>

<script setup>
const TOKEN_KEY = 'revision_bridge_token';

function validPin(value) {
  const pin = String(value == null ? '' : value).trim();
  return /^\d{4}$/.test(pin) ? pin : '';
}

function readWxPin() {
  try {
    if (typeof wx !== 'undefined' && typeof wx.getStorageSync === 'function') {
      return validPin(wx.getStorageSync(TOKEN_KEY));
    }
  } catch (e) {
    console.warn('RE:Vision wx storage read failed', e);
  }
  return '';
}

function readLocalPin() {
  try {
    if (typeof localStorage !== 'undefined') {
      return validPin(localStorage.getItem(TOKEN_KEY));
    }
  } catch (e) {
    console.warn('RE:Vision localStorage read failed', e);
  }
  return '';
}

// Write to both AIUI storage backends and verify each successful write.
// A single working backend is sufficient; the other acts as a recovery copy.
function savePinCopies(pin) {
  let wxSaved = false;
  let localSaved = false;

  try {
    if (typeof wx !== 'undefined' && typeof wx.setStorageSync === 'function') {
      wx.setStorageSync(TOKEN_KEY, pin);
      wxSaved = validPin(wx.getStorageSync(TOKEN_KEY)) === pin;
    }
  } catch (e) {
    console.warn('RE:Vision wx storage write failed', e);
  }

  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(TOKEN_KEY, pin);
      localSaved = validPin(localStorage.getItem(TOKEN_KEY)) === pin;
    }
  } catch (e) {
    console.warn('RE:Vision localStorage write failed', e);
  }

  if (!wxSaved && !localSaved) {
    throw new Error('AIUI не подтвердил сохранение PIN ни в одном локальном хранилище.');
  }
  return { wxSaved, localSaved };
}

function restorePin() {
  const pin = readLocalPin() || readWxPin();
  if (!pin) return '';
  try { savePinCopies(pin); } catch (e) {
    console.warn('RE:Vision PIN copy restoration failed', e);
  }
  return pin;
}

export default {
  data: {
    token: '',
    statusText: 'Введи четырёхзначный PIN, показанный в настройках приложения RE:Vision.'
  },

  onLoad() {
    const saved = restorePin();
    if (saved) {
      this.setData({ token: saved, statusText: 'PIN найден и восстановлен. Он сохранён в доступных локальных хранилищах AIUI.' });
    }
  },

  onTokenInput(event) {
    this.setData({ token: event.detail.value || '' });
  },

  pasteToken() {
    if (typeof wx === 'undefined' || typeof wx.getClipboardData !== 'function') {
      this.setData({ statusText: 'Чтение буфера обмена недоступно в этой версии AIUI. Вставь PIN вручную.' });
      return;
    }
    wx.getClipboardData({
      success: (result) => {
        const clipboardText = String(result && result.data != null ? result.data : '').trim();
        const pin = validPin(clipboardText);
        if (!pin) {
          this.setData({ statusText: 'В буфере нет корректного четырёхзначного PIN.' });
          return;
        }
        this.setData({ token: pin, statusText: 'Четырёхзначный PIN вставлен. Нажми «Сохранить PIN», чтобы сохранить его.' });
      },
      fail: (error) => {
        console.warn('RE:Vision clipboard read failed', error);
        this.setData({ statusText: 'Не удалось прочитать буфер обмена. Проверь разрешение AIUI или введи PIN вручную.' });
      }
    });
  },

  saveToken() {
    const token = validPin(this.data.token);
    if (!token) {
      this.setData({ statusText: 'Нужен ровно четырёхзначный PIN из приложения RE:Vision.' });
      return;
    }
    try {
      const result = savePinCopies(token);
      const stores = [result.wxSaved ? 'основное хранилище' : '', result.localSaved ? 'резервное хранилище' : '']
        .filter(Boolean).join(' и ');
      this.setData({ token, statusText: 'PIN сохранён и проверен: ' + stores + '. При следующем запуске агент попробует восстановить копию автоматически.' });
      try { wx.speech.playTTS('PIN доступа сохранён.'); } catch (e) {}
    } catch (error) {
      this.setData({ statusText: 'Не удалось надёжно сохранить PIN: ' + String(error.message || error) });
    }
  },

  clearToken() {
    try {
      if (typeof wx !== 'undefined' && typeof wx.removeStorageSync === 'function') {
        wx.removeStorageSync(TOKEN_KEY);
      }
    } catch (e) {}
    try {
      if (typeof localStorage !== 'undefined') localStorage.removeItem(TOKEN_KEY);
    } catch (e) {}
    this.setData({ token: '', statusText: 'PIN удалён из обоих локальных хранилищ AIUI.' });
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">RE:Vision</text>
    <text class="label">Четырёхзначный PIN локального командного моста</text>
    <input class="token" password="true" value="{{token}}" bindinput="onTokenInput" placeholder="Например, 0427" />
    <button bindtap="saveToken">Сохранить PIN</button>
    <button bindtap="clearToken">Удалить PIN</button>
    <button bindtap="pasteToken">Вставить из буфера обмена</button>
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
