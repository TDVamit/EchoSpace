# Third-party components

- **YOLOE-26s segmentation model:** supplied by a team member as a LiteRT export. Its embedded metadata names Ultralytics as author and identifies AGPL-3.0. See [Ultralytics licensing](https://www.ultralytics.com/license). The appended metadata was edited to remove an absolute local dataset path; the TFLite flatbuffer bytes were not altered. The related integration is [prateekgarg08/object-detection](https://github.com/prateekgarg08/object-detection). Confirm model, training data, and distribution rights before reuse.
- **Google ARCore and LiteRT:** used by the Android prototype for depth/motion and model inference. See their respective distribution terms and documentation.
- **React Native, React, Three.js, and other packages:** dependencies are identified in the component `package.json` and lockfiles. Review their individual licenses for redistribution.

No license for the original EchoSpace source has been specified in this repository. Public visibility alone does not grant a separate open-source license to that source.
