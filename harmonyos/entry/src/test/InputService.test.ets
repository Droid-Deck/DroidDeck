import { describe, it, expect } from '@ohos/hypium';

import { InputService } from '../../main/ets/service/InputService';

/**
 * Tests for the ported key mapping.
 *
 * Upstream's `input/EvdevKeys.java` turns Android key codes into Linux
 * evdev codes, and the guest's input stack only understands evdev. The port
 * maps ArkUI's key codes onto the same evdev values, so a mismatch here
 * would show up as the guest receiving the wrong key.
 */
export default function inputServiceTest() {
  describe('InputService', () => {
    it('mapsLettersToEvdev', 0, () => {
      expect(InputService.evdevFor(2017)).assertEqual(30); // A
      expect(InputService.evdevFor(2018)).assertEqual(48); // B
      expect(InputService.evdevFor(2020)).assertEqual(32); // D
      expect(InputService.evdevFor(2021)).assertEqual(18); // E
      expect(InputService.evdevFor(2042)).assertEqual(44); // Z
    });

    it('mapsDigitsToEvdev', 0, () => {
      expect(InputService.evdevFor(2000)).assertEqual(11); // 0
      expect(InputService.evdevFor(2001)).assertEqual(2);  // 1
      expect(InputService.evdevFor(2009)).assertEqual(10); // 9
    });

    it('mapsEditingKeysToEvdev', 0, () => {
      expect(InputService.evdevFor(2054)).assertEqual(28); // Enter
      expect(InputService.evdevFor(2050)).assertEqual(57); // Space
      expect(InputService.evdevFor(2049)).assertEqual(15); // Tab
      expect(InputService.evdevFor(2055)).assertEqual(14); // Delete
      expect(InputService.evdevFor(2070)).assertEqual(1);  // Escape
    });

    it('mapsModifiersAndArrowsToEvdev', 0, () => {
      expect(InputService.evdevFor(2047)).assertEqual(42);  // Shift
      expect(InputService.evdevFor(2072)).assertEqual(29);  // Control
      expect(InputService.evdevFor(2045)).assertEqual(56);  // Alt
      expect(InputService.evdevFor(2012)).assertEqual(103); // Up
      expect(InputService.evdevFor(2013)).assertEqual(108); // Down
      expect(InputService.evdevFor(2014)).assertEqual(105); // Left
      expect(InputService.evdevFor(2015)).assertEqual(106); // Right
    });

    it('mapsFunctionKeysToEvdev', 0, () => {
      expect(InputService.evdevFor(2090)).assertEqual(59); // F1
      expect(InputService.evdevFor(2101)).assertEqual(88); // F12
    });

    it('ignoresUnmappedKeys', 0, () => {
      expect(InputService.evdevFor(0)).assertEqual(-1);
      expect(InputService.evdevFor(9999)).assertEqual(-1);
    });
  });
}
