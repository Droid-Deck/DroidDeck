import { describe, it, expect } from '@ohos/hypium';

import { Paths } from '../../main/ets/common/Paths';
import { RuntimeService } from '../../main/ets/service/RuntimeService';

/**
 * Tests for the paths the guest is told about.
 *
 * The guest sees the rootfs as its /, and the host's runtime directory is
 * bound into it at /run/droiddeck. A path that is not bound resolves
 * inside the guest's rootfs, where the file is not — and the failure is
 * quiet: the guest's audio client simply never connects.
 */
export default function guestPathsTest() {
  describe('the guest paths', () => {
    it('namesTheGuestRuntimeDirectory', 0, () => {
      expect(Paths.guestRuntimeDir()).assertEqual('/run/droiddeck');
    });

    it('pointsThePulseAudioClientAtTheGuestSocket', 0, () => {
      expect(RuntimeService.pulseServerAddress())
        .assertEqual('unix:/run/droiddeck/pulse/native');
    });

    it('neverHandsTheGuestAHostPath', 0, () => {
      const env = RuntimeService.sessionEnvironment({
        command: '/usr/local/bin/droiddeck-session',
        args: ['desktop'],
        width: 1080,
        height: 2400,
        logPath: '/data/storage/el2/base/haps/entry/files/logs/session.log',
        debugDir: '/data/storage/el2/base/haps/entry/files/logs'
      });

      // The PulseAudio socket is the one path that has no bind behind it,
      // so it has to be the guest's own.
      expect(env['PULSE_SERVER'].indexOf(Paths.guestRuntimeDir()) >= 0).assertTrue();
    });
  });
}
