# LightCode Photophone Forge: Deep Research and Physical Upgrade Map

## The target

```text
actual song PCM
→ modulated light only
→ passive photoacoustic receiver
→ recognizable speech and music
```

The phone speaker is not part of the chain.

## Honest self-grade of Photophone V1

| Layer | Grade | Finding |
|---|---:|---|
| Whole-screen modulation | 1/10 | A 60–120 Hz display frame rate cannot directly carry ordinary audio bandwidth as one brightness value per frame. |
| Experimental grayscale rows | 3/10 | Produces visible patterns and some acoustic noise, but vendor scanout, sample-and-hold behavior and grayscale PWM are not controlled tightly enough for faithful PCM. |
| Painted glass absorber | 2/10 | Strong absorption is helpful, but thick paint bonded to thick glass has much higher thermal mass than the free-standing low-density absorbers used in successful broadband light loudspeakers. |
| Jar cavity | 3/10 | Adds useful gain at a few resonances but colors and rejects most of the spectrum. |
| Raw USB PCM architecture | 7/10 | Correctly bypasses Android media volume and can feed a purpose-built high-speed LED driver. It still requires physical testing and a broadband receiver. |
| Proof Mode | 5/10 | Records evidence but V1 compared only coarse envelopes and did not map the full transfer function. |

## What demonstrated real songs

The 2018 graphene-sponge light loudspeaker is the strongest direct template found.

- A free-standing graphene sponge had density close to air and about 5 percent reflectivity.
- A commercial LED supplied intensity-modulated light.
- The paper reports song playback from 100 Hz to 20 kHz.
- The reported song demonstration used about 20 mW RMS optical power.
- Acoustic pressure scaled linearly with optical power.
- Lower density improved conversion efficiency.
- Pulse-density modulation used approximately microsecond light pulses.
- PDM reduced a first harmonic from about 10 percent to about 1 percent.

Primary source:

- Flavio Giorgianni et al., **High Efficiency and Low Distortion Photoacoustic Effect in 3D Graphene Sponge**, arXiv:1806.03930.

This means the app alone is not the final speaker. The successful system was a matched pair:

```text
fast modulated LED
+
very low-density broadband absorber
```

## Forge V2 changes

### 1. Binary scanline PDM

Every active screen row is either fully on or fully off. Spatial error diffusion chooses the row states so total illuminated area follows the PCM waveform during panel scanout. This avoids asking slow grayscale transitions to behave like a precision analog light driver.

### 2. Physical rig grading

Forge emits synchronized tones at:

```text
100, 160, 250, 400, 630, 1000,
1600, 2500, 4000, 6300, 8000, 10000, 12500 Hz
```

The microphone recording is analyzed band by band. The app reports:

- noise floor
- response level
- SNR
- usable low and high frequency
- optical/acoustic power score
- speech readiness
- music readiness
- overall A–F grade

### 3. Measured inverse EQ

The app creates a smoothed inverse response from the measured jar profile. A cascade of peaking filters redistributes song energy away from over-loud resonances and toward weak but measurable bands, followed by optical limiting and renormalization.

Inverse EQ cannot restore a frequency with no measured signal. A silent band is a physical failure, not an EQ opportunity.

### 4. Microsecond external PDM

The RP2040 Forge firmware receives 48 kHz signed PCM and expands each sample into twenty one-bit pulses:

```text
48,000 × 20 = 960,000 optical decisions per second
```

A PIO state machine emits constant-height pulses approximately 1.04 microseconds wide. This mirrors the full-digital PDM strategy used in the graphene-sponge research.

## Receiver upgrade that preserves the painted jar

### Removable absorber cartridge

Keep the current jar untouched. Add a removable cartridge near its opening:

```text
light inlet
↓
very low-mass black porous absorber
↓ 2–10 mm air gap
jar cavity
↓
short ear tube or horn
```

Candidate tiers:

1. **Research target:** free-standing graphene aerogel or graphene sponge.
2. **Practical experimental target:** very lightweight open-cell carbon foam or carbonized porous sheet.
3. **Low-cost experiment:** an ultrathin sealed soot-coated membrane or foil insert.

A comparative absorber study found candle-soot coating produced a clearly higher photoacoustic response than the tested black paint and carbon-nanotube coatings below 1 kHz. Loose soot is an inhalation contaminant, so any soot experiment should be sealed inside a removable cartridge and handled without creating airborne dust.

Primary source:

- Jussi Rossi et al., **Photoacoustic characteristics of carbon-based infrared absorbers**, arXiv:2012.01568.

### Why the wall paint stays useful

The painted wall can remain as a secondary absorber and light trap. The cartridge becomes the fast broadband element, while the black jar reduces stray reflection and supplies acoustic volume.

### Optical concentration

A reflector, Fresnel sheet, clear water lens or ordinary convex lens can collect more of an external LED's output onto the absorber. Concentration does not create power, but it reduces light missing the active material.

Do not use an exposed high-power laser. Forge is designed around enclosed LED light because eye safety matters and the cited song demonstration used an LED.

### Acoustic output coupling

A short tube or horn from the jar opening can increase pressure at the ear by coupling the cavity to a smaller outlet. Run the rig grader for every tube length because a tube can amplify one band while cancelling another.

## Broadband cavity strategy

A single high-Q resonance is loud but narrow. Full music needs lower Q or several coupled resonances. The next physical experiments should compare:

- jar open
- jar with one short outlet tube
- jar with two different outlet lengths
- jar with a perforated multi-neck cap
- jar with a shallow front chamber plus the main chamber

Modern coupled-resonance research shows that interacting resonators can merge separate narrow features into a broader response. Ancient acoustic architecture provides an intriguing parallel: measurements of the Hal Saflieni Hypogeum found a strongly defined, geometrically related spectrum across multiple chambers. This does not establish ancient photoacoustic technology; it supports treating chamber geometry as an active spectral tool rather than a decorative container.

Sources:

- David Roca and Mahmoud I. Hussein, **Broadband and intense sound transmission loss by a coupled-resonance acoustic metamaterial**, arXiv:2106.02255.
- Kristina Wolfe, Douglas Swanson and Rupert Till, **The Frequency Spectrum and Geometry of the Hal Saflieni Hypogeum Appear Tuned**, arXiv:2010.13697.

## Frontier and fringe-inspired branches

### Photostrictive crystal membrane

Frequency-modulated light has driven free-standing barium-titanate membranes as resonant nanodrums. Ferroelectric membranes produced much larger light-driven deflection than paraelectric comparison films. A future receiver could combine a black photothermal coating with a photostrictive membrane.

Source:

- Saptam Ganguly et al., **Photostrictive actuators based on freestanding ferroelectric membranes**, arXiv:2305.03193.

### Gas-phase light speaker

Laboratory work has generated tones, music and recorded speech by modulating a laser absorbed by water vapor in air. This proves a passive solid is not the only possible receiving medium. It is not included as a home build because the optical system is specialized and laser safety is non-negotiable.

### Structured-light feedback

Photoacoustic transmission-matrix research has used acoustic feedback to choose optical patterns that focus more energy onto an absorbing target. Forge's PDM geometry and rig grader are a primitive first step toward this: generate candidate patterns, record the response, retain the strongest pattern, and iterate.

Source:

- Thomas Chaigne et al., **Light Focusing and Two-Dimensional Imaging Through Scattering Media using the Photoacoustic Transmission-Matrix**, arXiv:1402.0279.

### Ancient optics as geometry inspiration

Ancient and medieval artifacts such as the Nimrud and Visby crystal lenses demonstrate that shaped transparent materials were manufactured long before modern electronics, although their exact uses remain debated. The useful engineering idea is optical concentration and controlled apertures, not a claim that those artifacts carried audio.

## Development order

1. Install Forge V2 beside V1.
2. Grade the untouched painted jar using binary PDM.
3. Save the report and proof WAV.
4. Add a removable low-mass absorber cartridge.
5. Grade again without changing app settings.
6. Add an outlet tube or horn and grade again.
7. Test the external 960 kHz PDM LED controller.
8. Keep only modifications that measurably improve speech and music scores.
9. Once speech readiness exceeds 60, test complete sentences.
10. Once music readiness exceeds 65, test a short full-band song.

The score is deliberately strict. Rings and faint noise are evidence that some light-to-sound conversion exists, but they are not evidence that the complete waveform survived.
