# LightCode Investigation Lab Research Ledger

## Central distinction: payload versus carrier

The declassified remote-strobe experiment did **not** encode a sentence, image or secret file. Its transmitted experimental state was only one of three randomized conditions:

```text
0 Hz / no flash
6 flashes per second
16 flashes per second
```

The experiment looked for physiological correlations in a remote receiver. Investigation Lab preserves that design as a seeded trial-schedule export.

Universal Light Payload separates the two layers:

```text
PAYLOAD
Any text or any Android-readable file, regardless of MIME type

CARRIER
4-FSK, Manchester BPSK, 16-position PPM, chirp CSK,
PRBS-31 spread spectrum, Gold-31 coded spread, or slow torch OOK
```

The carrier determines how the bytes become a light-controlled waveform. The payload remains exactly what the user chose.

## Evidence layers

1. **Declassified**: directly described in released government records.
2. **Established engineering**: standard communications, signal processing, photoacoustics or optical-camera methods.
3. **Frontier**: specialist peer-reviewed or preprint research.
4. **Reported fringe**: witness reports and speculative interpretations used as geometry or timing inspiration, clearly separated from demonstrated engineering.

## MKULTRA findings

The released records establish a broad CIA umbrella program involving drugs, hypnosis, behavior research and unwitting human experimentation. In the records located during this research pass, no surviving MKULTRA document described a device for encoding arbitrary files, full sentences or music into a light beam.

Sources:

- CIA statement on MKULTRA, 21 September 1977: https://www.cia.gov/readingroom/document/cia-rdp99-00498r000300020007-3
- ARTICHOKE program restatement: https://www.cia.gov/readingroom/document/00190673
- CIA *Studies in Intelligence* paper on hypnosis: https://www.cia.gov/readingroom/document/cia-rdp78-03921a000300310001-0

## Declassified remote-strobe research

A later CIA/SRI perceptual-augmentation report described a sender exposed to a stroboscopic flash train while a receiver sat in a visually opaque, acoustically and electrically shielded room. The documented design used:

- 36 trials per run
- 12 null trials
- 12 trials at 6 flashes per second
- 12 trials at 16 flashes per second
- randomized order
- a warning cue followed one second later by a ten-second stimulus or null interval
- EEG monitoring of the receiver

This belonged to later CIA-sponsored parapsychology research rather than the original MKULTRA program.

Source:

- https://www.cia.gov/readingroom/document/cia-rdp96-00791r000300030003-0

Investigation Lab exports a reproducible CSV schedule with an explicit random seed. It does not generate a human-facing 6 Hz or 16 Hz strobe.

## Kirlian and Soviet psychoenergetic files

CIA holdings include technical and open-source reports concerning Kirlian photography and Soviet psychoenergetic-device claims. The technical description emphasizes:

- pulsed high-frequency excitation
- high peak voltage with low average power
- pulse width and repetition timing
- electrode and dielectric geometry
- corona-discharge imaging
- recording and display configuration

Kirlian photography is a high-voltage corona-discharge process, not an ordinary optical transmitter. The app's Kirlian-inspired mode copies only the reported pulse-envelope concept into an instrument-only optical carrier. It does not reproduce high voltage or corona discharge.

Sources:

- https://www.cia.gov/readingroom/document/cia-rdp96-00787r000100200002-9
- https://www.cia.gov/readingroom/document/cia-rdp79-00999a000200010086-1

## Established optical and acoustic coding included

### Fast 4-FSK

Each tone symbol selects one of four frequencies, carrying two bits per symbol. This is the fastest general universal carrier in the current app.

### Manchester BPSK

Every bit contains a transition. Phase reversal on a sinusoidal carrier makes the packet self-clocking and reduces long periods without timing information.

### 16-position pulse-position modulation

Each four-bit nibble selects one pulse location inside sixteen equal slots. Information survives even when absolute amplitude varies.

### Up/down chirp CSK

A rising chirp represents one bit state and a falling chirp represents the other. Chirps can remain detectable through resonant and multipath channels.

### PRBS-31 and Gold-31 spread carriers

Every data bit is multiplied by a known thirty-one-chip sequence. Correlation at the receiver can recover the data from noise and identify the intended code.

### Slow torch OOK

The phone flashlight exposes only a low update rate. Slow on/off keying is therefore included for tiny payloads and proof-of-channel tests, not as a practical large-file route.

### Barker and pseudorandom acquisition signals

The fixed-protocol laboratory includes Barker-13, PRBS-127 and Gold-like beacons for channel detection, timing acquisition and correlation measurements.

### Logarithmic chirp probing

A broadband chirp measures the combined transfer path:

```text
light source
→ beam geometry
→ absorber
→ jar cavity
→ microphone or optical receiver
```

## Universal packet format

Universal Light Payload V3 imposes no software payload-size ceiling.

The app hashes the chosen source in a streaming pass and reopens it for transmission. It never expands the complete file into a giant in-memory waveform.

```text
128-byte alternating preamble
ULP3 magic
version
carrier identifier
FEC flags
UTF-8 filename
unsigned-style 64-bit payload length
4,096-byte block size
whole-file SHA-256
header CRC32

repeated blocks:
    four-byte sync word
    64-bit block index
    32-bit block length
    payload data
    block CRC32

footer:
    END3 magic
    total blocks
    total bytes
    repeated SHA-256
```

Optional extended Hamming (8,4) error correction encodes each four-bit nibble as one eight-bit code word.

Practical limits remain:

- readable storage
- transmission duration
- battery
- sustained output hardware
- receiver storage and decoder throughput

The length field itself is 64-bit, so the packet format is not limited to 2 KB, 256 KB or 4 GB.

## Photoacoustic transfer evidence

Published experiments have reproduced recognizable music using modulated light and dark absorbers.

A 2017 experiment drove a light source using the tune *Greensleeves* and recorded recognizable photoacoustic output from a black-painted transducer, although the signal-to-noise ratio was low. Turning the light away reduced the recorded signal by roughly two orders of magnitude.

Source:

- https://www.nature.com/articles/srep41251

Low-density graphene-sponge experiments later demonstrated broader, more efficient photo-thermo-acoustic song reproduction. Pulse-density modulation reduced distortion compared with analogue intensity control.

Source:

- https://arxiv.org/abs/1806.03930

## Optical-camera communication

Modern optical-camera communication uses CMOS rolling-shutter timing to recover modulation faster than the ordinary video frame rate. The app creates coded optical patterns; a dedicated camera decoder remains a future receiver layer.

Sources:

- https://arxiv.org/abs/1812.01259
- https://arxiv.org/abs/2602.08474

## Frontier techniques

### Photoacoustic feedback and wavefront shaping

Photoacoustic transmission-matrix work uses measured acoustic feedback to select optical patterns that focus more energy on an absorbing target. Barker, PRBS, Gold-code, chirp and receiver-recording modes provide building blocks for future adaptive pattern evolution.

Sources:

- https://arxiv.org/abs/1305.6246
- https://arxiv.org/abs/1402.0279

### Digital optoacoustic modulation

Recent work has reproduced arbitrary digital audio using dense optical pulse trains and sigma-delta or pulse-amplitude modulation. The external RP2040 route is the appropriate high-rate path for these methods.

Sources:

- https://www.nature.com/articles/s41598-020-78990-z
- https://www.nature.com/articles/s41598-024-62382-8

### Gas-phase photoacoustic sound

Laboratory researchers have generated tones, music and recorded speech from light absorbed by water vapor in air. This confirms that a solid black receiver is not the only photoacoustic medium, although the required laser equipment is specialized.

## Fringe and witness-pattern archive

These reports are not treated as verified circuit diagrams. Recurring motifs become measurable beam geometries or spectral patterns.

### Hollow cylindrical beams

Hypnosis-linked encounter accounts such as the Allagash narrative describe hollow tube-like light. The app's hollow-beam geometry illuminates outer regions while leaving a dark center.

### Crystalline shafts

Reports of crystalline or concentrated shafts inspire central-beam geometry, prisms, faceted light guides and optical concentrators.

### Oscillating quadrants and structured color paths

Descriptions of changing quadrants or colored energy paths inspire phase-offset multicarrier fingerprints and separated RGB experiments.

### Source-less luminous interiors

Reports of walls or fog-like volumes emitting light inspire diffuser, full-aperture and multi-surface receiver tests.

These motifs are recorded as hypotheses and geometry ideas, not proof of extraterrestrial hardware.

## Included launcher set

### LightCode Investigation Lab

Fixed research protocols, session CSV logs, evidence labels, screen/torch/USB output and microphone receiver recording.

### Universal Light Payload

Any text or any file type, streamed without an artificial size cap using a selectable carrier and exact packet framing.

### Photophone Loop

The original full-options audio-through-light interface with continuous looping.

### Photophone Loop Torch

A dedicated looping audio-to-phone-flashlight interface.

## Controlled-lab behavior

- Live screen or torch output requires confirmation that the emitter is enclosed or aimed at a jar, camera or sensor.
- The declassified 0/6/16 Hz protocol is schedule-only.
- The Kirlian-inspired carrier is restricted to the external instrument output.
- Session logs identify the active protocol and evidence layer.
- Fringe-inspired methods remain separately labeled.
