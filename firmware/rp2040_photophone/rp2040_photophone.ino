#include <Arduino.h>

// LightCode Photophone USB bulk/CDC receiver for Raspberry Pi Pico / RP2040.
// Tested design target: Earle Philhower Arduino-Pico core with USB CDC enabled.
// The PWM pin must drive a transistor or LED-current driver, never a high-power LED directly.

static constexpr uint8_t LED_PWM_PIN = 15;
static constexpr uint32_t OUTPUT_SAMPLE_RATE = 48000;
static constexpr uint32_t PWM_CARRIER_HZ = 250000;
static constexpr uint16_t PWM_CENTER = 2048;
static constexpr size_t RING_SIZE = 32768;

volatile uint16_t sampleRing[RING_SIZE];
volatile size_t ringRead = 0;
volatile size_t ringWrite = 0;
volatile uint32_t underruns = 0;
repeating_timer_t sampleTimer;

static uint32_t crc32Update(uint32_t crc, const uint8_t* data, size_t length) {
  crc = ~crc;
  for (size_t index = 0; index < length; ++index) {
    crc ^= data[index];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      const uint32_t mask = -(crc & 1u);
      crc = (crc >> 1u) ^ (0xEDB88320u & mask);
    }
  }
  return ~crc;
}

static size_t ringAvailable() {
  const size_t write = ringWrite;
  const size_t read = ringRead;
  return write >= read ? write - read : RING_SIZE - read + write;
}

static size_t ringFree() {
  return RING_SIZE - 1 - ringAvailable();
}

static void pushPcmSample(int16_t pcm) {
  while (ringFree() == 0) {
    delayMicroseconds(50);
  }
  const int32_t shifted = static_cast<int32_t>(pcm) + 32768;
  const uint16_t pwm = static_cast<uint16_t>((shifted * 4095L) / 65535L);
  sampleRing[ringWrite] = pwm;
  ringWrite = (ringWrite + 1) % RING_SIZE;
}

static bool timerCallback(repeating_timer_t*) {
  if (ringRead != ringWrite) {
    const uint16_t value = sampleRing[ringRead];
    ringRead = (ringRead + 1) % RING_SIZE;
    analogWrite(LED_PWM_PIN, value);
  } else {
    analogWrite(LED_PWM_PIN, PWM_CENTER);
    underruns++;
  }
  return true;
}

static bool readExact(uint8_t* destination, size_t length, uint32_t timeoutMs = 3000) {
  const uint32_t started = millis();
  size_t offset = 0;
  while (offset < length) {
    while (Serial.available() > 0 && offset < length) {
      destination[offset++] = static_cast<uint8_t>(Serial.read());
    }
    if (millis() - started > timeoutMs) return false;
    delayMicroseconds(50);
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
  const uint32_t center = readLe32(config + 12);
  const uint32_t carrier = readLe32(config + 16);

  // This firmware currently runs fixed 48 kHz, mono, signed 16-bit PCM.
  // Values are still parsed so future firmware can negotiate dynamically.
  (void)requestedRate;
  (void)channels;
  (void)bits;
  (void)center;
  (void)carrier;
}

static void handlePcmPacket() {
  uint8_t header[20];
  if (!readExact(header, sizeof(header))) return;

  const uint32_t sequence = readLe32(header + 0);
  const uint32_t sampleRate = readLe32(header + 4);
  const uint16_t sampleCount = readLe16(header + 8);
  const uint16_t flags = readLe16(header + 10);
  const uint32_t expectedCrc = readLe32(header + 12);
  (void)sequence;
  (void)flags;

  if (sampleRate != OUTPUT_SAMPLE_RATE || sampleCount == 0 || sampleCount > 2048) {
    return;
  }

  static uint8_t payload[4096];
  const size_t payloadBytes = static_cast<size_t>(sampleCount) * 2u;
  if (!readExact(payload, payloadBytes)) return;

  uint32_t crc = 0;
  crc = crc32Update(crc, header, 12);
  crc = crc32Update(crc, payload, payloadBytes);
  if (crc != expectedCrc) return;

  for (uint16_t sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex) {
    const uint16_t raw = readLe16(payload + sampleIndex * 2u);
    pushPcmSample(static_cast<int16_t>(raw));
  }
}

void setup() {
  Serial.begin(115200);
  analogWriteFreq(PWM_CARRIER_HZ);
  analogWriteRange(4095);
  pinMode(LED_PWM_PIN, OUTPUT);
  analogWrite(LED_PWM_PIN, PWM_CENTER);

  add_repeating_timer_us(
    -static_cast<int64_t>(1000000ULL / OUTPUT_SAMPLE_RATE),
    timerCallback,
    nullptr,
    &sampleTimer
  );
}

void loop() {
  if (Serial.available() < 4) {
    delayMicroseconds(100);
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
