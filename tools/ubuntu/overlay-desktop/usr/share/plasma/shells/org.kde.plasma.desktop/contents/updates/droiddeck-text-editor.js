panels().forEach(function (panel) {
    panel.widgets("org.kde.plasma.icontasks").forEach(function (tasks) {
        tasks.currentConfigGroup = ["General"];
        var launchers = tasks.readConfig("launchers");
        if (typeof launchers === "string") launchers = launchers.length ? launchers.split(",") : [];
        if (launchers.indexOf("applications:org.kde.kate.desktop") < 0) {
            launchers.push("applications:org.kde.kate.desktop");
            tasks.writeConfig("launchers", launchers);
        }
    });
});
