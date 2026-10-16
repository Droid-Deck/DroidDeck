#ifndef DROIDDECK_QUEST_DEVICE_H
#define DROIDDECK_QUEST_DEVICE_H
#include <string.h>
#include <strings.h>

/* Presentation only; this does not select a GPU driver or expand driver support. */
static inline int quest_device_matches(const char *model, const char *device, const char *maker) {
    return !strcasecmp(model, "Quest 2") || !strcasecmp(model, "Quest 3") ||
           !strcasecmp(model, "Quest 3S") ||
           ((!strcasecmp(maker, "Oculus") || !strcasecmp(maker, "Meta")) &&
            (!strcmp(device, "hollywood") || !strcmp(device, "eureka") || !strcmp(device, "panther")));
}
#endif
