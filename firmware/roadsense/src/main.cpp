#include <cinttypes>

#include "esp_log.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "roadsense/protocol.hpp"

#ifndef ROADSENSE_FIRMWARE_VERSION
#define ROADSENSE_FIRMWARE_VERSION "unknown"
#endif

namespace {

constexpr char kTag[] = "roadsense";
constexpr TickType_t kHealthInterval = pdMS_TO_TICKS(30'000);

void health_task(void*) {
    for (;;) {
        ESP_LOGI(
            kTag,
            "health firmware=%s protocol=%" PRIu16 " free_heap=%" PRIu32,
            ROADSENSE_FIRMWARE_VERSION,
            roadsense::kProtocolVersion,
            esp_get_free_heap_size());
        vTaskDelay(kHealthInterval);
    }
}

}  // namespace

extern "C" void app_main() {
    ESP_LOGI(
        kTag,
        "boot firmware=%s protocol=%" PRIu16 " mode=safe-scaffold",
        ROADSENSE_FIRMWARE_VERSION,
        roadsense::kProtocolVersion);

    const BaseType_t created = xTaskCreate(
        health_task,
        "health",
        3072,
        nullptr,
        tskIDLE_PRIORITY + 1,
        nullptr);

    if (created != pdPASS) {
        ESP_LOGE(kTag, "failed to create health task");
        esp_restart();
    }
}
