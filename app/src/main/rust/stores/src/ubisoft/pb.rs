pub use crate::amazon::proto::{Fields, Value};

#[derive(Default)]
pub struct Msg(pub Vec<u8>);

impl Msg {
    pub fn new() -> Self {
        Msg(Vec::new())
    }

    fn varint(&mut self, mut v: u64) {
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                self.0.push(b);
                return;
            }
            self.0.push(b | 0x80);
        }
    }

    fn key(&mut self, field: u32, wire: u8) {
        self.varint((field as u64) << 3 | wire as u64);
    }

    pub fn uint(mut self, field: u32, v: u64) -> Self {
        self.key(field, 0);
        self.varint(v);
        self
    }

    pub fn flag(self, field: u32, v: bool) -> Self {
        self.uint(field, v as u64)
    }

    pub fn bytes(mut self, field: u32, data: &[u8]) -> Self {
        self.key(field, 2);
        self.varint(data.len() as u64);
        self.0.extend_from_slice(data);
        self
    }

    pub fn text(self, field: u32, s: &str) -> Self {
        self.bytes(field, s.as_bytes())
    }

    pub fn msg(self, field: u32, m: Msg) -> Self {
        self.bytes(field, &m.0)
    }
}

/// Every field of a message, in wire order; repeated fields appear once per value.
pub fn fields(data: &[u8]) -> Result<Vec<(u32, Value<'_>)>, String> {
    let mut out = Vec::new();
    let mut f = Fields::new(data);
    while let Some(v) = f.next_field()? {
        out.push(v);
    }
    Ok(out)
}

pub fn first<'a>(all: &[(u32, Value<'a>)], field: u32) -> Option<Value<'a>> {
    all.iter().find(|(n, _)| *n == field).map(|(_, v)| *v)
}

pub fn sub<'a>(all: &[(u32, Value<'a>)], field: u32) -> Option<&'a [u8]> {
    match first(all, field) {
        Some(Value::Bytes(b)) => Some(b),
        _ => None,
    }
}

pub fn subs<'a>(all: &[(u32, Value<'a>)], field: u32) -> Vec<&'a [u8]> {
    all.iter().filter_map(|(n, v)| match (n, v) { (n, Value::Bytes(b)) if *n == field => Some(*b), _ => None }).collect()
}

pub fn num(all: &[(u32, Value<'_>)], field: u32) -> Option<u64> {
    match first(all, field) {
        Some(Value::Varint(v)) | Some(Value::Fixed64(v)) => Some(v),
        Some(Value::Fixed32(v)) => Some(v as u64),
        _ => None,
    }
}

pub fn string(all: &[(u32, Value<'_>)], field: u32) -> Option<String> {
    sub(all, field).map(|b| String::from_utf8_lossy(b).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_nested_messages() {
        let m = Msg::new().uint(1, 300).msg(2, Msg::new().text(1, "a").text(1, "b")).flag(3, true);
        let all = fields(&m.0).unwrap();
        assert_eq!(num(&all, 1), Some(300));
        assert_eq!(num(&all, 3), Some(1));
        let inner = fields(sub(&all, 2).unwrap()).unwrap();
        let texts: Vec<_> = subs(&inner, 1).iter().map(|b| String::from_utf8_lossy(b).into_owned()).collect();
        assert_eq!(texts, ["a", "b"]);
    }
}
