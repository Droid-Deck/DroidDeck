#[derive(Clone, Copy)]
pub enum MessageLevel {
    Error,
    Info,
}

pub enum MessageButtons {
    Ok,
    YesNo,
    YesNoCancelCustom(String, String, String),
}

pub enum MessageDialogResult {
    Yes,
    No,
    Ok,
    Cancel,
    Custom(String),
}

pub struct MessageDialog {
    title: String,
    description: String,
    level: MessageLevel,
    buttons: Option<MessageButtons>,
}

impl Default for MessageDialog {
    fn default() -> Self {
        Self {
            title: String::new(),
            description: String::new(),
            level: MessageLevel::Info,
            buttons: None,
        }
    }
}

impl MessageDialog {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }

    pub fn set_description(mut self, description: impl Into<String>) -> Self {
        self.description = description.into();
        self
    }

    pub fn set_level(mut self, level: MessageLevel) -> Self {
        self.level = level;
        self
    }

    pub fn set_buttons(mut self, buttons: MessageButtons) -> Self {
        self.buttons = Some(buttons);
        self
    }

    pub fn show(&self) -> MessageDialogResult {
        let _ = (&self.title, &self.description, self.level, &self.buttons);
        MessageDialogResult::Cancel
    }
}
