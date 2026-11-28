<script def>
{
  "navigationBarTitleText": "Тест связи",
  "description": "Проверка постоянного WebSocket-канала между AIUI и Android Foreground Service на том же устройстве."
}
</script>

<script setup>
export default {
  data: {
    state: 'Подключение не запущено',
    detail: 'Запусти RE:Vision Bridge Lab на очках, затем подключи канал.'
  },
  socket: null,
  heartbeatTimer: null,
  reconnectTimer: null,
  disposed: false,

  onShow() {
    this.disposed = false;
    this.connectLoopback();
  },

  connectLoopback() {
    if (this.socket && this.socket.readyState === 1) {
      this.setData({ state: 'WebSocket подключён', detail: 'Жду контрольный ответ от Foreground Service.' });
      return;
    }
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.setData({ state: 'Подключение…', detail: 'Пробую ws://127.0.0.1:38763' });
    try {
      const ws = new WebSocket('ws://127.0.0.1:38763/bridge?token=revision-lab-only');
      this.socket = ws;
      ws.onopen = () => {
        this.setData({ state: 'КАНАЛ ОТКРЫТ', detail: 'Отправляю heartbeat каждые 15 секунд.' });
        ws.send('AIUI_CONNECTED');
        if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
        this.heartbeatTimer = setInterval(() => {
          if (this.socket === ws && ws.readyState === 1) ws.send('AIUI_HEARTBEAT');
        }, 15000);
      };
      ws.onmessage = (event) => {
        const payload = String(event.data);
        if (payload.indexOf('AIUI_HEARTBEAT') >= 0) {
          this.setData({ state: 'СВЯЗЬ СТАБИЛЬНА', detail: 'Foreground Service отвечает на heartbeat. ' + payload });
        } else {
          this.setData({ state: 'AIUI СВЯЗАН С ANDROID SERVICE', detail: payload });
        }
      };
      ws.onerror = () => {
        this.setData({ state: 'ОШИБКА WebSocket', detail: 'Проверяю соединение и попробую переподключиться.' });
      };
      ws.onclose = (event) => {
        if (this.socket === ws) this.socket = null;
        if (this.heartbeatTimer) {
          clearInterval(this.heartbeatTimer);
          this.heartbeatTimer = null;
        }
        if (!this.disposed) {
          this.setData({ state: 'КАНАЛ ЗАКРЫТ — ПЕРЕПОДКЛЮЧЕНИЕ', detail: 'Код: ' + event.code + '. Повтор через 2 секунды.' });
          this.reconnectTimer = setTimeout(() => this.connectLoopback(), 2000);
        }
      };
    } catch (error) {
      this.setData({ state: 'ОШИБКА', detail: String(error) });
      if (!this.disposed) this.reconnectTimer = setTimeout(() => this.connectLoopback(), 2000);
    }
  },

  onUnload() {
    this.disposed = true;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.heartbeatTimer = null;
    this.reconnectTimer = null;
    if (this.socket) {
      try { this.socket.close(); } catch (e) {}
      this.socket = null;
    }
  }
};
</script>

<page>
  <view class="panel">
    <text class="heading">RE:Vision — лаборатория связи</text>
    <text class="status">{{state}}</text>
    <text class="detail">{{detail}}</text>
    <button bindtap="connectLoopback">Подключить / переподключить</button>
  </view>
</page>

<style>
.panel { display: flex; flex-direction: column; gap: 14px; padding: 18px; }
.heading { font-size: 20px; font-weight: 700; }
.status { font-size: 18px; }
.detail { font-size: 14px; line-height: 1.4; }
</style>
