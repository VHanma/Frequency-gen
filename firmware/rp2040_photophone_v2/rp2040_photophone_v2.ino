#include <Arduino.h>
#include "hardware/clocks.h"
#include "hardware/pio.h"
#include "hardware/pio_instructions.h"
#include "pico/multicore.h"

// LightCode Photophone Forge RP2040 firmware.
// 48 kHz signed PCM arrives by USB CDC/bulk and becomes 960 kHz one-bit PDM.
// Each optical pulse is about 1.04 microseconds wide.
// Drive a transistor or constant-current LED driver. Never drive a high-power LED directly.

static constexpr uint8_t LED_PDM_PIN = 15;
static constexpr uint32_t PCM_RATE = 48000;
static constexpr uint32_t PDM_OVERSAMPLE = 20;
static constexpr uint32_t PDM_RATE = PCM_RATE * PDM_OVERSAMPLE;
static constexpr size_t WORD_RING_SIZE = 32768;

static volatile uint32_t wordRing[WORD_RING_SIZE];
static volatile size_t wordRead = 0;
static volatile size_t wordWrite = 0;
static volatile uint32_t underrunWords = 0;

static PIO pdmPio = pio0;
static uint pdmStateMachine = 0;
static uint pdmProgramOffset = 0;
static uint32_t pdmAccumulator = 0;
static uint32_t packWord = 0;
static uint8_t packBits = 0;

static const uint16_t pdmInstructions[] = {
  pio_encode_out(pio_pins, 1)
};

static const pio_program pdmProgram = {
  .instructions = pdmInstructions,
  .length = 1,
  .origin = -1
};

static size_t ringAvailable() {
  const size_t write = wordWrite;
  const size_t read = wordRead;
  return write >= read ? write - read : WORD_RING_SIZE - read + write;
}

static size_t ringFree() {
  return WORD_RING_SIZE - 1 - ringAvailable();
}

static void pushWord(uint32_t word) {
  while (ringFree() == 0) {
    delayMicroseconds(20);
  }
  wordRing[wordWrite] = word;
  __dmb();
  wordWrite = (wordWrite + 1) % WORD_RING_SIZE;
}

static void pushBit(bool bit) {
  // PIO shifts right, so the first emitted bit is placed at bit zero.
  if (bit) packWord |= (1u << packBits);
  packBits++;
  if (packBits == 32) {
    pushWord(packWord);
    packWord = 0;
    packBits = 0;
  }
}

static void pcmToPdm(int16_t pcm) {
  const uint32_t target = static_cast<uint16_t>(static_cast<int32_t>(pcm) + 32768);
  for (uint32_t index = 0; index < PDM_OVERSAMPLE; ++index) {
    pdmAccumulator += target;
    const bool bit = pdmAccumulator >= 65536u;
    if (bit) pdmAccumulator -= 65536u;
    pushBit(bit);
  }
}

static void core1PdmFeeder() {
  const uint32_t silence = 0xAAAAAAAAu;
  while (true) {
    uint32_t word = silence;
    if (wordRead != wordWrite) {
      word = wordRing[wordRead];
      __dmb();
      wordRead = (wordRead + 1) % WORD_RING_SIZE;
    } else {
      underrunWords++;
    }
    pio_sm_put_blocking(pdmPio, pdmStateMachine, word);
  }
}

static void setupPdmPio() {
  pdmProgramOffset = pio_add_program(pdmPio, &pdmProgram);
  pdmStateMachine = pio_claim_unused_sm(pdmPio, true);
  pio_sm_config config = pio_get_default_sm_config();
  sm_config_set_wrap(&config, pdmProgramOffset, pdmProgramOffset);
  sm_config_set_out_pins(&config, LED_PDM_PIN, 1);
  sm_config_set_out_shift(&config, true, true, 32);
  const float divider = static_cast<float>(clock_get_hz(clk_sys)) / static_cast<float>(PDM_RATE);
  sm_config_set_clkdiv(&config, divider);
  pio_gpio_init(pdmPio, LED_PDM_PIN);
  pio_sm_set_consecutive_pindirs(pdmPio, pdmStateMachine, LED_PDM_PIN, 1, true);
  pio_sm_init(pdmPio, pdmStateMachine, pdmProgramOffset, &config);
  pio_sm_set_enabled(pdmPio, pdmStateMachine, true);
}

static uint32_t crc32UpdateRaw(uint32_t internalCrc, const uint8_t* data, size_t length) {
  uint32_t crc = internalCrc;
  for (size_t index = 0; index < length; ++index) {
    crc ^= data[index];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      const uint32_t mask = -(crc & 1u);
      crc = (crc >> 1u) ^ (0xEDB88320u & mask);
    }
  }
  return crc;
}

static bool readExact(uint8_t* destination, size_t length, uint32_t timeoutMs = 3000) {
  const uint32_t started = millis();
  size_t offset = 0;
  while (offset < length) {
    while (Serial.available() > 0 && offset < length) {
      destination[offset++] = static_cast<uint8_t>(Serial.read());
    }
    if (millis() - started > timeoutMs) return false;
    delayMicroseconds(25);
  }
  return true;
}

static uint16_t readLe16(const uint8_t* data) {
  return static_cast<uint16_t>(data[0]) |
         (static_cast<uint16_t>(data[1]) << 8u);
}

static uint32_t readLe32(const uint8_t* data) {
  return static_cast<uint32_t>(data[0]) |
         (static_cast<uint32_t>(data[1]) << 8u) |
         (static_cast<uint32_t>(data[2]) << 16u) |
         (static_cast<uint32_t>(data[3]) << 24u);
}

static void handleConfig() {
  uint8_t config[20];
  if (!readExact(config, sizeof(config))) return;
  const uint32_t requestedRate = readLe32(config + 0);
  const uint32_t channels = readLe32(config + 4);
  const uint32_t bits = readLe32(config + 8);
  (void)requestedRate;
  (void)channels;
  (void)bits;
  // The final two fields came from V1's PWM protocol. Forge intentionally uses fixed PDM.
}

static void handlePcmPacket() {
  uint8_t header[20];
  if (!readExact(header, sizeof(header))) return;

  const uint32_t sampleRate = readLe32(header + 4);
  const uint16_t sampleCount = readLe16(header + 8);
  const uint32_t expectedCrc = readLe32(header + 12);
  if (sampleRate != PCM_RATE || sampleCount == 0 || sampleCount > 2048) return;

  static uint8_t payload[4096];
  const size_t payloadBytes = static_cast<size_t>(sampleCount) * 2u;
  if (!readExact(payload, payloadBytes)) return;

  uint32_t internal = 0xFFFFFFFFu;
  internal = crc32UpdateRaw(internal, header, 12);
  internal = crc32UpdateRaw(internal, payload, payloadBytes);
  const uint32_t calculatedCrc = ~internal;
  if (calculatedCrc != expectedCrc) return;

  for (uint16_t index = 0; index < sampleCount; ++index) {
    pcmToPdm(static_cast<int16_t>(readLe16(payload + index * 2u)));
  }
}

void setup() {
  Serial.begin(115200);
  setupPdmPio();
  multicore_launch_core1(core1PdmFeeder);
}

void loop() {
  if (Serial.available() < 4) {
    delayMicroseconds(50);
    return;
  }

  uint8_t magic[4];
  if (!readExact(magic, sizeof(magic), 500)) return;
  if (magic[0] == 'L' && magic[1] == 'P' && magic[2] == 'C' && magic[3] == '1') {
    handleConfig();
  } else if (magic[0] == 'L' && magic[1] == 'P' && magic[2] == 'P' && magic[3] == '1') {
    handlePcmPacket();
  }
}
