// Shared MathLive settings: no sounds, no remote features. Fonts load from the default
// ./fonts/ next to mathlive.min.js, which is where they are vendored.
'use strict';

// Sounds are not shipped; null disables them, so nothing is requested.
MathfieldElement.soundsDirectory = null;
MathfieldElement.keypressSound = null;
MathfieldElement.plonkSound = null;
