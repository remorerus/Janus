<script def>
{
  "widget": {
    "family": "1x2"
  }
}
</script>

<script setup>
const BRIDGE = 'http://127.0.0.1:38761';
const REFRESH_MS = 30000;

export default {
  data: {
    title: 'Ревижн',
    status: 'Проверяю связь…',
    detail: 'Voice Bridge',
    online: false,
    indicatorClass: 'offline',
    updated: 'Автопроверка каждые 30 сек.'
  },

  refreshStatus() {
    if (typeof wx === 'undefined' || typeof wx.request !== 'function') {
      this.setData({
        status: 'Нет доступа к проверке',
        detail: 'AIUI runtime не предоставил сетевой запрос',
        online: false,
        indicatorClass: 'offline'
      });
      return;
    }

    const ports = [38761, 38762];
    let index = 0;
    const tryNext = () => {
      if (index >= ports.length) {
        this.setData({
          status: 'Связь потеряна',
          detail: 'Не отвечает ни основной Voice Bridge, ни канал восстановления',
          online: false,
          indicatorClass: 'offline',
          updated: 'Последняя проверка: ' + new Date().toLocaleTimeString()
        });
        return;
      }
      const port = ports[index++];
      wx.request({
        url: 'http://127.0.0.1:' + port + '/health',
        method: 'GET',
        dataType: 'json',
        timeout: 5000,
        success: (res) => {
          const body = res && res.data;
          if (!(res && Number(res.statusCode) >= 200 && Number(res.statusCode) < 300
              && body && body.ok === true)) {
            tryNext();
            return;
          }
          const now = new Date();
          const mainRunning = body.mainServerRunning === true;
          this.setData({
            status: mainRunning ? 'Основной канал работает' : 'Доступен канал восстановления',
            detail: mainRunning
              ? 'Voice Bridge доступен · порт 38761'
              : 'Основная служба не подключена. Можно открыть системные настройки · порт 38762',
            online: mainRunning,
            indicatorClass: mainRunning ? 'online' : 'offline',
            updated: 'Проверено: ' + now.toLocaleTimeString()
          });
        },
        fail: () => tryNext()
      });
    };
    tryNext();
  },

  onCreate() {
    this.refreshStatus();
  },

  onAttach() {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    this.refreshStatus();
    this.refreshTimer = setInterval(() => this.refreshStatus(), REFRESH_MS);
  },

  onDetach() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  },

  onDestroy() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }
};
</script>

<widget>
  <view class="panel">
    <view class="top">
      <text class="title">{{title}}</text>
      <view class="indicator {{indicatorClass}}"></view>
    </view>
    <text class="status">{{status}}</text>
    <text class="detail">{{detail}}</text>
    <view class="bottom">
      <text class="updated">{{updated}}</text>
      <text class="refresh" bindtap="refreshStatus">Обновить</text>
    </view>
  </view>
</widget>

<style>
.panel {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  width: 100%;
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
  border: 2px solid #40ff5e;
  border-radius: 12px;
  color: #ffffff;
}
.top, .bottom {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-size: 17px;
  font-weight: 700;
}
.indicator {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.online { background-color: #40ff5e; }
.offline { background-color: #ff5a5a; }
.status {
  font-size: 18px;
  font-weight: 700;
}
.detail, .updated {
  font-size: 12px;
  opacity: 0.85;
}
.refresh {
  font-size: 13px;
  color: #40ff5e;
}
</style>
