export default {
  onLaunch(options) {
    console.log('[RE:Vision] app launched', options || {});
  },

  onShow(options) {
    console.log('[RE:Vision] app shown', options || {});
  },

  onHide() {
    console.log('[RE:Vision] app hidden');
  },

  onError(error) {
    console.error('[RE:Vision] AIUI runtime error', error);
  }
};
