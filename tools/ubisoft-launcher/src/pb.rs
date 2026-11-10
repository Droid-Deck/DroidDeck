//! The protobuf wire format, enough to build and read the launcher messages by field number.

pub fn varint(mut n: u64, out: &mut Vec<u8>) {
    loop {
        let b = (n & 0x7f) as u8;
        n >>= 7;
        if n == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

#[derive(Default, Clone)]
pub struct Msg(pub Vec<u8>);

impl Msg {
    pub fn new() -> Self {
        Msg(Vec::new())
    }

    fn key(mut self, field: u32, wire: u8) -> Self {
        varint(((field as u64) << 3) | wire as u64, &mut self.0);
        self
    }

    pub fn uint(self, field: u32, value: u64) -> Self {
        let mut m = self.key(field, 0);
        varint(value, &mut m.0);
        m
    }

    pub fn bytes(self, field: u32, value: &[u8]) -> Self {
        let mut m = self.key(field, 2);
        varint(value.len() as u64, &mut m.0);
        m.0.extend_from_slice(value);
        m
    }

    pub fn text(self, field: u32, value: &str) -> Self {
        self.bytes(field, value.as_bytes())
    }

    pub fn msg(self, field: u32, value: Msg) -> Self {
        self.bytes(field, &value.0)
    }

    pub fn float(self, field: u32, value: f32) -> Self {
        let mut m = self.key(field, 5);
        m.0.extend_from_slice(&value.to_le_bytes());
        m
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Value<'a> {
    Int(u64),
    Bytes(&'a [u8]),
}

fn read_varint(b: &[u8], i: &mut usize) -> Option<u64> {
    let mut r = 0u64;
    let mut shift = 0;
    loop {
        let c = *b.get(*i)?;
        *i += 1;
        if shift >= 64 {
            return None;
        }
        r |= ((c & 0x7f) as u64) << shift;
        if c & 0x80 == 0 {
            return Some(r);
        }
        shift += 7;
    }
}

/// The top-level fields of a message, or `None` when it does not parse.
pub fn fields(b: &[u8]) -> Option<Vec<(u32, Value<'_>)>> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        let key = read_varint(b, &mut i)?;
        let field = (key >> 3) as u32;
        match key & 7 {
            0 => out.push((field, Value::Int(read_varint(b, &mut i)?))),
            1 => {
                let v = b.get(i..i + 8)?;
                i += 8;
                out.push((field, Value::Int(u64::from_le_bytes(v.try_into().ok()?))));
            }
            2 => {
                let n = read_varint(b, &mut i)? as usize;
                let v = b.get(i..i.checked_add(n)?)?;
                i += n;
                out.push((field, Value::Bytes(v)));
            }
            5 => {
                let v = b.get(i..i + 4)?;
                i += 4;
                out.push((field, Value::Int(u32::from_le_bytes(v.try_into().ok()?) as u64)));
            }
            _ => return None,
        }
    }
    Some(out)
}

pub fn sub<'a>(f: &[(u32, Value<'a>)], field: u32) -> Option<&'a [u8]> {
    f.iter().find_map(|(n, v)| match v {
        Value::Bytes(b) if *n == field => Some(*b),
        _ => None,
    })
}

pub fn num(f: &[(u32, Value<'_>)], field: u32) -> Option<u64> {
    f.iter().find_map(|(n, v)| match v {
        Value::Int(x) if *n == field => Some(*x),
        _ => None,
    })
}

pub fn has(f: &[(u32, Value<'_>)], field: u32) -> bool {
    f.iter().any(|(n, _)| *n == field)
}

/// The first length-delimited `field` at any depth up to `depth`, searching breadth first.
pub fn find(b: &[u8], field: u32, depth: u32) -> Option<&[u8]> {
    let f = fields(b)?;
    if let Some(v) = sub(&f, field) {
        return Some(v);
    }
    if depth == 0 {
        return None;
    }
    f.iter().find_map(|(_, v)| match v {
        Value::Bytes(inner) => find(inner, field, depth - 1),
        _ => None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let m = Msg::new().uint(1, 300).text(2, "hi").msg(3, Msg::new().uint(4, 1)).float(5, 1.0);
        let f = fields(&m.0).unwrap();
        assert_eq!(num(&f, 1), Some(300));
        assert_eq!(sub(&f, 2), Some(&b"hi"[..]));
        assert_eq!(num(&fields(sub(&f, 3).unwrap()).unwrap(), 4), Some(1));
        assert_eq!(num(&f, 5), Some(0x3f80_0000));
    }

    #[test]
    fn finds_nested_fields() {
        let m = Msg::new().msg(1, Msg::new().msg(1, Msg::new().uint(2, 109).text(10, "C:/game.exe")));
        assert_eq!(find(&m.0, 10, 4), Some(&b"C:/game.exe"[..]));
        assert_eq!(find(&m.0, 11, 4), None);
    }

    #[test]
    fn rejects_truncated_input() {
        assert!(fields(&[0x0a, 0x05, 1, 2]).is_none());
    }
}
