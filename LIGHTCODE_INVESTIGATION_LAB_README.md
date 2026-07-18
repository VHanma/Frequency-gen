# LightCode Investigation Lab

This separate Android clone combines the full looping Photophone options with controlled optical protocol research and a universal streaming payload encoder.

## Launcher icons

- **Universal Payload Encoder**: paste a direct URL, download and load it with one tap, choose any local file, or package typed text.
- **Investigation Protocol Lab**: declassified-protocol reconstruction, established communication codes, frontier patterns and separately labeled fringe-inspired timing experiments.
- **Photophone Loop**: the full original looping audio-to-light interface.
- **Photophone Loop Torch**: looping audio through the phone flashlight.

## Universal payload format

There is no fixed file-size ceiling in the streaming path. Files are hashed once, then reopened and transmitted in 16 KiB blocks rather than loaded into memory.

Header:

```text
64 × 0x55
LPU2
version
carrier ID
UTF-8 filename length + filename
uint64 payload length
SHA-256
```

Each block:

```text
BLK2
uint32 block index
uint32 block length
uint32 CRC32
payload bytes
```

End:

```text
END2
uint32 block count
uint64 payload length
SHA-256
```

## Carriers

- 4-FSK at approximately 1,200 bits/s
- Manchester BPSK at approximately 300 bits/s
- 16-position PPM at approximately 600 bits/s
- PRBS spread-spectrum at approximately 342 bits/s
- Torch OOK at approximately 10 bits/s

## Downloads

Pasted direct links are saved under:

```text
Download/LightCode-Investigation-Lab/Payloads/
```

The app downloads bytes without executing them. Web pages that require login, JavaScript, cookies or expiring authorization may not expose a direct downloadable file URL.

## Historical distinction

The located CIA/SRI remote-strobe protocol randomized null, 6 Hz and 16 Hz conditions. It did not encode arbitrary files. The app exports that historical trial schedule separately. Arbitrary user-selected payloads use the modern carriers listed above.

Live visible-light modes require confirmation that the emitter is enclosed or aimed into a jar or instrument sensor. The declassified strobe protocol is exported as a CSV rather than presented as a human-facing flashing program.
