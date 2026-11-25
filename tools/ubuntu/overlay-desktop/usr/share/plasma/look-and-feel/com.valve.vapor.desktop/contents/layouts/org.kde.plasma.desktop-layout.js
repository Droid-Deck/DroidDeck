var desktops = desktopsForActivity(currentActivity());
for (var i = 0; i < desktops.length; i++) {
    desktops[i].wallpaperPlugin = "org.kde.image";
    desktops[i].currentConfigGroup = ["Wallpaper", "org.kde.image", "General"];
    desktops[i].writeConfig("Image", "file:///usr/share/wallpapers/DroidDeck");
}

var panel = new Panel;
var panelScreen = panel.screen;
panel.location = "bottom";
panel.height = 2 * Math.ceil(gridUnit * 2.5 / 2);

const maximumAspectRatio = 21 / 9;
if (panel.formFactor === "horizontal") {
    const geo = screenGeometry(panelScreen);
    const maximumWidth = Math.ceil(geo.height * maximumAspectRatio);
    if (geo.width > maximumWidth) {
        panel.alignment = "center";
        panel.minimumLength = maximumWidth;
        panel.maximumLength = maximumWidth;
    }
}

var kickoff = panel.addWidget("org.kde.plasma.kickoff");
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

panel.addWidget("org.kde.plasma.pager");

var tasks = panel.addWidget("org.kde.plasma.icontasks");
tasks.currentConfigGroup = ["General"];
tasks.writeConfig("launchers", [
    "applications:steamdeck-steam.desktop",
    "applications:systemsettings.desktop",
    "applications:org.kde.discover.desktop",
    "preferred://filemanager",
    "preferred://browser",
    "applications:org.kde.konsole.desktop",
    "applications:org.kde.kate.desktop",
]);

panel.addWidget("org.kde.plasma.marginsseparator");

const inputMethodLanguages = ["as", "bn", "bo", "brx", "doi", "gu", "hi", "ja", "kn", "ko", "kok", "ks", "lep", "mai",
    "ml", "mni", "mr", "ne", "or", "pa", "sa", "sat", "sd", "si", "ta", "te", "th", "ur", "vi", "zh_CN", "zh_TW"];
if (inputMethodLanguages.indexOf(languageId) != -1) {
    panel.addWidget("org.kde.plasma.kimpanel");
}

var tray = panel.addWidget("org.kde.plasma.systemtray");
var trayId = tray.readConfig("SystrayContainmentId");
if (trayId) {
    var trayContainment = desktopById(trayId);
    trayContainment.currentConfigGroup = ["General"];
    trayContainment.writeConfig("scaleIconsToFit", true);
}
var clock = panel.addWidget("org.kde.plasma.digitalclock");
clock.currentConfigGroup = ["Appearance"];
clock.writeConfig("dateFormat", "isoDate");
panel.addWidget("org.kde.plasma.showdesktop");
