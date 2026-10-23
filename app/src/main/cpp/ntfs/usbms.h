#ifndef DROIDDECK_USBMS_H
#define DROIDDECK_USBMS_H

#include <stdint.h>

typedef struct usbms_dev usbms_dev;

/*
 * fd is the descriptor from UsbDeviceConnection.getFileDescriptor(); the
 * interface must already be claimed on the Java side, which is what detaches
 * the kernel's usb-storage/uas driver.
 */
usbms_dev *usbms_open(int fd, int iface, uint8_t ep_in, uint8_t ep_out);
void usbms_close(usbms_dev *dev);

int usbms_inquiry(usbms_dev *dev, char vendor[9], char product[17]);
int usbms_capacity(usbms_dev *dev, uint64_t *blocks, uint32_t *block_size);
int usbms_read(usbms_dev *dev, uint64_t lba, uint32_t count, void *buf);
int usbms_write(usbms_dev *dev, uint64_t lba, uint32_t count, const void *buf);

uint32_t usbms_block_size(const usbms_dev *dev);
uint64_t usbms_blocks(const usbms_dev *dev);
const char *usbms_last_error(const usbms_dev *dev);

#endif
