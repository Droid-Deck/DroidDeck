#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Value<'a> {
    Varint(u64),
    Fixed64(u64),
    Bytes(&'a [u8]),
    Fixed32(u32),
}

pub struct Fields<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Fields<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Fields { data, pos: 0 }
    }

    fn varint(&mut self) -> Result<u64, String> {
        let mut value = 0u64;
        for i in 0..10 {
            let Some(&b) = self.data.get(self.pos) else {
                return Err("a number runs past the end of the message".into());
            };
            self.pos += 1;
            if i == 9 && b > 1 {
                return Err("a number is longer than 64 bits".into());
            }
            value |= ((b & 0x7f) as u64) << (7 * i);
            if b & 0x80 == 0 {
                return Ok(value);
            }
        }
        Err("a number is longer than 10 bytes".into())
    }

    fn take(&mut self, n: u64) -> Result<&'a [u8], String> {
        let left = (self.data.len() - self.pos) as u64;
        if n > left {
            return Err(format!("a field of {n} bytes runs past the end of the message ({left} left)"));
        }
        let start = self.pos;
        self.pos += n as usize;
        Ok(&self.data[start..self.pos])
    }

    pub fn next_field(&mut self) -> Result<Option<(u32, Value<'a>)>, String> {
        if self.pos >= self.data.len() {
            return Ok(None);
        }
        let key = self.varint()?;
        let field = key >> 3;
        if field == 0 || field > u32::MAX as u64 {
            return Err(format!("invalid field number {field}"));
        }
        let value = match key & 7 {
            0 => Value::Varint(self.varint()?),
            1 => {
                let b = self.take(8)?;
                Value::Fixed64(u64::from_le_bytes(b.try_into().expect("8 bytes")))
            }
            2 => {
                let n = self.varint()?;
                Value::Bytes(self.take(n)?)
            }
            5 => {
                let b = self.take(4)?;
                Value::Fixed32(u32::from_le_bytes(b.try_into().expect("4 bytes")))
            }
            other => return Err(format!("unsupported wire type {other} for field {field}")),
        };
        Ok(Some((field as u32, value)))
    }
}

#[cfg(test)]
pub mod write {
    pub fn varint(out: &mut Vec<u8>, mut v: u64) {
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                return;
            }
            out.push(b | 0x80);
        }
    }

    pub fn key(out: &mut Vec<u8>, field: u32, wire: u8) {
        varint(out, (field as u64) << 3 | wire as u64);
    }

    pub fn uint(out: &mut Vec<u8>, field: u32, v: u64) {
        key(out, field, 0);
        varint(out, v);
    }

    pub fn bytes(out: &mut Vec<u8>, field: u32, data: &[u8]) {
        key(out, field, 2);
        varint(out, data.len() as u64);
        out.extend_from_slice(data);
    }
}

#[cfg(test)]
mod tests {
    use super::write;
    use super::*;

    fn all(data: &[u8]) -> Result<Vec<(u32, Value<'_>)>, String> {
        let mut f = Fields::new(data);
        let mut out = Vec::new();
        while let Some(x) = f.next_field()? {
            out.push(x);
        }
        Ok(out)
    }

    #[test]
    fn reads_every_supported_wire_type() {
        let mut m = Vec::new();
        write::uint(&mut m, 1, 300);
        write::key(&mut m, 2, 1);
        m.extend_from_slice(&7u64.to_le_bytes());
        write::bytes(&mut m, 3, b"hi");
        write::key(&mut m, 4, 5);
        m.extend_from_slice(&9u32.to_le_bytes());
        write::uint(&mut m, 5, u64::MAX);
        let got = all(&m).unwrap();
        assert_eq!(
            got,
            vec![
                (1, Value::Varint(300)),
                (2, Value::Fixed64(7)),
                (3, Value::Bytes(b"hi")),
                (4, Value::Fixed32(9)),
                (5, Value::Varint(u64::MAX)),
            ]
        );
    }

    #[test]
    fn malformed_messages_are_errors() {
        assert!(all(&[0x0a, 0x05, b'a']).is_err());
        assert!(all(&[0x08, 0xff, 0xff]).is_err());
        assert!(all(&[0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01]).is_err());
        assert!(all(&[0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x02]).is_err());
        assert!(all(&[0x0b]).is_err());
        assert!(all(&[0x0f]).is_err());
        assert!(all(&[0x00, 0x01]).is_err());
        assert!(all(&[0x0d, 1, 2]).is_err());
        assert_eq!(all(&[]).unwrap(), vec![]);
    }
}
