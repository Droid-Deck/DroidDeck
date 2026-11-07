#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <tuple>
#include <unordered_map>

struct Key {
  uint32_t value = 0;

  bool operator == (const Key& other) const {
    return value == other.value;
  }
};

struct Hash {
  std::size_t operator () (const Key& key) const {
    return key.value;
  }
};

struct Value {
  explicit Value(Key key)
  : value(key.value) { }

  uint32_t value;
};

int main() {
  std::array<char, 2> text = {{ 'A', 'Z' }};
  std::transform(text.begin(), text.end(), text.begin(),
    [] (char value) { return char(value - 'A' + 'a'); });

  Key key;
  std::unordered_map<Key, Value, Hash> values;
  auto entry = values.emplace(
    std::piecewise_construct,
    std::tuple(key),
    std::tuple(key));

  return !entry.second || entry.first->first.value || entry.first->second.value
      || text[0] != 'a' || text[1] != 'z';
}
