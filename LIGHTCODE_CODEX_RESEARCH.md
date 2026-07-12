# LightCode Codex Research Ledger

This document records the sources and engineering ideas used for the duplicate experimental app. Historical descriptions and reported UAP motifs are treated as design inputs, while implemented behavior is described directly and testably.

## 1. Ancient optical information systems

### Polybius: arbitrary messages through fire

Polybius, *Histories* Book X, chapters 43-47, distinguishes simple preset beacons from a system capable of conveying any unforeseen message. The system uses:

- an attention handshake before transmission
- synchronized observers
- compact wording to reduce message length
- five divisions and five positions
- repeated practice and error avoidance

Source: https://penelope.uchicago.edu/Thayer/E/Roman/Texts/Polybius/10*.html

Codex translation:

- Five-Flame mode uses five simultaneous frequency lanes rather than alphabet torches.
- A preamble announces that a payload is beginning.
- File length and CRC32 permit receiver validation.
- Repeated frames improve recovery.

### Aeneas Tacticus: hydraulic timing channel

Polybius preserves Aeneas's earlier system using matched water vessels. Torches synchronize the start and stop of draining water, and information is selected by elapsed time and water level.

Codex translation:

- Water-Clock mode uses 16-position pulse-position modulation.
- Information lives in the timing position of a light pulse inside a fixed window.
- The opening burst sequence provides synchronization.
- Equal symbol duration creates a shared clock without Morse dots and dashes.

### Ancient beacon chains

Long-distance fire networks show a relay architecture: one station receives a visible state and passes it onward. A future Codex repeater can listen to jar audio with the microphone, validate a packet, and retransmit it as light from another phone.

## 2. Direct words carried by light

### Alexander Graham Bell and Charles Sumner Tainter

Bell's 1880 photophone transmitted speech by continuously varying reflected light. Bell also investigated direct sound generation when interrupted or modulated light was absorbed by dark materials. Lampblack was especially responsive.

Primary historical paper:

- Alexander Graham Bell, “On the Production and Reproduction of Sound by Light,” 1880.

Background index:

- https://en.wikipedia.org/wiki/Photophone

Codex translation:

- Typed words are synthesized internally but never intentionally played through the built-in speaker.
- The waveform directly controls light intensity.
- Speech clarity mode adds pre-emphasis, dynamic compression and soft limiting before optical modulation.

## 3. Photoacoustic receiver improvements

### Absorber material

A modern comparison of carbon absorbers found candle-soot coating produced a stronger photoacoustic response than tested black paint and carbon-nanotube coatings across the investigated range.

Source:

- Jussi Rossi et al., “Photoacoustic characteristics of carbon-based infrared absorbers,” arXiv:2012.01568.

Codex implication:

- The app includes jar-specific measurement rather than assuming all black surfaces respond equally.
- Physical experiments can compare the current paint against a removable soot-coated insert without changing the original jar permanently.

### Resonance and equalization

Photoacoustic cells become much more sensitive when modulation coincides with an acoustic resonance. Codex now transmits a logarithmic sweep while recording the physical jar through the phone microphone. It ranks candidate resonant regions and can retune Tone mode to the strongest measured frequency.

Future path:

- save separate profiles for each jar, color and phone position
- create a measured inverse equalizer for speech
- use repeated sweeps to estimate noise and confidence

### Graphene-aerogel inspiration

Research on graphene aerogels reports broad photo-thermo-acoustic conversion with low moving mass. This suggests future removable absorber inserts, rather than treating ordinary black paint as the final receiver material.

Sources:

- arXiv:1909.08858
- arXiv:2009.03670

## 4. Screen and LED optical communications

### Rolling scan and spatial-to-temporal coding

Rolling-shutter optical communication exploits row-by-row sensor timing. Codex applies the inverse idea experimentally at the display: many waveform samples are represented across screen rows, and differential row brightness attempts to create a rapid aggregate-light staircase during panel scanning.

Relevant research:

- “Symbol Rate Maximization in Rolling-Shutter OCC,” arXiv:2602.08474.
- “DeepLight,” arXiv:2105.05092.

Codex additions:

- selectable 128, 256, 384, 512 or 640 raster rows
- reversible scan direction
- selectable red, green, blue, amber, cyan, magenta or white output
- adaptive gain to reduce clipping between consecutive raster frames

### LED bias and predistortion

Visible-light communication research shows that LED bandwidth and distortion depend on DC bias, temperature and nonlinear response. Digital predistortion and equalization can compensate for these limits.

Sources:

- arXiv:1612.08477
- arXiv:1904.10987
- arXiv:2505.19709

Future Codex path:

- microphone-feedback predistortion for the USB LED route
- per-device transfer-function calibration
- temperature-aware gain reduction
- sigma-delta binary LED mode

## 5. Extraterrestrial-light communication design motifs

### Optical SETI

Optical SETI searches are designed around the possibility that a technological civilization could use short, intense laser pulses or coherent narrow-band light as a beacon. The most useful engineering concepts are repetition, precise timing, narrow spectral channels, pulse-position encoding and a recognizable beacon preceding payload data.

Sources:

- “Interstellar communication XI: Short pulse duration limits of optical SETI,” arXiv:1804.01251.
- “A Proposed Method for a Photon-Counting Laser Coherence Detection System,” arXiv:1902.05371.
- Optical SETI with Air Cerenkov Telescopes, astro-ph/0111081.

Codex translation:

- Firefly mode repeats an attention burst and 16-position timed payload.
- Water-Clock PPM uses equal-energy pulses whose position carries information.
- Five-Flame mode carries parallel information in multiple frequencies.
- CRC framing distinguishes a deliberate complete packet from random flashes.

### Reported luminous UAP behavior

Some observational literature reports luminous objects with regular brightness variation, including a cited 10-20 Hz range. Codex does not treat the reported source as established; it uses the recurring-brightness motif as inspiration for persistent pilots, burst groups and timing-lock patterns.

Source:

- “Unidentified aerial phenomena I. Observations of events,” arXiv:2208.11215.

### Light-generated sound without a solid receiver

Research has demonstrated photoacoustic sound generated in air by modulated laser energy absorbed by water vapor. This is beyond a phone-screen build, but it points toward future directed-light audio experiments using suitable laboratory optical hardware.

Relevant work:

- MIT Lincoln Laboratory work reported in *Optics Letters* on remote photoacoustic sound generation.

## 6. Current Codex modes

- Direct typed speech through modulated light
- Audio file through modulated light
- AFSK exact-file modem
- Water-Clock 16-position PPM file mode
- Five-Flame five-carrier file mode
- Firefly repeated text beacon
- Spectral screen output
- Adjustable raster density and direction
- Automatic physical-jar resonance measurement
- Torch and USB-C LED-DAC output inherited from LightCode Jar

The original LightCode Jar project remains unchanged on `agent/lightcode-jar`. Codex uses a separate Android application ID and separate build artifact.
