<script def>
{
  "navigationBarTitleText": "Тест связи",
  "description": "Проверка доступа AIUI к локальному WebSocket Foreground Service на том же устройстве."
}
</script>

<script setup>
export default {
  data: {
    state: 'Ожидание проверки',
    detail: 'Запусти RE:Vision Bridge Lab на очках и нажми «Проверить связь».'
  },
  socket: null,

  onShow() {
    this.setData({
      state: 'AIUI готов к тесту',
      detail: 'Локальный адрес: 127.0.0.1:38763'
    });
  },

  testConnection() {
    this.setData({ state: 'Подключение…', detail: 'Пробую ws://127.0.0.1:38763' });
    try {
      const ws = new WebSocket('ws://127.0.0.1:38763/bridge?token=revision-lab-only');
      this.socket = ws;
      ws.onopen = () => {
        this.setData({ state: 'WebSocket открыт', detail: 'Отправляю контрольную команду из AIUI…' });
        ws.send('AIUI_LOOPBACK_TEST');
      };
      ws.onmessage = (event) => {
        this.setData({ state: 'УСПЕХ: AIUI связан с Android Service', detail: String(event.data) });
        ws.close();
      };
      ws.onerror = () => {
        this.setData({ state: 'ОШИБКА WebSocket', detail: 'AIUI не смог подключиться к 127.0.0.1:38763.' });
      };
      ws.onclose = (event) => {
        console.log('[BridgeLab] socket closed', event.code, event.reason);
      };
    } catch (error) {
      this.setData({ state: 'ОШИБКА', detail: String(error) });
    }
  },

  onUnload() {
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
    <button bindtap="testConnection">Проверить связь</button>
  </view>
</page>

<style>
.panel { display: flex; flex-direction: column; gap: 14px; padding: 18px; }
.heading { font-size: 20px; font-weight: 700; }
.status { font-size: 18px; }
.detail { font-size: 14px; line-height: 1.4; }
</style>
