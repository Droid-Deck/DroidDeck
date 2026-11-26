const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('droiddeck', {
  getLibrary: () => ipcRenderer.invoke('library:get'),
  refreshLibrary: () => ipcRenderer.invoke('library:refresh'),
  importLibrary: () => ipcRenderer.invoke('library:import'),
  addGame: (game) => ipcRenderer.invoke('library:add', game),
  removeGame: (key) => ipcRenderer.invoke('library:remove', key),
  launchGame: (key) => ipcRenderer.invoke('library:launch', key),
  chooseLaunchTarget: () => ipcRenderer.invoke('library:choose-target'),
});
