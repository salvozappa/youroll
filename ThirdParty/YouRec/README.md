# From YouRec

Files copied unchanged from [YouRec](https://github.com/antirez/YouRecApp) by Salvatore
Sanfilippo (MIT, see the repository LICENSE):

- `StabilizationMotion.cpp`, `StabilizationMotion.h`: the motion estimator behind
  stabilization, built into the app with the NDK.
- `StabilizationMotionTests.cpp`: its tests, built and run on the host by
  `android/scripts/check.sh`.
- `Fixtures/editor-fixture.mp4`: synthetic test media (a geometric image, a generated tone
  and silence) used by the emulator test scripts.
