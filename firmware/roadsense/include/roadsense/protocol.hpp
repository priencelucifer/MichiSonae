#pragma once

#include <cstdint>

namespace roadsense {

constexpr std::uint16_t kProtocolVersion = 2;

enum class MessageType : std::uint8_t {
    kCapabilities = 1,
    kTimeSync = 2,
    kRoadDetection = 3,
    kAcknowledgement = 4,
    kDeviceHealth = 5,
};

struct FrameHeader {
    std::uint16_t protocol_version;
    MessageType message_type;
    std::uint32_t boot_id;
    std::uint64_t sequence;
    std::uint64_t monotonic_time_ms;
    std::uint16_t payload_length;
};

static_assert(kProtocolVersion == 2);

}  // namespace roadsense
