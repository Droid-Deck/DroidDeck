var desktops = desktopsForActivity(currentActivity());
for (var i = 0; i < desktops.length; i++) {
    desktops[i].wallpaperPlugin = "org.kde.image";
    desktops[i].currentConfigGroup = ["Wallpaper", "org.kde.image", "General"];
    desktops[i].writeConfig("Image", "file:///usr/share/wallpapers/DroidDeck");
}

var dock = new Panel;
dock.location = "bottom";
dock.height = 2 * Math.ceil(gridUnit * 2.5 / 2);

var kickoff = dock.addWidget("org.kde.plasma.kickoff");
kickoff.currentConfigGroup = ["Shortcuts"];
kickoff.writeConfig("global", "Alt+F1");
kickoff.currentConfigGroup = ["General"];
kickoff.writeConfig("icon", "distributor-logo-steamdeck");
kickoff.writeConfig("favorites", [
    "preferred://browser",
    "applications:org.kde.dolphin.desktop",
    "applications:org.kde.konsole.desktop",
    "applications:org.kde.kate.desktop",
    "applications:org.kde.discover.desktop",
    "applications:systemsettings.desktop",
]);

var tasks = dock.addWidget("org.kde.plasma.icontasks");
tasks.currentConfigGroup = ["General"];
tasks.writeConfig("launchers", [
    "applications:steamdeck-steam.desktop",
    "preferred://filemanager",
    "preferred://browser",
    "applications:org.kde.konsole.desktop",
    "applications:org.kde.kate.desktop",
]);

var bar = new Panel;
bar.location = "top";
bar.height = 2 * Math.ceil(gridUnit * 1.6 / 2);
bar.addWidget("org.kde.plasma.pager");
bar.addWidget("org.kde.plasma.panelspacer");
var clock = bar.addWidget("org.kde.plasma.digitalclock");
clock.currentConfigGroup = ["Appearance"];
clock.writeConfig("dateFormat", "isoDate");
clock.writeConfig("showDate", true);
bar.addWidget("org.kde.plasma.panelspacer");
var tray = bar.addWidget("org.kde.plasma.systemtray");
var trayId = tray.readConfig("SystrayContainmentId");
if (trayId) {
    var trayContainment = desktopById(trayId);
    trayContainment.currentConfigGroup = ["General"];
    trayContainment.writeConfig("scaleIconsToFit", true);
}
bar.addWidget("org.kde.plasma.showdesktop");
