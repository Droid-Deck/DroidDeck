import { describe, it, expect } from '@ohos/hypium';

import { GpuClassifier, GpuFamily, GpuSupport } from '../../main/ets/model/DeviceInfo';

/**
 * Tests for the ported GPU classification.
 *
 * The Mali cases matter most here: Huawei devices are the target of this
 * port, and upstream DroidDeck already ships PanVK builds for several Mali
 * generations, so the classifier has to land on the right one.
 */
export default function gpuClassifierTest() {
  describe('GpuClassifier', () => {
    it('classifiesAdrenoGenerations', 0, () => {
      expect(GpuClassifier.classify('Adreno (TM) 850')).assertEqual(GpuFamily.A8XX);
      expect(GpuClassifier.classify('Adreno (TM) 740')).assertEqual(GpuFamily.A7XX);
      expect(GpuClassifier.classify('Adreno (TM) 725')).assertEqual(GpuFamily.A7XX);
      expect(GpuClassifier.classify('Adreno (TM) 650')).assertEqual(GpuFamily.A6XX);
      expect(GpuClassifier.classify('Adreno (TM) 710')).assertEqual(GpuFamily.A7XX_LOW);
    });

    it('classifiesMaliGenerations', 0, () => {
      expect(GpuClassifier.classify('Mali-G78')).assertEqual(GpuFamily.MALI_JM);
      expect(GpuClassifier.classify('Mali-G57')).assertEqual(GpuFamily.MALI_JM);
      expect(GpuClassifier.classify('Mali-G610')).assertEqual(GpuFamily.MALI_G610_G710);
      expect(GpuClassifier.classify('Mali-G710')).assertEqual(GpuFamily.MALI_G610_G710);
      expect(GpuClassifier.classify('Mali-G615')).assertEqual(GpuFamily.MALI_G615);
      expect(GpuClassifier.classify('Mali-G720')).assertEqual(GpuFamily.MALI_G720);
      expect(GpuClassifier.classify('Mali-G925')).assertEqual(GpuFamily.MALI_LATEST);
    });

    it('classifiesUnknownHardware', 0, () => {
      expect(GpuClassifier.classify('')).assertEqual(GpuFamily.NOT_ADRENO);
      expect(GpuClassifier.classify('PowerVR Rogue GE8320')).assertEqual(GpuFamily.POWERVR);
    });

    it('reportsSupport', 0, () => {
      expect(GpuClassifier.support(GpuFamily.A8XX, 850, false)).assertEqual(GpuSupport.TESTED);
      expect(GpuClassifier.support(GpuFamily.MALI_JM, 78, false)).assertEqual(GpuSupport.UNTESTED);
      // The bridge gives an uncovered Mali a path through the phone's own
      // Vulkan driver instead of leaving it unsupported.
      expect(GpuClassifier.support(GpuFamily.MALI_OTHER, 0, true)).assertEqual(GpuSupport.UNTESTED);
      expect(GpuClassifier.support(GpuFamily.MALI_OTHER, 0, false)).assertEqual(GpuSupport.UNSUPPORTED);
      expect(GpuClassifier.support(GpuFamily.NOT_ADRENO, 0, false)).assertEqual(GpuSupport.UNSUPPORTED);
    });

    it('namesDrivers', 0, () => {
      expect(GpuClassifier.driverLabel(GpuFamily.MALI_JM)).assertEqual('PanVK · Mali G52-G78');
      expect(GpuClassifier.driverLabel(GpuFamily.A8XX)).assertEqual('Banners-Turnip · Adreno 8xx');
    });
  });
}
