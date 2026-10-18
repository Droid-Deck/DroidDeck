"""Exercise production DMA-BUF creation and destruction with real file descriptors.

Wayland endpoints are fake; buffer ownership functions are compiled verbatim.
"""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

from test_vk_pending_completion import function

SRC = Path(__file__).resolve().parents[2] / 'app/src/main/cpp/waylandcomp/src'

HARNESS = r'''
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#define MAX_PLANES 4
#define ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER 7
struct vkp_image;
/* PRODUCTION_STRUCTS */
struct wl_client { int unused; };
struct wl_resource { void *data; void (*destroy)(struct wl_resource *); };
struct client_info { unsigned dmabuf_buffers; };
static struct client_info info;
static int g_dev_state, fail_resource;
static unsigned created, protocol_errors, no_memory, sent_created, sent_failed;
static struct wl_resource *last_buffer;
static const int wl_buffer_interface=1, dbuf_buffer_impl=2;
static void *wl_resource_get_user_data(struct wl_resource *r) { return r->data; }
static struct wl_resource *wl_resource_create(struct wl_client *c, const void *iface,
                                             int version, uint32_t id) {
    (void)c; (void)iface; (void)version; (void)id;
    if (fail_resource) return NULL;
    ++created; last_buffer=calloc(1,sizeof(*last_buffer)); assert(last_buffer);
    return last_buffer;
}
static void wl_resource_set_implementation(struct wl_resource *r, const void *impl,
                                            void *data, void (*destroy)(struct wl_resource *)) {
    (void)impl; r->data=data; r->destroy=destroy;
}
static struct client_info *client_info_of(struct wl_client *c) { (void)c; return &info; }
static void wl_client_post_no_memory(struct wl_client *c) { (void)c; ++no_memory; }
static void __attribute__((unused)) wl_resource_post_error(struct wl_resource *r, uint32_t code, const char *text, ...) {
    (void)r; (void)text; assert(code == ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER);
    ++protocol_errors;
}
static void zwp_linux_buffer_params_v1_send_created(struct wl_resource *r, struct wl_resource *b) {
    (void)r; assert(b == last_buffer); ++sent_created;
}
static void zwp_linux_buffer_params_v1_send_failed(struct wl_resource *r) { (void)r; ++sent_failed; }
static void vkp_image_destroy(struct vkp_image *img) { assert(!img); }
/* PRODUCTION_FUNCTIONS */
int main(int argc, char **argv) {
    assert(argc == 5);
    g_dev_state=atoi(argv[1]); const int method=atoi(argv[2]);
    const int repeats=atoi(argv[3]); fail_resource=atoi(argv[4]);
    struct wl_client client={0};
    for (int iteration=0; iteration<repeats; ++iteration) {
        struct dmabuf_params *p=calloc(1,sizeof(*p)); assert(p);
        for (int i=0;i<MAX_PLANES;++i) p->fd[i]=-1;
        const int fd=open("/dev/null",O_RDONLY); assert(fd >= 0);
        p->fd[0]=fd; p->n_planes=1; p->stride[0]=16;
        struct wl_resource params={.data=p}; last_buffer=NULL;
        struct wl_resource *buffer=NULL;
        if (method == 0) buffer=params_do_create(&client,&params,9,4,4,1,0);
        else if (method == 1) params_create(&client,&params,4,4,1,0);
        else params_create_immed(&client,&params,9,4,4,1,0);
        if (method != 0) buffer=last_buffer;
        if (g_dev_state == -3) {
            assert(!buffer && !last_buffer && !created && !info.dmabuf_buffers && !no_memory);
            assert(protocol_errors == (unsigned)(iteration+1));
            assert(p->fd[0] == fd && fcntl(fd,F_GETFD) >= 0);
            assert(!sent_created && sent_failed == (method == 1 ? (unsigned)(iteration+1) : 0));
        } else if (fail_resource) {
            assert(!buffer && !created && !info.dmabuf_buffers && !protocol_errors);
            assert(no_memory == (unsigned)(iteration+1));
            assert(p->fd[0] == -1);
        } else {
            assert(buffer && created == (unsigned)(iteration+1));
            assert(info.dmabuf_buffers == (unsigned)(iteration+1));
            assert(!protocol_errors && !no_memory && !sent_failed);
            assert(p->fd[0] == -1);
            struct dmabuf_buffer *b=buffer->data;
            assert(b->fd[0] == fd && b->refs == 1 && b->width == 4 && b->height == 4);
            assert(fcntl(fd,F_GETFD) >= 0);
            buffer->destroy(buffer); free(buffer);
        }
        params_resource_destroy(&params);
        errno=0; assert(fcntl(fd,F_GETFD) == -1 && errno == EBADF);
    }
    return 0;
}
'''


class DmabufCreationQuarantineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source_dir=Path(os.environ.get('DROIDDECK_CREATION_TEST_SOURCE',SRC))
        db=(source_dir/'wl_dmabuf.c').read_text()
        comp=(source_dir/'compositor.c').read_text()
        vk=(source_dir/'vk_present.c').read_text()
        header=(source_dir/'compositor_internal.h').read_text()
        structs='\n'.join(re.search(r'struct '+name+r' \{.*?\n\};',header,re.S).group()
                          for name in ('dmabuf_params','dmabuf_buffer'))
        functions=[function(vk,'vkp_completion_unknown'),function(comp,'dmabuf_buffer_unref')]
        functions += [function(db,name) for name in ('dbuf_buffer_resource_destroy','params_do_create',
                      'params_create','params_create_immed','params_resource_destroy')]
        harness=HARNESS.replace('/* PRODUCTION_STRUCTS */',structs)
        harness=harness.replace('/* PRODUCTION_FUNCTIONS */','\n'.join(functions))
        cls.tmp=tempfile.TemporaryDirectory(prefix='droiddeck-creation-quarantine-')
        cls.addClassCleanup(cls.tmp.cleanup)
        src=Path(cls.tmp.name)/'probe.c';cls.binary=src.with_suffix('');src.write_text(harness)
        cc=shutil.which(os.environ.get('CC','cc'))
        if not cc:raise AssertionError('C compiler required')
        result=subprocess.run([cc,'-std=c11','-Wall','-Wextra','-Werror','-Wno-unused-parameter',
                               '-UNDEBUG',*shlex.split(os.environ.get('DROIDDECK_TEST_CFLAGS','')),
                               str(src),'-o',str(cls.binary)],capture_output=True,text=True)
        if result.returncode:raise AssertionError(result.stdout+result.stderr)

    def run_scenario(self,state,method=0,repeats=1,fail_resource=0):
        result=subprocess.run([str(self.binary),str(state),str(method),str(repeats),str(fail_resource)],
                              capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_unknown_completion_rejects_new_buffer(self):self.run_scenario(-3)
    def test_unknown_async_creation_rejects_new_buffer(self):self.run_scenario(-3,method=1)
    def test_unknown_immediate_creation_rejects_new_buffer(self):self.run_scenario(-3,method=2)
    def test_repeated_terminal_creation_does_not_accumulate_buffer_ownership(self):
        self.run_scenario(-3,repeats=100)
    def test_healthy_creation_and_destruction_close_fd(self):self.run_scenario(1)
    def test_device_lost_keeps_existing_creation_and_cleanup(self):self.run_scenario(-2)
    def test_healthy_async_creation_keeps_created_event(self):self.run_scenario(1,method=1)
    def test_healthy_immediate_creation_keeps_requested_resource(self):self.run_scenario(1,method=2)
    def test_wayland_allocation_failure_closes_transferred_fd(self):
        self.run_scenario(1,fail_resource=1)


if __name__=='__main__':unittest.main()
