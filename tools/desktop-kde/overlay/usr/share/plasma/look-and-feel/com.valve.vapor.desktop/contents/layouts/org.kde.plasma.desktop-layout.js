// The first desktop: DroidDeck's wallpaper, and SteamOS's Plasma's default bottom panel with the Steam Deck
// launcher icon, Steam, the file manager, the browser and Konsole pinned to the task manager.
var desktops = desktopsForActivity(currentActivity());
for (var i = 0; i < desktops.length; i++) {
    desktops[i].wallpaperPlugin = "org.kde.image";
    desktops[i].currentConfigGroup = ["Wallpaper", "org.kde.image", "General"];
    desktops[i].writeConfig("Image", "file:///usr/share/wallpapers/DroidDeck");
}

var panel = new Panel;
panel.location = "bottom";
panel.height = 2 * Math.ceil(gridUnit * 2.5 / 2);

var kickoff = panel.addWidget("org.kde.plasma.kickoff");
kickoff.currentConfigGroup = ["Shortcuts"];
kickoff.writeConfig("global", "Alt+F1");
kickoff.currentConfigGroup = ["General"];
kickoff.writeConfig("icon", "distributor-logo-steamdeck");

panel.addWidget("org.kde.plasma.pager");

var tasks = panel.addWidget("org.kde.plasma.icontasks");
tasks.currentConfigGroup = ["General"];
tasks.writeConfig("launchers", [
    "applications:steamdeck-steam.desktop",
    "preferred://filemanager",
    "preferred://browser",
    "applications:org.kde.konsole.desktop",
]);

panel.addWidget("org.kde.plasma.marginsseparator");
var tray = panel.addWidget("org.kde.plasma.systemtray");
var trayId = tray.readConfig("SystrayContainmentId");
if (trayId) {
    var trayContainment = desktopById(trayId);
    trayContainment.currentConfigGroup = ["General"];
    trayContainment.writeConfig("scaleIconsToFit", true);
}
// The runtime has no locale of its own (C.UTF-8), whose short date reads "9 10 2026".
var clock = panel.addWidget("org.kde.plasma.digitalclock");
clock.currentConfigGroup = ["Appearance"];
clock.writeConfig("dateFormat", "isoDate");
panel.addWidget("org.kde.plasma.showdesktop");
